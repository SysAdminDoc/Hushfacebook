/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.storiestray

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two Stories tray adapter methods, found in every Facebook build the bundle declares the way
 * the patch finds them, each with a local register free for the hook. The tray controller's
 * constructor, which holds the start and stop names too, is there and is not picked. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class StoriesTrayAdapterFixtureTest {
    @Test
    fun `every declared build has one classic and one unified tray adapter, in one class`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val classic = FixtureDex.classesHolding(bundle, ADD_STORIES_ADAPTER).flatMap(::legacyTrayAdapters)
                assertEquals("${bundle.name}: ${classic.map { it.toString() }}", 1, classic.size)
                val configuration = FixtureDex.classes(bundle, setOf(classic.single().definingClass))
                    .getValue(classic.single().definingClass)
                val unified = unifiedTrayAdapters(configuration)
                assertEquals("${bundle.name}: ${unified.map { it.toString() }}", 1, unified.size)

                for (adapter in classic + unified) {
                    val implementation = adapter.implementation!!
                    val locals = implementation.registerCount - adapter.parameterTypes.size
                    assertTrue("${bundle.name}: $adapter has no local for the hook", locals >= 1)
                }

                // The control: the start name sits in more methods than the one picked, the tray
                // controller's constructor among them.
                val holdingStart = FixtureDex.classesHolding(bundle, TRAY_ADAPTER_START)
                    .flatMap { methodsHolding(it, TRAY_ADAPTER_START) }
                assertTrue("${bundle.name}: only ${holdingStart.size} method holds \"$TRAY_ADAPTER_START\"",
                    holdingStart.size >= 2)
                assertTrue("${bundle.name}: no constructor holds \"$TRAY_ADAPTER_START\" to tell apart",
                    holdingStart.any { AccessFlags.CONSTRUCTOR.isSet(it.accessFlags) })
                checked[version] = "${classic.single()} and ${unified.single()}"
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }
}
