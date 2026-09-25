/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.reels

import app.morphe.Fixtures
import app.morphe.RepoFiles
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.shared.FEED_STORY_CATEGORY
import app.morphe.patches.shared.compat.AppCompatibilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The reels categories the patch requires are in every Facebook build the bundle declares, and are
 * the ones the extension hides. The fixture half reads the bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it; the parity half reads both sources as text.
 */
class FeedReelsCategoryTest {
    @Test
    fun `every declared build files reels rows under the categories the patch requires`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val category = FixtureDex.classes(bundle, setOf(FEED_STORY_CATEGORY))[FEED_STORY_CATEGORY]
                    ?: throw AssertionError("${bundle.name} has no GraphQLFeedStoryCategory")
                val names = categoryNames(category)
                // The control: the categories the other feed rules match are read the same way.
                assertTrue("${bundle.name}: ${names.size} names", names.containsAll(listOf("SPONSORED", "PROMOTION", "ENGAGEMENT")))
                assertEquals("${bundle.name}: reels categories missing", emptyList<String>(), REELS_CATEGORIES.filterNot(names::contains))
                checked += version
            }
        }
        assertEquals("a declared build went unchecked", versions.toSet(), checked)
    }

    @Test
    fun `the patch requires the categories the extension hides`() {
        val filter = File(RepoFiles.root, "extensions/facebook/src/main/java/app/morphe/extension/facebook/feed/FeedFilter.java")
        val array = Regex("""REELS_CATEGORIES\s*=\s*\{([^}]*)\}""").find(filter.readText())
            ?: throw AssertionError("FeedFilter.java has no REELS_CATEGORIES array")
        val hidden = Regex(""""([A-Z_]+)"""").findAll(array.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals(REELS_CATEGORIES, hidden)
    }
}
