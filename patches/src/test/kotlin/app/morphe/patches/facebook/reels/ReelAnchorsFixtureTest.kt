/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.Fixtures
import app.morphe.RepoFiles
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.shared.redexOriginalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every anchor Clean up Reels needs, found in every Facebook build the bundle declares the way the
 * patch finds it, each with the registers its hook borrows, and each picked out of lookalikes the
 * same literal or name also finds. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips
 * without it.
 */
class ReelAnchorsFixtureTest {
    private class Found {
        val builders = mutableListOf<Method>()
        var chipAnchorHolders = 0
        var immutableList: ClassDef? = null
        val dumpers = mutableListOf<Method>()
        val hotComments = mutableListOf<Method>()
        val bubbles = mutableListOf<Method>()
        var footerHelperClasses = 0
    }

    private val wanted = setOf(CHIP_ANCHOR, WATCH_FEED_DUMP, INLINE_COMMENTS_QUERY, VIDEO_ID)

    /** One pass over the bundle for everything but the Follow getter's readers. */
    private fun scan(bundle: File): Found {
        val found = Found()
        FixtureDex.forEach(bundle) { dex ->
            val present = dex.stringSection.filterTo(HashSet()) { it in wanted }
            for (classDef in dex.classes) {
                if (classDef.type == IMMUTABLE_LIST) found.immutableList = ImmutableClassDef.of(classDef)
                if (redexOriginalName(classDef)?.startsWith("FbShortsViewerFooter") == true) found.footerHelperClasses++
                if (CHIP_ANCHOR in present) {
                    found.chipAnchorHolders += classDef.methods.count { holdsString(it, CHIP_ANCHOR) }
                    chipListBuilders(classDef).mapTo(found.builders) { ImmutableMethod.of(it) }
                }
                if (WATCH_FEED_DUMP in present) {
                    classDef.methods.filter { holdsString(it, WATCH_FEED_DUMP) }.mapTo(found.dumpers) { ImmutableMethod.of(it) }
                }
                if (INLINE_COMMENTS_QUERY in present) {
                    footerRunnable(classDef, HOT_COMMENT_RUNNABLE, INLINE_COMMENTS_QUERY)?.let { found.hotComments += ImmutableMethod.of(it) }
                }
                if (VIDEO_ID in present) {
                    footerRunnable(classDef, SOCIAL_BUBBLES_RUNNABLE, VIDEO_ID)?.let { found.bubbles += ImmutableMethod.of(it) }
                }
            }
        }
        return found
    }

    /** Every method calling [getter], and the getter's own class. Only dex files referencing it are walked. */
    private fun readers(bundle: File, getter: MethodReference): Pair<List<Method>, ClassDef?> {
        val readers = mutableListOf<Method>()
        var owner: ClassDef? = null
        FixtureDex.forEach(bundle) { dex ->
            val defines = dex.classes.firstOrNull { it.type == getter.definingClass }
            if (defines != null) owner = ImmutableClassDef.of(defines)
            if (dex.methodSection.none { it == getter }) return@forEach
            for (classDef in dex.classes) {
                classDef.methods.filter { calls(it, getter) }.mapTo(readers) { ImmutableMethod.of(it) }
            }
        }
        return readers to owner
    }

    private fun locals(method: Method): Int {
        val self = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
        val parameters = method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
        return method.implementation!!.registerCount - self - parameters
    }

    @Test
    fun `every declared build has each anchor once, with registers for its hook`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val found = scan(bundle)

                // The chip list builder, one of the many methods naming the Remix chip.
                assertEquals("$name: ${found.builders}", 1, found.builders.size)
                assertTrue("$name: only ${found.chipAnchorHolders} methods hold \"$CHIP_ANCHOR\"", found.chipAnchorHolders >= 10)
                val builder = found.builders.single()
                val returns = builder.implementation!!.instructions.count { it.opcode == Opcode.RETURN_OBJECT }
                assertTrue("$name: the builder returns nothing", returns >= 1)
                assertTrue("$name: the builder has ${locals(builder)} locals", locals(builder) >= 2)
                assertTrue("$name: ImmutableList has no copyOf(Object[])", hasCopyOfArray(requireNotNull(found.immutableList) { "$name has no ImmutableList" }))

                // The Follow getter, traced from each dumped "removeFollowingButton" value.
                assertEquals("$name: ${found.dumpers}", 1, found.dumpers.size)
                val dumper = found.dumpers.single()
                val follow = followButtonGetter(dumper)
                assertNull("$name: ${follow.problem}", follow.problem)
                val getter = follow.getter!!
                val (readers, owner) = readers(bundle, getter)
                assertNull("$name: readers", followReaderProblem(readers, dumper))
                val method = requireNotNull(owner) { "$name has no ${getter.definingClass}" }.methods.single { it.name == getter.name && it.returnType == "Z" && it.parameterTypes.isEmpty() }
                assertTrue("$name: ${getter.name} is static", !AccessFlags.STATIC.isSet(method.accessFlags))
                assertTrue("$name: ${getter.name} has no local for the hook", locals(method) >= 1)

                // Both footer runnables, among the footer helpers' other kept-name classes.
                assertEquals("$name: ${found.hotComments}", 1, found.hotComments.size)
                assertEquals("$name: ${found.bubbles}", 1, found.bubbles.size)
                assertTrue("$name: ${found.footerHelperClasses} footer helper classes", found.footerHelperClasses >= 3)
                for (run in found.hotComments + found.bubbles) {
                    assertTrue("$name: $run has no local for the hook", locals(run) >= 1)
                }
                checked[version] = "${follow.literals} Follow literal(s), getter ${getter.definingClass}->${getter.name}"
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    /**
     * The Follow check, found the way the patch finds it: from the dump to the Following getter, to
     * its readers, to the author row among them, to the one check that row asks beside the Follow
     * button it builds. The getter alone missed the Reels tab on 0.1.7, since the row reads it only
     * for an author you already follow.
     */
    @Test
    fun `every declared build's author row asks one Follow check, with a local for its hook`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val dumpers = FixtureDex.classesHolding(bundle, WATCH_FEED_DUMP).flatMap { methodsHolding(it, WATCH_FEED_DUMP) }
                assertEquals("$name: $dumpers", 1, dumpers.size)
                val dumper = dumpers.single()
                val getter = requireNotNull(followButtonGetter(dumper).getter) { "$name: the dump names no Following getter" }
                val (readers, _) = readers(bundle, getter)
                val author = requireNotNull(authorRow(readers, dumper)) { "$name: no author row among $readers" }

                val owners = FixtureDex.classes(bundle, staticCalls(author).map { it.definingClass }.toSet())
                val asked = followCheck(author) { call -> owners[call.definingClass]?.let { resolveStatic(it, call) } }
                assertNull("$name: ${asked.problem}", asked.problem)
                val check = asked.check!!
                assertTrue("$name: ${check.name} isn't static", AccessFlags.STATIC.isSet(check.accessFlags))
                assertTrue("$name: ${check.name} has no local for the hook", locals(check) >= 1)
                checked[version] = "${check.definingClass}->${check.name}"
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    @Test
    fun `the patch requires the chips the extension hides`() {
        val declutter = File(RepoFiles.root,
            "extensions/facebook/src/main/java/app/morphe/extension/facebook/reels/ReelDeclutter.java")
        val array = Regex("""HIDDEN_CHIPS\s*=\s*\{([^}]*)\}""").find(declutter.readText())
            ?: throw AssertionError("ReelDeclutter.java has no HIDDEN_CHIPS array")
        val hidden = Regex(""""([A-Za-z]+)"""").findAll(array.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals(HIDDEN_CHIPS, hidden)
    }
}
