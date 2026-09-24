/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every name Meta's obfuscator made up for one Facebook build, written into this repository as
 * though it were the name of something.
 *
 * <p>Facebook's classes are renamed into package `X` on every release (`LX/1lD;` on 577 was
 * `LX/1vv;` on 573), and most method and field names become short tokens such as `A0K`, `B8f` or
 * `BQd`. A patch that writes one of them down applies on the build it was written against and
 * fails, or does the wrong thing, on the next one. The patches here resolve every such name while
 * patching, from anchors the obfuscator keeps: kept class and method names, log and trace
 * literals, enum constant names, debug-dump field names and method shapes. That is what let them
 * survive where FroggoMorphePatches, which wrote about a hundred of these down, did not.
 *
 * <p>What is here today is recorded in `obfuscated-identities.txt`, one `path|name` per line,
 * so that the list can only shrink: a name that turns up and is not in it fails this test, and
 * so does a line in it that no longer matches anything, so the record stays the truth.
 * Comments are not read, so a note naming what a build called something is fine.
 */
class ObfuscatedIdentityTest {
    @Test
    fun `java binary class names are inventoried as exact tokens`() {
        assertEquals(setOf("X.1vv"), identitiesIn("""Class.forName("X.1vv")""", extensionTree = true))
        assertTrue(identitiesIn("""Class.forName("LX.1vv")""", extensionTree = true).isEmpty())
    }

    @Test
    fun `obfuscated member names and descriptors are inventoried`() {
        assertEquals(setOf("LX/1lD;"), identitiesIn("""val edge = "LX/1lD;"""", extensionTree = false))
        assertEquals(setOf("B8f"), identitiesIn("""method.name == "B8f"""", extensionTree = false))
        assertEquals(setOf("A0K"), identitiesIn("""val field = "A0K"""", extensionTree = false))
    }

    @Test
    fun `real names of the same shape are not`() {
        assertTrue(identitiesIn("""category("Ads")""", extensionTree = false).isEmpty())
        assertTrue(identitiesIn("""if (name.startsWith("X.")) walk()""", extensionTree = true).isEmpty())
    }

    @Test
    fun `no name one build made up is written down that the record does not already hold`() {
        val found = scan()
        val recorded = recorded()
        val added = found - recorded
        val gone = recorded - found
        assertTrue(
            "New names a single build made up, written as identities. Anchor on what the thing " +
                "is instead, or record the line with a reason:\n" + added.joinToString("\n"),
            added.isEmpty(),
        )
        assertTrue(
            "Recorded names that are no longer in the code. Delete these lines so the record " +
                "keeps saying what is left:\n" + gone.joinToString("\n"),
            gone.isEmpty(),
        )
    }

    private fun recorded(): Set<String> {
        val text = javaClass.getResourceAsStream("/obfuscated-identities.txt")
            ?.bufferedReader()?.readText().orEmpty()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }

    private fun scan(): Set<String> {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val patches = File(repo, "patches/src/main/kotlin")
        val extension = File(repo, "extensions/facebook/src/main/java")
        assertTrue("could not find the sources from ${File(".").absolutePath}", patches.isDirectory && extension.isDirectory)

        val found = sortedSetOf<String>()
        for ((root, extensionTree) in listOf(patches to false, extension to true)) {
            root.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.forEach { file ->
                val path = file.relativeTo(repo).invariantSeparatorsPath
                file.readLines().forEach { raw ->
                    val line = raw.trim()
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) return@forEach
                    identitiesIn(line, extensionTree).forEach { found.add("$path|$it") }
                }
            }
        }
        return found
    }

    private fun identitiesIn(line: String, extensionTree: Boolean): Set<String> {
        val names = mutableSetOf<String>()
        OBFUSCATED_TYPE.findAll(line).forEach { names.add(it.value) }
        JAVA_BINARY_TYPE.findAll(line).forEach { names.add(it.value) }
        MEMBER_LITERAL.findAll(line).forEach { names.add(it.groupValues[1]) }
        PREFIX_LITERAL.findAll(line).forEach { names.add(it.groupValues[1]) }
        SHORT_MEMBER_COMPARED.findAll(line).forEach { names.add(it.groupValues[1]) }
        if (extensionTree && RESOURCE_LOOKUP.containsMatchIn(line)) {
            RESOURCE_NAME.findAll(line).forEach { names.add(it.groupValues[1]) }
        }
        return names - REAL_NAMES
    }

    private companion object {
        /** A class the obfuscator named for one build: `LX/1lD;`, or a merged lambda group `LY/...;`. */
        val OBFUSCATED_TYPE = Regex("""L[XY]/[0-9A-Za-z_$]+;""")

        /** The Java binary spelling of such a class: `X.1vv`, `X.B89`. */
        val JAVA_BINARY_TYPE = Regex("""(?<![0-9A-Za-z_$])X\.[0-9A-Za-z]{2,5}(?![0-9A-Za-z_$])""")

        /** A member name of the shape Meta's obfuscator gives Facebook's: `"A0K"`, `"B8f"`, `"BQd"`. */
        val MEMBER_LITERAL = Regex(""""([A-F][0-9A-Za-z]{2})"""")

        /** A prefix standing in for such names: `"LX/"`, `"LY/"`. */
        val PREFIX_LITERAL = Regex(""""(LX/|LY/)"""")

        /** A short name held against a member's: `name == "A0K"`, `it.name == "bq"`. */
        val SHORT_MEMBER_COMPARED = Regex("""name\s*==\s*"([A-Za-z][A-Za-z0-9]{0,2})"""")

        /** A line that looks a view or resource up by name, in the extension. */
        val RESOURCE_LOOKUP = Regex("""_IDS?\b|RESOURCE_NAME|getIdentifier\(|\.resolve\(|IDS\b|viewId\(|idOf\(""")

        /** A two or three character name on such a line: `"kzj"`, `"fb"`. */
        val RESOURCE_NAME = Regex(""""([a-z][a-z0-9_]{1,2})"""")

        /**
         * Names that match a shape above and are nobody's invention: the patch category, framework
         * members compared by name, and file extensions.
         */
        val REAL_NAMES = setOf("Ads", "get", "put", "run", "id", "raw", "mp3", "mp4", "m4a", "m4v")
    }
}
