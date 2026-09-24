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

    private fun matches(pattern: String, path: String): Boolean {
        require(pattern.endsWith("/**")) { "Only directory rules ending in /** are supported: $pattern" }
        return path.startsWith(pattern.removeSuffix("**"))
    }

    private fun rulesFor(path: String) = rules.filter { rule -> rule.paths.any { matches(it, path) } }

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
