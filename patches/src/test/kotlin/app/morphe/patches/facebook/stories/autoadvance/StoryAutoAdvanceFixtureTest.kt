/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.autoadvance

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryAutoAdvanceFixtureTest {
    @Test
    fun `each declared build has one completion hook and a separate manual navigation path`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val hooks = FixtureDex.classesHolding(bundle, AUTO_NAVIGATION).mapNotNull(::autoAdvanceHook)
                assertEquals("${bundle.name}: completion hook", 1, hooks.size)
                val (callback, index) = hooks.single()
                val implementation = callback.implementation!!
                val locals = implementation.registerCount - 1 - callback.parameterTypes.size
                assertTrue("${bundle.name}: no local register", locals >= 1)
                assertTrue("${bundle.name}: navigation call missing", index < implementation.instructions.count())

                // A separate touch handler must still call the same navigator. The patch only
                // guards the progress callback, so this call proves taps retain their route.
                val navigator = (implementation.instructions.elementAt(index) as ReferenceInstruction)
                    .reference as MethodReference
                fun callsNavigator(method: com.android.tools.smali.dexlib2.iface.Method) =
                    method.implementation?.instructions?.any { instruction ->
                        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        ref?.definingClass == navigator.definingClass && ref.name == navigator.name &&
                            ref.parameterTypes.map { it.toString() } == navigator.parameterTypes.map { it.toString() }
                    } == true
                val touchRoutes = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.methodSection.any { it.definingClass == navigator.definingClass && it.name == navigator.name }
                }) { method ->
                    method.parameterTypes.map { it.toString() } == listOf("Landroid/view/MotionEvent;") &&
                        callsNavigator(method)
                }
                assertEquals("${bundle.name}: manual touch route", 1, touchRoutes.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
