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
     * Whether a file's header agrees with its rule. Repositories are compared as whole names, so
     * MorpheApp/morphe-patches-library isn't taken for MorpheApp/morphe-patches. A "Forked from"
     * source has to be in the rule's chain, the header has to name a repository of it, and it may
     * name no repository outside it but this one. An original rule's file claims no source at all.
     */
    private fun headerProblem(path: String, header: String, rule: Rule): String? {
        val chain = rule.upstreams.map { repositoryOf(it) }.toSet()
        val named = REPOSITORY_URL.findAll(header).map { normalise(it.groupValues[1]) }.toSet()
        val outside = named - chain - OWN_REPOSITORY
        // Every "Forked from" line, not the first: a second one could name anything.
        val forks = FORKED_FROM.findAll(header).map { normalise(it.groupValues[1]) }.toSet()
        val strayFork = forks.firstOrNull { it !in chain }
        return when (rule.origin) {
            "ported" -> when {
                strayFork != null -> "$path says it was forked from $strayFork, which isn't in its rule's chain $chain"
                named.none { it in chain } ->
                    "$path falls under a rule ported from ${chain.first()}, but its header names none of $chain by URL"
                outside.isNotEmpty() -> "$path names $outside, which its rule's chain $chain doesn't include"
                else -> null
            }
            "original" -> when {
                CAME_FROM.containsMatchIn(header) ->
                    "$path falls under a rule for code written here, but its header says it came from elsewhere"
                outside.isNotEmpty() -> "$path names $outside, which its rule's chain $chain doesn't include"
                named.none { it in chain } -> "$path falls under a rule for code written here, but its header names none of $chain"
                else -> null
            }
            else -> "$path falls under a rule of unknown origin ${rule.origin}"
        }
    }

    private fun repositoryOf(url: String) = normalise(REPOSITORY_URL.find(url)?.groupValues?.get(1) ?: url)

    /** owner/name as GitHub compares it: without case, a trailing .git or a full stop after it. */
    private fun normalise(repository: String) = repository.trimEnd('.').removeSuffix(".git").lowercase()

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
        val morphe = Rule(listOf("z/**"), "ported", listOf("https://github.com/MorpheApp/morphe-patches"))
        val original = Rule(listOf("y/**"), "original", listOf("https://github.com/SysAdminDoc/Hushfacebook"))
        val ours = "/*\n * Copyright 2026 Hushfacebook contributors\n * https://github.com/SysAdminDoc/Hushfacebook\n */"
        val forked = "/*\n * Forked from:\n * https://github.com/andrewliang25/morphe-patches/blob/5db2e57/X.java\n */"
        val library = "/*\n * Copyright 2025 Morphe.\n * https://github.com/MorpheApp/morphe-patches-library\n */"
        val stub = "/*\n * From SysAdminDoc/hushfeed, unchanged: https://github.com/SysAdminDoc/hushfeed\n */"

        assertTrue(headerProblem("x/A.java", ours, ported) != null)
        assertTrue(headerProblem("x/A.java", "", ported) != null)
        assertEquals(null, headerProblem("x/A.java", forked, ported))
        assertTrue(headerProblem("y/A.java", forked, original) != null)
        assertEquals(null, headerProblem("y/A.java", ours, original))
        // A fork from another upstream than the rule's, a repository only sharing a prefix with
        // the rule's, and a file written here that says where it came from without "Forked".
        assertTrue("a fork from outside the chain passed", headerProblem("z/A.java", forked, morphe) != null)
        assertTrue("a name sharing a prefix passed", headerProblem("z/A.java", library, morphe) != null)
        assertTrue("a copied file passed as written here", headerProblem("y/A.java", stub, original) != null)
        // The same two claims with only the rule's own repository linked, so nothing but the
        // words themselves gives them away.
        val forkedInWords = "/*\n * Forked from andrewliang25/morphe-patches (GPL-3.0).\n * https://github.com/MorpheApp/morphe-patches\n */"
        val fromInWords = "/*\n * From SysAdminDoc/hushfeed, unchanged.\n * https://github.com/SysAdminDoc/Hushfacebook\n */"
        assertTrue("a fork named in words outside the chain passed", headerProblem("z/A.java", forkedInWords, morphe) != null)
        assertTrue("a copied file named in words passed as written here", headerProblem("y/A.java", fromInWords, original) != null)
        assertEquals("a rule naming the file wins over its folder's", "original",
            rulesFor("extensions/facebook/src/main/java/app/morphe/extension/facebook/download/MediaUrlPolicy.java")
                .single().origin)

        // A file written here that says where it came from in any of the ways a header does,
        // with the rule's own repository linked so only the words give it away.
        val ownLink = "\n * https://github.com/SysAdminDoc/Hushfacebook\n */"
        for (claim in listOf("Forked from the old settings screen.", "Copied from:", "Ported from:", "From:",
            "forked from SysAdminDoc/hushfeed", "Based on SysAdminDoc/hushfeed", "Adapted from the Hushfeed screen",
            "Taken from SysAdminDoc/hushfeed", "Derived from SysAdminDoc/hushfeed")) {
            assertTrue("\"$claim\" passed as written here", headerProblem("y/A.java", "/*\n * $claim$ownLink", original) != null)
        }
        assertEquals("\"Built on\" is how a file written here names its project", null,
            headerProblem("y/A.java", "/*\n * Copyright 2026 Hushfacebook contributors$ownLink\n * Built on SysAdminDoc/hushfeed (GPL-3.0).", original))

        // A repository outside the chain, linked however a header writes a link, beside the
        // chain's own; and a second "Forked from" after one in the chain.
        val chainLink = "\n * https://github.com/MorpheApp/morphe-patches\n */"
        for (outsider in listOf("http://github.com/evil/other", "https://www.github.com/evil/other",
            "https://GitHub.com/evil/other")) {
            assertTrue("$outsider passed", headerProblem("z/A.java", "/*\n * Forked from:\n * $outsider$chainLink", morphe) != null)
            assertTrue("$outsider named beside the chain passed",
                headerProblem("z/A.java", "/*\n * $outsider$chainLink", morphe) != null)
        }
        assertTrue("a second fork outside the chain passed", headerProblem("z/A.java",
            "/*\n * Forked from MorpheApp/morphe-patches.\n * Forked from evil/other.$chainLink", morphe) != null)
        assertEquals("the chain's own link, written with www. and capitals, is still the chain", null,
            headerProblem("z/A.java", "/*\n * Forked from:\n * https://www.GitHub.com/MorpheApp/morphe-patches\n */", morphe))
    }

    /** provenance.json's note says every rule states its licence, so every rule has to. */
    @Test
    fun everyRuleNamesItsLicence() {
        val ledger = JsonParser.parseString(File(RepoFiles.root, "provenance.json").readText()).asJsonObject
        val missing = ledger.getAsJsonArray("rules").mapNotNull { element ->
            val rule = element.asJsonObject
            val licence = rule.get("license")?.asString
            if (licence.isNullOrBlank()) "the rule for ${rule.getAsJsonArray("paths")} names no licence" else null
        }
        if (missing.isNotEmpty()) fail(missing.joinToString("\n"))
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

    private companion object {
        /** This repository, which a changed file may name beside where it came from. */
        val OWN_REPOSITORY = setOf("sysadmindoc/hushfacebook")
        /**
         * A repository link on either host, however it's written: http or https, with or without
         * www., the host in any case. Only the https lower-case form was read once, so the same
         * repository written any other way wasn't seen at all.
         */
        val REPOSITORY_URL = Regex("""(?i)https?://(?:www\.)?(?:github|gitlab)\.com/([\w.-]+/[\w.-]+)""")
        /** The source a "Forked from" line names, as a link on its line or the next, or as owner/name. */
        val FORKED_FROM = Regex("""(?i)forked from:?[\s*]*(?:https?://(?:www\.)?(?:github|gitlab)\.com/)?([\w.-]+/[\w.-]+)""")
        /**
         * A header line saying the file came from somewhere, however it's worded and whatever
         * follows it: prose, a colon, or a link on the next line. "Built on" isn't one: it's how a
         * file written here names the project it's written against.
         */
        val CAME_FROM = Regex(
            """(?im)^\s*\*?\s*(?:forked from|copied from|ported from|adapted from|taken from|derived from|based on|from)\b"""
        )
    }
}
