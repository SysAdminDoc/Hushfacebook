package app.morphe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle runs on Manager's Guava, which can be an older version than the one the build
 * compiles against (33.5.0 vs 33.7.1 at the time of writing). A patch that calls a method
 * added in a newer Guava would compile and pass every test here, then crash at runtime on
 * Manager's copy. This test makes that gap visible before it ships.
 */
class GuavaPatchSourceGuardTest {
    @Test
    fun patchSourcesDoNotImportGuavaDirectly() {
        val root = File("patches/src/main")
        if (!root.exists()) return
        val violations = root.walk()
            .filter { it.extension == "kt" || it.extension == "java" }
            .flatMap { file ->
                file.readLines()
                    .filter { it.trimStart().startsWith("import com.google.common") }
                    .map { "${file.relativeTo(root)}: ${it.trim()}" }
            }
            .toList()
        assertTrue(
            "Patch sources import Guava directly. The bundle runs on Manager's Guava, " +
                "which may be an older version than the compile classpath. Use the standard " +
                "library or the patcher's own API instead.\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
