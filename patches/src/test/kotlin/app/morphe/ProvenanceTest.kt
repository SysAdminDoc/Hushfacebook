/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Every shipped source file has to say where it came from, and every upstream it names has to be
 * carried in NOTICE. In this ecosystem a missing section 7b notice has already ended in a DMCA
 * notice twice (Morphe against the ReVanced network, and a ReVanced contributor against Morphe
 * Manager), so the ledger is checked rather than trusted.
 */
class ProvenanceTest {
    private data class Rule(val paths: List<String>, val origin: String, val upstreams: List<String>)

    private val rules: List<Rule> by lazy {
        val json = JsonParser.parseString(File(RepoFiles.root, "provenance.json").readText()).asJsonObject
        json.getAsJsonArray("rules").map { element ->
            val rule = element.asJsonObject
            val upstreams = mutableListOf(rule.get("upstream").asString)
            rule.getAsJsonArray("via")?.forEach { upstreams += it.asString }
            Rule(rule.getAsJsonArray("paths").map { it.asString }, rule.get("origin").asString, upstreams)
        }
    }

    private fun matchesDirectory(pattern: String, path: String) =
        pattern.endsWith("/**") && path.startsWith(pattern.removeSuffix("**"))

    /**
     * The rules a file falls under. A rule naming the file itself wins over the directory rule
     * around it, so a file written here can sit in a folder of ported code and still say so.
     */
    private fun rulesFor(path: String): List<Rule> {
        val named = rules.filter { rule -> path in rule.paths }
        if (named.isNotEmpty()) return named
        return rules.filter { rule -> rule.paths.any { matchesDirectory(it, path) } }
    }

    /**
     * Whether a file's header agrees with its rule: a ported rule's file names one of that rule's
     * upstreams or says where it was forked from, and an original rule's file doesn't claim a fork.
     */
    private fun headerProblem(path: String, header: String, rule: Rule): String? {
        val forked = header.contains("Forked from")
        return when (rule.origin) {
            "ported" -> if (forked || rule.upstreams.any { header.contains(repositoryOf(it)) }) null
                else "$path falls under a rule ported from ${rule.upstreams.first()}, but its header names none of ${rule.upstreams}"
            "original" -> if (forked) "$path falls under a rule for code written here, but its header says it was forked" else null
            else -> "$path falls under a rule of unknown origin ${rule.origin}"
        }
    }

    private fun repositoryOf(url: String) = url.removePrefix("https://github.com/").removePrefix("https://gitlab.com/")

    @Test
    fun everyShippedSourceMatchesExactlyOneRule() {
        val sources = RepoFiles.shippedSources()
        assertTrue("Found no shipped sources, so this check saw nothing", sources.size > 10)
        val problems = sources.mapNotNull { file ->
            val path = RepoFiles.relative(file)
            when (val count = rulesFor(path).size) {
                1 -> null
                else -> "$path matches $count provenance rules"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun anUnlistedPathMatchesNoRule() {
        // Positive control: the matcher must be able to say no, or the check above passes on anything.
        assertEquals(0, rulesFor("extensions/somewhere-new/src/main/java/X.java").size)
        assertEquals(0, rulesFor("patches/src/main/kotlin/app/morphe/patches/other/X.kt").size)
    }

    /**
     * The ledger filed MediaUrlPolicy.java, written here, under the rule for code copied from
     * andrewliang25/morphe-patches, and nothing compared the rule with the file. Now every file's
     * header has to agree with the rule it falls under.
     */
    @Test
    fun everyFileHeaderAgreesWithItsRule() {
        val problems = RepoFiles.shippedSources().mapNotNull { file ->
            val path = RepoFiles.relative(file)
            // A file under no rule, or two, is the first test's to report.
            val rule = rulesFor(path).singleOrNull() ?: return@mapNotNull null
            headerProblem(path, file.readText().substringBefore("\npackage "), rule)
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    /** Positive control for the check above: each kind of disagreement is reported. */
    @Test
    fun aHeaderThatDisagreesWithItsRuleIsReported() {
        val ported = Rule(listOf("x/**"), "ported", listOf("https://github.com/andrewliang25/morphe-patches"))
        val original = Rule(listOf("y/**"), "original", listOf("https://github.com/SysAdminDoc/Hushfacebook"))
        val ours = "/*\n * Copyright 2026 Hushfacebook contributors\n * https://github.com/SysAdminDoc/Hushfacebook\n */"
        val forked = "/*\n * Forked from:\n * https://github.com/andrewliang25/morphe-patches/blob/5db2e57/X.java\n */"

        assertTrue(headerProblem("x/A.java", ours, ported) != null)
        assertTrue(headerProblem("x/A.java", "", ported) != null)
        assertEquals(null, headerProblem("x/A.java", forked, ported))
        assertTrue(headerProblem("y/A.java", forked, original) != null)
        assertEquals(null, headerProblem("y/A.java", ours, original))
        assertEquals("a rule naming the file wins over its folder's", "original",
            rulesFor("extensions/facebook/src/main/java/app/morphe/extension/facebook/download/MediaUrlPolicy.java")
                .single().origin)
    }

    @Test
    fun portedRulesNameTheirCommitAndEveryUpstreamIsInNotice() {
        val notice = File(RepoFiles.root, "NOTICE").readText()
        val ledger = JsonParser.parseString(File(RepoFiles.root, "provenance.json").readText()).asJsonObject
        val missing = mutableListOf<String>()
        ledger.getAsJsonArray("rules").forEach { element ->
            val rule = element.asJsonObject
            if (rule.get("origin").asString == "ported" && !rule.has("commit")) {
                missing += "a ported rule for ${rule.getAsJsonArray("paths")} has no commit"
            }
        }
        rules.flatMap { it.upstreams }.distinct().forEach { upstream ->
            if (!notice.contains(upstream)) missing += "NOTICE does not name $upstream"
        }
        if (missing.isNotEmpty()) fail(missing.joinToString("\n"))
    }
}
