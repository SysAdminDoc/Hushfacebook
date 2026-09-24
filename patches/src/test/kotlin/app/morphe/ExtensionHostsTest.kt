/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the README's Privacy section says about the extension, held to its source: which web
 * addresses the code names, and the one place that opens a connection by itself.
 *
 * <p>Comments don't count. Licence headers and design notes name hosts the code never contacts,
 * so string literals and code are read apart first, which also stops the `//` inside a URL from
 * being taken for a comment.
 */
class ExtensionHostsTest {
    @Test
    fun `the extension names only the hosts the README lists`() {
        val found = sortedMapOf<String, MutableSet<String>>()
        for ((path, source) in sources()) {
            for (literal in split(source).first) {
                URL.findAll(literal).forEach { found.getOrPut(it.groupValues[1].lowercase()) { sortedSetOf() } += path }
            }
        }
        assertTrue("found no web address at all, so this compares nothing: $found", "github.com" in found)
        val others = found.filterKeys { it !in ALLOWED_HOSTS }
        assertTrue(
            "The extension names hosts the README's Privacy section doesn't list. Take them out, or " +
                "add them here and to that section:\n" +
                others.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString()}" },
            others.isEmpty(),
        )
    }

    @Test
    fun `only the media transport opens a connection itself`() {
        val openers = sources().filter { (_, source) -> NETWORK.containsMatchIn(split(source).second) }
            .map { it.first }.toSortedSet()
        assertEquals(
            "The README says the extension goes online by itself only to download what you save. " +
                "These files open connections",
            sortedSetOf(TRANSPORT),
            openers,
        )
    }

    @Test
    fun `strings and code are read apart`() {
        val (literals, code) = split(
            "String a = \"https://example.org/x\"; // HttpURLConnection\n" +
                "/* Socket */ char c = '\"'; String d = \"new Socket(\";"
        )
        assertEquals(listOf("https://example.org/x", "new Socket("), literals)
        assertFalse("a comment or a string was read as code: $code", NETWORK.containsMatchIn(code))
    }

    /** Every Java source file under an extension's `src/main`, by path from the repository root. */
    private fun sources(): List<Pair<String, String>> {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val extensions = File(repo, "extensions")
        assertTrue("could not find the extension sources from ${File(".").absolutePath}", extensions.isDirectory)
        return extensions.walkTopDown()
            .filter { it.isFile && it.extension == "java" && "/src/main/" in it.invariantSeparatorsPath }
            .map { it.relativeTo(repo).invariantSeparatorsPath to it.readText() }
            .sortedBy { it.first }
            .toList()
    }

    /** The string literals of [source], and its code with comments and literals taken out. */
    private fun split(source: String): Pair<List<String>, String> {
        val literals = mutableListOf<String>()
        val code = StringBuilder()
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                source.startsWith("/*", i) -> i = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it }
                    literals += source.substring(i + 3, end)
                    i = end + 3
                }
                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i]
                    var j = i + 1
                    while (j < source.length && source[j] != quote) j += if (source[j] == '\\') 2 else 1
                    if (quote == '"') literals += source.substring(i + 1, minOf(j, source.length))
                    code.append(' ')
                    i = j + 1
                }
                else -> code.append(source[i++])
            }
        }
        return literals to code.toString()
    }

    private companion object {
        /** The hosts the README's Privacy section names. */
        val ALLOWED_HOSTS = setOf("github.com", "gitlab.com", "www.gnu.org")
        const val TRANSPORT = "extensions/facebook/src/main/java/app/morphe/extension/facebook/download/Downloader.java"
        val URL = Regex("""(?:https?|wss?)://([A-Za-z0-9.-]+)""")
        val NETWORK = Regex(
            """\b(?:HttpURLConnection|HttpsURLConnection|URLConnection|openConnection|Socket|SSLSocket|""" +
                """OkHttpClient|WebSocket|DatagramSocket)\b"""
        )
    }
}
