/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe

import java.io.File

/** Paths into the repository, read as plain files: nothing here loads a patch or payload class. */
internal object RepoFiles {
    val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "provenance.json").isFile }
            ?: error("Could not find the repository root from ${File("").absolutePath}")
    }

    /** Every Java and Kotlin source that ships: patches, the patch stub and every extension module. */
    fun shippedSources(): List<File> {
        val roots = listOf(
            File(root, "patches/src/main"),
            File(root, "patches/stub/src/main"),
        ) + (File(root, "extensions").walkTopDown()
            .onEnter { it.name != "build" && it.name != "test" }
            .filter { it.isDirectory && it.name == "main" && it.parentFile?.name == "src" }
            .toList())
        return roots.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "java" || it.extension == "kt") }.toList() }
            .distinct()
            .sortedBy { it.path }
    }

    fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath
}
