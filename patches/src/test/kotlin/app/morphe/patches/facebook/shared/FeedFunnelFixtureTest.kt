/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.shared

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The funnel's pinned shape holds on every Facebook build the bundle declares: exactly one method
 * of its name has it, so the guard's lookup finds one and not two. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FeedFunnelFixtureTest {
    @Test
    fun `every declared build carries one funnel in the pinned shape`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val named = FixtureDex.methodsWhere(
                    bundle,
                    { dex -> dex.stringSection.any { it == "addNewEdgeToCollection" } },
                ) { it.name == "addNewEdgeToCollection" }
                val funnels = named.filter(::admittedAsFeedFunnel)
                assertEquals("${bundle.name}: $named", 1, funnels.size)
                checked[version] = "${named.size} method(s) of the name, 1 in the shape"
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }
}
