import app.morphe.patches.gradle.ExtensionExtension
import app.morphe.patches.gradle.ExtensionPlugin
import app.morphe.patches.gradle.PatchesExtension
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The moment the bundle says it was built.
 *
 * Two builds of one commit used to differ in exactly one field, the manifest's Timestamp, so a
 * third party could rebuild the bundle and never match the published hash: a checksum then
 * attests to one file rather than to the source it came from. This pins the field to
 * SOURCE_DATE_EPOCH when the environment sets one, otherwise to the commit being built,
 * otherwise to zero. Anything read from the clock would put the difference straight back.
 */
val sourceDateEpoch: Long = run {
    providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.trim()?.toLongOrNull()?.let {
        return@run it
    }
    try {
        providers.exec {
            commandLine("git", "log", "-1", "--format=%ct")
            workingDir = rootProject.projectDir
            isIgnoreExitValue = true
        }.standardOutput.asText.orNull?.trim()?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
        // No git, or no repository. Zero is stable, which is the only property that matters.
        0L
    }
}

/**
 * Rewrites the bundle with the timestamp pinned, leaving everything else as it was.
 *
 * <p>Every entry is written again rather than copied compressed, because the zip API offers no
 * way to move compressed bytes across without decoding them. That makes the output differ from
 * what the plugin first wrote, which does not matter: what matters is that two runs of this
 * produce the same bytes, and they do, because nothing here reads a clock.
 */
fun pinBundleTimestamp(bundle: File, epochSeconds: Long) {
    val stampMillis = epochSeconds * 1000L
    val names = mutableListOf<String>()
    val contents = mutableMapOf<String, ByteArray>()
    val times = mutableMapOf<String, Long>()
    val methods = mutableMapOf<String, Int>()

    ZipFile(bundle).use { zip ->
        for (entry in zip.entries()) {
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            names += entry.name
            contents[entry.name] = bytes
            times[entry.name] = entry.time
            methods[entry.name] = entry.method
        }
    }

    val manifestName = "META-INF/MANIFEST.MF"
    val manifest = contents[manifestName]
        ?: throw GradleException("The bundle has no $manifestName: $bundle")
    val pinned = String(manifest, Charsets.UTF_8)
        .replace(Regex("(?m)^Timestamp: [0-9]+"), "Timestamp: $stampMillis")
    if (!pinned.contains("Timestamp: $stampMillis")) {
        throw GradleException("The bundle manifest has no Timestamp line to pin: $bundle")
    }
    contents[manifestName] = pinned.toByteArray(Charsets.UTF_8)

    val rebuilt = ByteArrayOutputStream()
    ZipOutputStream(rebuilt).use { out ->
        for (name in names) {
            val bytes = contents.getValue(name)
            val entry = ZipEntry(name)
            entry.time = times.getValue(name)
            entry.method = methods.getValue(name)
            if (entry.method == ZipEntry.STORED) {
                entry.size = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
            }
            out.putNextEntry(entry)
            out.write(bytes)
            out.closeEntry()
        }
    }
    bundle.writeBytes(rebuilt.toByteArray())
}

/**
 * What the SBOM tasks below share. An object rather than functions of the script: a task class
 * that called into the script would capture it, and Gradle can't create a task of such a class.
 */
object Sbom {
    /** A component of a resolved graph, keyed the way the graph files name it. */
    class Node(val key: String, val kind: String, val group: String, val name: String, val version: String) {
        val dependsOn = sortedSetOf<String>()
    }

    /** One resolved graph as a graph file holds it: payload, root, components and artifacts. */
    class Graph(
        val payload: String,
        val root: String,
        val nodes: Map<String, Node>,
        /** Owner key to (file name, SHA-256) of each artifact the dex step or the jar is given. */
        val artifacts: Map<String, List<Pair<String, String>>>,
    )

    fun key(id: ComponentIdentifier): String = when (id) {
        is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
        is ProjectComponentIdentifier -> "project ${id.projectPath}"
        else -> throw GradleException("The SBOM can't name ${id.displayName}, a ${id::class.java.simpleName}.")
    }

    /**
     * Every component the graph reaches from its root, with the ones each depends on directly.
     * A constraint edge brings no component of its own, so it's not followed. A dependency that
     * didn't resolve stops the build: the SBOM can't say what it would have been.
     */
    fun walk(root: ResolvedComponentResult): Map<String, Node> {
        val nodes = sortedMapOf<String, Node>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            val key = key(component.id)
            if (nodes.containsKey(key)) continue
            val node = when (val id = component.id) {
                is ModuleComponentIdentifier -> Node(key, "module", id.group, id.module, id.version)
                is ProjectComponentIdentifier -> Node(key, "project", "", id.projectPath, "")
                else -> throw GradleException("The SBOM can't name ${id.displayName}, a ${id::class.java.simpleName}.")
            }
            nodes[key] = node
            for (dependency in component.dependencies) {
                if (dependency.isConstraint) continue
                if (dependency !is ResolvedDependencyResult) {
                    throw GradleException("${dependency.requested.displayName} did not resolve for $key, so the SBOM can't say what it is.")
                }
                node.dependsOn += key(dependency.selected.id)
                queue.addLast(dependency.selected)
            }
        }
        return nodes
    }

    /** The listed components a component reaches, looking through the ones left out of the SBOM. */
    fun listedDependsOn(nodes: Map<String, Node>, listed: Set<String>, from: String): Set<String> {
        val found = sortedSetOf<String>()
        val seen = mutableSetOf(from)
        val queue = ArrayDeque(nodes.getValue(from).dependsOn.toList())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            if (next in listed) found += next else queue.addAll(nodes[next]?.dependsOn ?: emptySet())
        }
        return found
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The files an artifact holds, by the path they'd have inside the bundle. */
    fun entryNames(artifact: File): Set<String> =
        if (artifact.isDirectory) {
            artifact.walkTopDown().filter { it.isFile }.map { it.relativeTo(artifact).invariantSeparatorsPath }.toSet()
        } else {
            ZipFile(artifact).use { zip -> zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet() }
        }

    /** A purl segment, percent-encoded outside the characters the purl spec leaves as they are. */
    fun purlSegment(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val plain = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '.'.code || c == '-'.code || c == '_'.code || c == '~'.code
            if (plain) append(c.toChar()) else append("%%%02X".format(c))
        }
    }

    fun purl(group: String, name: String, version: String) =
        "pkg:maven/${purlSegment(group)}/${purlSegment(name)}@${purlSegment(version)}"

    fun writeGraph(file: File, payload: String, root: String, nodes: Map<String, Node>, artifacts: List<Pair<String, File>>) {
        val lines = mutableListOf("payload\t$payload", "root\t$root")
        for (node in nodes.values) {
            lines += "node\t${node.key}\t${node.kind}\t${node.group}\t${node.name}\t${node.version}"
            for (target in node.dependsOn) lines += "edge\t${node.key}\t$target"
        }
        for ((owner, artifact) in artifacts.sortedWith(compareBy({ it.first }, { it.second.name }))) {
            lines += "artifact\t$owner\t${artifact.name}\t${sha256(artifact)}"
        }
        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    fun readGraph(file: File): Graph {
        var payload: String? = null
        var root: String? = null
        val nodes = sortedMapOf<String, Node>()
        val edges = mutableListOf<Pair<String, String>>()
        val artifacts = sortedMapOf<String, MutableList<Pair<String, String>>>()
        for (line in file.readLines(Charsets.UTF_8)) {
            if (line.isEmpty()) continue
            val fields = line.split('\t')
            when (fields[0]) {
                "payload" -> payload = fields[1]
                "root" -> root = fields[1]
                "node" -> nodes[fields[1]] = Node(fields[1], fields[2], fields[3], fields[4], fields[5])
                "edge" -> edges += fields[1] to fields[2]
                "artifact" -> artifacts.getOrPut(fields[1]) { mutableListOf() } += fields[2] to fields[3]
                else -> throw GradleException("$file has a line the SBOM task doesn't read: $line")
            }
        }
        for ((from, to) in edges) nodes.getValue(from).dependsOn += to
        return Graph(payload ?: throw GradleException("$file names no payload."),
            root ?: throw GradleException("$file names no root."), nodes, artifacts)
    }

    fun quote(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\n' -> append("\\n")
                ch.code < 0x20 || ch.code > 0x7e -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

    /** JSON in the order the maps were built, two spaces a level, ASCII only. */
    fun json(value: Any?, indent: String = ""): String {
        val inner = "$indent  "
        return when (value) {
            null -> "null"
            is String -> quote(value)
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(",\n", "{\n", "\n$indent}") {
                inner + quote(it.key.toString()) + ": " + json(it.value, inner)
            }
            is Iterable<*> -> if (!value.iterator().hasNext()) "[]" else value.joinToString(",\n", "[\n", "\n$indent]") {
                inner + json(it, inner)
            }
            else -> throw GradleException("The SBOM can't hold a ${value::class.java.simpleName}.")
        }
    }
}

/**
 * What one extension payload is dexed from: the extension's releaseRuntimeClasspath, which is the
 * program input of the R8 step (D8 with minify off) whose output the Morphe plugin copies into the
 * bundle as its .mpe. R8 drops the classes the payload never reaches, so this is what it was given,
 * not what it kept. Registered on each extension by :patches, and resolved by a task of the
 * extension's own: Gradle refuses a :patches task reading another project's graph, with "Current
 * thread does not hold the state lock for project".
 */
abstract class PayloadGraph : DefaultTask() {
    @get:Input
    abstract val payload: Property<String>

    @get:Input
    abstract val graph: Property<ResolvedComponentResult>

    /** "<key>\t<path>" for each library artifact the dex step is given. */
    @get:Input
    abstract val artifacts: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifactFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        val root = graph.get()
        Sbom.writeGraph(output.get().asFile, payload.get(), Sbom.key(root.id), Sbom.walk(root),
            artifacts.get().map { it.substringBefore('\t') to File(it.substringAfter('\t')) })
    }
}

/**
 * The CycloneDX SBOM of the release bundle, written beside it in build/release: every resolved
 * component the bundle carries and the version it resolved to, with the graph between them.
 *
 * Two things go into a bundle. The Morphe plugin's jar task takes the runtime classpath's
 * artifacts less every module, by group and name, that the patcher-provided classpath holds,
 * since the patcher that loads the bundle brings those itself; buildAndroid dexes that jar. And
 * each extension's payload is dexed from its releaseRuntimeClasspath. So the patcher, smali and
 * everything they bring are left out, with compile-only libraries, test graphs and the build's
 * own plugins, and the SBOM says so in its metadata. An SBOM listing the patcher's graph would
 * hold the release to advisories in code it doesn't ship, and to versions the Manager that loads
 * it doesn't use either.
 *
 * Checked against the bundle before it's written. Every file the bundle carries has to come from
 * the patch module's own output or from a library listed here, every library listed here has to
 * have files in it, and the payloads have to be the ones the extension projects describe. A
 * plugin release that bundled differently would otherwise leave an SBOM that describes the
 * rules above and not the file. Nothing here reads the clock: the timestamp is the pinned one
 * the bundle carries, and the serial number is derived from the bundle's hash, so a rebuild of
 * the same commit writes the same bytes.
 */
abstract class WriteReleaseSbom : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val bundle: RegularFileProperty

    @get:Input
    abstract val bundleVersion: Property<String>

    @get:Input
    abstract val epochSeconds: Property<Long>

    @get:Input
    abstract val gradleVersion: Property<String>

    @get:Input
    abstract val sourceUrl: Property<String>

    @get:Input
    abstract val runtimeGraph: Property<ResolvedComponentResult>

    /** "<key>\t<path>" for each library artifact of the runtime classpath. */
    @get:Input
    abstract val runtimeArtifacts: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeArtifactFiles: ConfigurableFileCollection

    /** "<group>:<name>" of every module the patcher-provided classpath holds. */
    @get:Input
    abstract val providedModules: SetProperty<String>

    /** The patch module's own classes and resources, which the jar carries as they are. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ownOutput: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val payloadGraphs: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    private class Component(val ref: String, val type: String, val group: String?, val name: String, val version: String, val purl: String?) {
        val carriers = sortedSetOf<String>()
        val artifacts = sortedMapOf<String, String>()
        val dependsOn = sortedSetOf<String>()
        var hash: String? = null
    }

    private fun ref(node: Sbom.Node): String =
        if (node.kind == "module") Sbom.purl(node.group, node.name, node.version) else "project" + node.name

    @TaskAction
    fun write() {
        val bundleFile = bundle.get().asFile
        val bundleName = bundleFile.name
        val version = bundleVersion.get()
        val bundleHash = Sbom.sha256(bundleFile)

        val bundleFiles = sortedSetOf<String>()
        val payloadHashes = sortedMapOf<String, String>()
        ZipFile(bundleFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                bundleFiles += entry.name
                if (entry.name.startsWith("extensions/") && entry.name.endsWith(".mpe")) {
                    payloadHashes[entry.name] = Sbom.sha256(zip.getInputStream(entry).use { it.readBytes() })
                }
            }
        }

        // The jar's share, as the plugin's jar task picks it.
        val provided = providedModules.get()
        val runtimeRoot = runtimeGraph.get()
        val runtimeNodes = Sbom.walk(runtimeRoot)
        val bundled = runtimeArtifacts.get().map { it.substringBefore('\t') to File(it.substringAfter('\t')) }.filter { (key, _) ->
            val node = runtimeNodes[key]
                ?: throw GradleException("The runtime classpath has an artifact of $key, which its graph doesn't reach.")
            "${node.group}:${node.name}" !in provided
        }

        // Written by the plugin itself: the manifest by the jar task, which leaves every library's
        // own out, and classes.dex by buildAndroid. Every library jar has a manifest too, so a
        // library would otherwise count as carried on the strength of a file that isn't its.
        val generated = setOf("META-INF/MANIFEST.MF", "classes.dex")
        val own = ownOutput.files.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        }.toSet()
        val fromLibraries = mutableSetOf<String>()
        val found = sortedMapOf<String, Int>()
        for ((key, artifact) in bundled) {
            val names = Sbom.entryNames(artifact) - generated
            fromLibraries += names
            found[key] = (found[key] ?: 0) + names.count { it in bundleFiles }
        }
        val unexplained = bundleFiles.filter { it !in generated && it !in payloadHashes && it !in own && it !in fromLibraries }
        if (unexplained.isNotEmpty()) {
            throw GradleException(
                "$bundleName carries ${unexplained.take(5).joinToString(", ")}" +
                    (if (unexplained.size > 5) " and ${unexplained.size - 5} more" else "") +
                    ", which neither the patch module's own output nor a library the SBOM lists provides. " +
                    "The Morphe plugin bundled something this SBOM would leave out."
            )
        }
        val absent = found.filterValues { it == 0 }.keys
        if (absent.isNotEmpty()) {
            throw GradleException(
                "The SBOM would list ${absent.joinToString(", ")} as carried by $bundleName, " +
                    "but the bundle holds none of their files."
            )
        }

        val graphs = payloadGraphs.files.sortedBy { it.path }.map { Sbom.readGraph(it) }
        val described = graphs.map { it.payload }.toSortedSet()
        if (described != payloadHashes.keys) {
            throw GradleException(
                "$bundleName carries the payloads ${payloadHashes.keys.joinToString(", ").ifEmpty { "(none)" }}, " +
                    "and the extension projects the Morphe plugin dexes describe " +
                    "${described.joinToString(", ").ifEmpty { "(none)" }}. The SBOM can't say what the difference is made of."
            )
        }

        val components = sortedMapOf<String, Component>()
        fun add(graphNodes: Map<String, Sbom.Node>, listed: Set<String>, carrier: String,
                hashes: (String) -> List<Pair<String, String>>) {
            for (key in listed) {
                val node = graphNodes.getValue(key)
                val ref = ref(node)
                val component = components.getOrPut(ref) {
                    if (node.kind == "module") {
                        Component(ref, "library", node.group, node.name, node.version, ref)
                    } else {
                        Component(ref, "library", null, node.name, version, null)
                    }
                }
                component.carriers += carrier
                for ((fileName, sha) in hashes(key)) component.artifacts[fileName] = sha
                for (target in Sbom.listedDependsOn(graphNodes, listed, key)) component.dependsOn += ref(graphNodes.getValue(target))
            }
        }

        val runtimeRootKey = Sbom.key(runtimeRoot.id)
        val bundledKeys = bundled.map { it.first }.toSortedSet()
        add(runtimeNodes, bundledKeys + runtimeRootKey, bundleName) { key ->
            bundled.filter { it.first == key }.map { it.second.name to Sbom.sha256(it.second) }
        }
        val bundleDependsOn = sortedSetOf(ref(runtimeNodes.getValue(runtimeRootKey)))
        for (graph in graphs) {
            val listed = graph.nodes.values.filter { it.kind == "project" || graph.artifacts.containsKey(it.key) }
                .map { it.key }.toSortedSet()
            add(graph.nodes, listed, graph.payload) { key -> graph.artifacts[key] ?: emptyList() }
            val payload = Component(graph.payload, "file", null, graph.payload, version, null)
            payload.hash = payloadHashes.getValue(graph.payload)
            payload.carriers += bundleName
            payload.dependsOn += ref(graph.nodes.getValue(graph.root))
            components[graph.payload] = payload
            bundleDependsOn += graph.payload
        }

        fun property(name: String, value: String) = linkedMapOf("name" to name, "value" to value)
        fun sha(content: String) = linkedMapOf("alg" to "SHA-256", "content" to content)
        val listedComponents = components.values.map { component ->
            val entry = linkedMapOf<String, Any?>("type" to component.type, "bom-ref" to component.ref)
            if (component.group != null) entry["group"] = component.group
            entry["name"] = component.name
            entry["version"] = component.version
            entry["scope"] = "required"
            val single = component.hash ?: component.artifacts.values.singleOrNull()
            if (single != null) entry["hashes"] = listOf(sha(single))
            if (component.purl != null) entry["purl"] = component.purl
            val properties = mutableListOf(property("hushfacebook:carried-by", component.carriers.joinToString(", ")))
            for ((fileName, content) in component.artifacts) properties += property("hushfacebook:artifact", "$fileName sha256:$content")
            if (component.purl == null && component.type == "library") {
                properties += property("hushfacebook:first-party", "built from this repository")
            }
            entry["properties"] = properties
            entry
        }
        val dependencies = listOf(linkedMapOf<String, Any?>("ref" to bundleName, "dependsOn" to bundleDependsOn.toList())) +
            components.values.map { linkedMapOf<String, Any?>("ref" to it.ref, "dependsOn" to it.dependsOn.toList()) }

        val document = linkedMapOf<String, Any?>(
            "bomFormat" to "CycloneDX",
            "specVersion" to "1.6",
            "serialNumber" to "urn:uuid:" + UUID.nameUUIDFromBytes("hushfacebook:$bundleName:$bundleHash".toByteArray(Charsets.UTF_8)),
            "version" to 1,
            "metadata" to linkedMapOf(
                "timestamp" to Instant.ofEpochSecond(epochSeconds.get()).toString(),
                "tools" to linkedMapOf("components" to listOf(
                    linkedMapOf("type" to "application", "name" to "Gradle", "version" to gradleVersion.get()))),
                "component" to linkedMapOf(
                    "type" to "file",
                    "bom-ref" to bundleName,
                    "name" to bundleName,
                    "version" to version,
                    "hashes" to listOf(sha(bundleHash)),
                    "externalReferences" to listOf(linkedMapOf("type" to "vcs", "url" to sourceUrl.get())),
                ),
                "properties" to listOf(
                    property("hushfacebook:covers",
                        "What $bundleName carries: the patch classes and the libraries the Morphe plugin bundles " +
                            "with them (:patches runtimeClasspath less every module :patches patcherProvidedClasspath " +
                            "holds), and what each extension payload is dexed from (its releaseRuntimeClasspath, " +
                            "which R8 is given as program input)."),
                    property("hushfacebook:leaves-out",
                        "The Morphe patcher, smali and everything they bring, which the Manager or CLI that loads " +
                            "the bundle supplies; compile-only libraries; test and lint graphs; and the build's own " +
                            "plugins and tools."),
                ),
            ),
            "components" to listedComponents,
            "dependencies" to dependencies,
        )
        val target = output.get().asFile
        target.parentFile.mkdirs()
        target.writeText(Sbom.json(document) + "\n", Charsets.UTF_8)
    }
}

group = "app.morphe"

patches {
    about {
        name = "Hushfacebook"
        description = "Hushfacebook patches for the Facebook app, built for Morphe. Sponsored posts, Reels, Stories and suggested clutter out of your feed, and more control over what Facebook shows you."
        source = "https://github.com/SysAdminDoc/Hushfacebook"
        author = "SysAdminDoc"
        contact = "https://github.com/SysAdminDoc/Hushfacebook/issues"
        website = "https://github.com/SysAdminDoc/Hushfacebook"
        license = "GNU General Public License v3.0, with additional GPL section 7 requirements"
    }
}

// Morphe patcher 1.12.0 asks for Bouncy Castle 1.77 and the Android build tools it brings ask
// for 1.79, so this module's graph resolved at 1.79: 1.77 is inside all six advisories
// (CVE-2025-8916 1.44 to 1.78, CVE-2026-5588 1.67 to 1.83, CVE-2025-14813, CVE-2026-0636
// fixed in 1.84, CVE-2026-8763, CVE-2026-13506 fixed in 1.85) and 1.79 is inside all but
// CVE-2025-8916. None of it reaches the
// payload, and the APK a user gets is
// signed by their own Manager with its own patcher, so this is the build and signing classpath
// here rather than anything shipped. The repository's rule is that a known-affected component
// does not stay in a reproducible graph either way. Every request is rewritten to the reviewed
// release the catalog pins, the same treatment :extensions:facebook gives its test graph.
val safeBouncyCastleVersion = libs.versions.bouncycastle.get()
// What the patcher and the Android build tools are known to ask for. A request for anything
// else is a version nobody has read the advisories for, and the gate below stops the build on
// it rather than rewriting it away in silence.
val reviewedBouncyCastleRequests = setOf("1.77", "1.79", safeBouncyCastleVersion)
// Guarded by hand rather than with a synchronized collection wrapper: in a Kotlin build script
// `java` is the Java extension, so the java.util package cannot be named here.
val requestedBouncyCastleVersions = sortedSetOf<String>()

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.bouncycastle") {
            // Recorded before the rewrite, and recorded as a name rather than dropped when the
            // request carries no version of its own: a request arriving through a platform or
            // a constraint would otherwise be rewritten and never counted.
            // Blank as well as null: a declaration with no version reports "" rather than null,
            // and a plain ?: leaves the failure message with an empty name in it.
            val asked = requested.version?.takeIf { it.isNotBlank() } ?: "a request with no version of its own"
            synchronized(requestedBouncyCastleVersions) { requestedBouncyCastleVersions.add(asked) }
            useVersion(safeBouncyCastleVersion)
            because("The build classpath must use the reviewed Bouncy Castle release.")
        }
    }
}

val verifyBouncyCastleBuildGraph = tasks.register("verifyBouncyCastleBuildGraph") {
    group = "verification"
    description = "Checks this module's resolvable graphs for unreviewed Bouncy Castle requests."

    doLast {
        // Resolving is what runs the rewrite above, so the requests are collected here rather
        // than read out of a set nothing has filled yet. Checking the resolved version instead
        // would prove nothing: the rewrite guarantees that answer, so its failure branch could
        // never run. The request underneath it is the live fact.
        // Named rather than "every resolvable configuration": the ones the patcher's graph
        // actually arrives on. Resolution failures are not caught. A swallowed one is how this
        // gate first passed while reporting a single module, because dependency verification
        // was refusing the very artifacts the force had just introduced.
        val inspected = listOf("patcherProvidedClasspath", "compileClasspath", "runtimeClasspath",
            "testCompileClasspath", "testRuntimeClasspath")
        val seen = sortedMapOf<String, String>()
        val missing = mutableListOf<String>()
        for (name in inspected) {
            val configuration = configurations.findByName(name)
            if (configuration == null || !configuration.isCanBeResolved) {
                missing += name
                continue
            }
            val modules = configuration.incoming.resolutionResult.allComponents
                .mapNotNull { it.moduleVersion?.takeIf { module -> module.group == "org.bouncycastle" } }
            for (module in modules) seen[module.name] = module.version
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "These configurations were not there to inspect: " + missing.joinToString(", ") +
                    ". The plugin renamed them, so this gate is looking at the wrong graph."
            )
        }
        // Named, not merely non-empty. A module that fails to resolve is absent from
        // allComponents rather than raising, so a force at a version that does not publish a
        // module leaves this reporting the ones that did resolve and passing. That is exactly
        // what 1.85.2 did: it is a bcprov-only release, and bcpkix and bcutil read FAILED in
        // the dependency report while this gate said the graph was clean.
        val expected = setOf("bcpkix-jdk18on", "bcprov-jdk18on", "bcutil-jdk18on")
        val absent = expected - seen.keys
        if (absent.isNotEmpty()) {
            throw GradleException(
                "Bouncy Castle " + absent.sorted().joinToString(", ") + " is not on the build " +
                    "graph. Either the force names a version that does not publish it, or the " +
                    "patcher stopped bringing it and this gate is now blind."
            )
        }
        val wrong = seen.filterValues { it != safeBouncyCastleVersion }
        if (wrong.isNotEmpty()) {
            throw GradleException(
                "Bouncy Castle resolved at " + wrong.entries.joinToString(", ") { "${it.key}:${it.value}" } +
                    " rather than the reviewed $safeBouncyCastleVersion."
            )
        }
        logger.lifecycle(
            "Bouncy Castle on the build graph: " + seen.entries.joinToString(", ") { "${it.key}:${it.value}" }
        )

        val requested = synchronized(requestedBouncyCastleVersions) { requestedBouncyCastleVersions.toSet() }
        val unreviewed = requested - reviewedBouncyCastleRequests
        if (unreviewed.isNotEmpty()) {
            throw GradleException(
                "The build graph now asks for Bouncy Castle " + unreviewed.sorted().joinToString(", ") +
                    ", which nobody has reviewed. It is being rewritten to $safeBouncyCastleVersion. " +
                    "Check the advisory for the requested release, then add it to " +
                    "reviewedBouncyCastleRequests or move the pin."
            )
        }
    }
}

// By type rather than by the one name, so a second test task cannot start on a graph nothing
// has looked at. :patches:test is what scripts/pre-push.ps1 runs when a patch source changes.
tasks.withType<Test>().configureEach {
    dependsOn(verifyBouncyCastleBuildGraph)
}

dependencies {
    compileOnly(libs.morphe.patcher)

    // Used by JsonGenerator.
    implementation(libs.gson)

    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))

    // The register and instruction helpers under app.morphe.util are the only thing tested
    // here. They take dexlib2 methods, so the patcher has to be on the test runtime classpath
    // as well as the compile one.
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.morphe.patcher)
    // Reads the signing certificate of every retained fixture. The patcher already brings this
    // exact version at run time; this puts it on the test compile classpath as well.
    testImplementation("com.android.tools.build:apksig:9.1.1")
    // Reads the resource tables of the fixtures, for the Material You theme's colour table. The
    // patcher already brings this exact version at run time; this puts it on the test compile
    // classpath as well.
    testImplementation("com.github.REAndroid:arsclib:a28c6fb2a7")
}

tasks {
    // The README tests read the README and the patch list, both outside this module. Declare
    // those inputs so Gradle reruns them when either changes.
    test {
        inputs.file(rootProject.file("README.md"))
            .withPropertyName("readme")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(rootProject.file("patches-list.json"))
            .withPropertyName("patchList")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(rootProject.file("provenance.json"))
            .withPropertyName("provenance")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        // ProvenanceTest also reads NOTICE and every shipped source's header. A header-only edit
        // compiles to the same classes, so without these the task came back up to date and the
        // check never saw the change.
        inputs.file(rootProject.file("NOTICE"))
            .withPropertyName("notice")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.files(
            rootProject.fileTree("patches/src/main"),
            rootProject.fileTree("patches/stub/src/main"),
            rootProject.fileTree("extensions") {
                include("**/src/main/**")
                exclude("**/build/**")
            },
        )
            .withPropertyName("shippedSources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        // The fixture tests skip when this is unset and read the folder when it is set. What the
        // folder holds is the input, not its name: a run whose APK was swapped, re-signed or
        // deleted under the same path has to run again, not come back up to date or out of the
        // build cache with the last folder's verdict. Relative, so where the folder sits on this
        // machine does not count, and an APK moved into or out of a subfolder does: the tests
        // read only the folder's top level, and name only would call that move no change.
        // Blank counts as unset, as Fixtures.kt reads it; File("") would be the whole project.
        val fixtureDirectory = providers.environmentVariable("HUSHFACEBOOK_FIXTURE_DIR")
        inputs.files(fixtureDirectory.map { if (it.isBlank()) emptyList() else listOf(File(it)) }.orElse(emptyList()))
            .withPropertyName("fixtures")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    // The bundle a release publishes lives in build/release, not build/libs. The plugin's
    // buildAndroid merges the DEX payload into the jar task's own output in place, so any later
    // task that reruns jar (test does) put the plain jar back over the finished bundle under the
    // same name: v0.43.0 shipped with no classes.dex that way, and on 2026-09-21 the pre-push
    // test run did it again between the build and the index push. Nothing but buildAndroid
    // writes build/release. scripts/common.ps1 names the same path for every release script.
    val releaseBundleName = "patches-${project.version}.mpp"
    val verifyBundle = register<JavaExec>("verifyBundle") {
        group = "verification"
        description = "Check the Android bundle and its published patch list without rebuilding it"
        dependsOn(classes)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.BundleVerifier")
        args(
            providers.gradleProperty("patchBundle").getOrElse(
                layout.buildDirectory.file("release/$releaseBundleName").get().asFile.absolutePath
            ),
            rootProject.file("patches-list.json").absolutePath,
            project.version.toString(),
            layout.buildDirectory.file("release/bundle.sha256").get().asFile.absolutePath
        )
    }
    // The extensions the bundle carries, found the way the Morphe plugin's patches plugin finds
    // them: the subprojects of its extensions project that apply its extension plugin. Each one's
    // graph is read by a task of its own, for the lock reason PayloadGraph gives.
    val extensionsPath = project.extensions.getByType(PatchesExtension::class.java).extensionsProjectPath ?: ":extensions"
    val payloadGraphTasks = rootProject.project(extensionsPath).subprojects
        .filter { it.plugins.hasPlugin(ExtensionPlugin::class.java) }
        .map { extensionProject ->
            extensionProject.tasks.register<PayloadGraph>("sbomPayloadGraph") {
                description = "Records what this extension's payload is dexed from, for :patches:releaseSbom"
                // The payload's path in the bundle, as the plugin names it when the build doesn't.
                payload.set(extensionProject.extensions.getByType(ExtensionExtension::class.java).name
                    ?: extensionProject.projectDir.resolveSibling(extensionProject.name + ".mpe")
                        .relativeTo(extensionProject.rootDir).invariantSeparatorsPath)
                val runtime = extensionProject.configurations.named("releaseRuntimeClasspath")
                graph.set(runtime.flatMap { it.incoming.resolutionResult.rootComponent })
                val libraries = runtime.map { configuration ->
                    configuration.incoming.artifactView { componentFilter { it is ModuleComponentIdentifier } }.artifacts
                }
                artifacts.set(libraries.flatMap { it.resolvedArtifacts }.map { resolved ->
                    resolved.map { Sbom.key(it.id.componentIdentifier) + "\t" + it.file.absolutePath }.sorted()
                })
                artifactFiles.from(libraries.map { it.artifactFiles })
                output.set(extensionProject.layout.buildDirectory.file("sbom/payload-graph.tsv"))
            }
        }
    // Beside the bundle, named for it, and written only after buildAndroid has finished it: a
    // finalizer that also depends on it runs when it succeeds and not otherwise, so a failed build
    // can't leave an SBOM describing the bundle before it. scripts/build-release-receipt.ps1 reads
    // it from there and records its hash in the receipt.
    val releaseSbomName = "patches-${project.version}.cdx.json"
    val releaseSbom = register<WriteReleaseSbom>("releaseSbom") {
        group = "build"
        description = "Writes the CycloneDX SBOM of the release bundle into build/release beside it"
        dependsOn("buildAndroid")
        bundle.set(layout.buildDirectory.file("release/$releaseBundleName"))
        bundleVersion.set(project.version.toString())
        epochSeconds.set(sourceDateEpoch)
        gradleVersion.set(gradle.gradleVersion)
        sourceUrl.set(project.extensions.getByType(PatchesExtension::class.java).about.source ?: "")
        val runtime = configurations.named("runtimeClasspath")
        runtimeGraph.set(runtime.flatMap { it.incoming.resolutionResult.rootComponent })
        val libraries = runtime.map { configuration ->
            configuration.incoming.artifactView { componentFilter { it is ModuleComponentIdentifier } }.artifacts
        }
        runtimeArtifacts.set(libraries.flatMap { it.resolvedArtifacts }.map { resolved ->
            resolved.map { Sbom.key(it.id.componentIdentifier) + "\t" + it.file.absolutePath }.sorted()
        })
        runtimeArtifactFiles.from(libraries.map { it.artifactFiles })
        providedModules.set(configurations.named("patcherProvidedClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }
            .map { resolved ->
                resolved.mapNotNull { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.let { id -> "${id.group}:${id.module}" } }.toSet()
            })
        ownOutput.from(sourceSets["main"].output)
        payloadGraphs.from(payloadGraphTasks.map { graphTask -> graphTask.flatMap { it.output } })
        output.set(layout.buildDirectory.file("release/$releaseSbomName"))
    }
    named("buildAndroid") {
        // Resolved at configuration time. Reaching for project inside doLast is what the
        // configuration cache refuses, and Gradle 10 turns that refusal into an error.
        val bundleFile = layout.buildDirectory.file("libs/$releaseBundleName")
        val releaseDirectory = layout.buildDirectory.dir("release")
        val pinnedEpoch = sourceDateEpoch
        doLast {
            // Emptied first, so the directory never holds a bundle of another version or a
            // checksum of another build: the release scripts take the one file they find.
            val directory = releaseDirectory.get().asFile
            directory.mkdirs()
            directory.listFiles()?.filter { it.isFile }?.forEach { stale ->
                if (!stale.delete()) throw GradleException("Could not clear the old release file $stale")
            }
            val releaseBundle = directory.resolve(releaseBundleName)
            bundleFile.get().asFile.copyTo(releaseBundle)
            // Before the checksum, so what is recorded is what a rebuild will produce.
            pinBundleTimestamp(releaseBundle, pinnedEpoch)
            // Record only at the producer boundary. Standalone verification must not
            // bless a modified bundle by generating its own expected checksum.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(releaseBundle.readBytes())
                .joinToString("") { "%02x".format(it) }
            directory.resolve("bundle.sha256").writeText(digest)
        }
        finalizedBy(verifyBundle)
        finalizedBy(releaseSbom)
    }
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        // jar, not build. build runs check, which runs test, and ReadmePatchNamesTest asserts
        // that the checked-in patches-list.json names exactly the patches the Kotlin sources
        // declare. Adding or renaming a patch therefore failed the test before the task that
        // regenerates the list could run, and the only ways through were -x test or editing the
        // generated JSON by hand. The generator reads build/libs/patches-<version>.mpp, which
        // jar produces; the test and verifyBundle are still the gate afterwards, which is the
        // order that can actually pass.
        dependsOn(jar)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
        args(project.version.toString())
    }
    // The patch list has to be regenerated before anything publishes the bundle.
    publish {
        dependsOn("generatePatchesList")
    }
}
