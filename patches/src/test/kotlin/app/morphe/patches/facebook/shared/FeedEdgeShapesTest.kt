/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.shared

import app.morphe.patcher.parametersMatch
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the patcher holds a method to for the funnel's fingerprint: its name, return type and parameters. */
internal fun admittedAsFeedFunnel(method: Method): Boolean {
    val fingerprint = AddNewEdgeToCollectionFingerprint
    val parameters = fingerprint.parameters ?: throw AssertionError("the funnel's parameters are not pinned")
    return method.name == fingerprint.name && method.returnType == fingerprint.returnType &&
        parametersMatch(method.parameterTypes, parameters)
}

/**
 * How the one feed guard finds its method and the edge's two getters, without a Facebook build: the
 * funnel's pinned shape, the refusal when the shape fits no method or several, and the getters'
 * refusals naming what they looked for.
 */
class FeedEdgeShapesTest {
    private val builder = "Lcom/google/common/collect/ImmutableList\$Builder;"

    private fun method(
        name: String,
        returnType: String,
        vararg parameters: String,
        definingClass: String = "Lfixture/Collection;",
        strings: List<String> = emptyList(),
    ): Method = ImmutableMethod(
        definingClass,
        name,
        parameters.map { ImmutableMethodParameter(it, null, null) },
        returnType,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null,
        null,
        ImmutableMethodImplementation(
            1,
            strings.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } +
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            null,
            null,
        ),
    )

    private fun funnel(definingClass: String = "Lfixture/Collection;") =
        method("addNewEdgeToCollection", "Z", builder, FEED_UNIT_EDGE, "Lfixture/Options;", definingClass = definingClass)

    private fun admits(method: Method) = admittedAsFeedFunnel(method)

    /** 577 and 580 both take the builder, the edge and one object of a class Redex renames. */
    @Test
    fun `the pinned shape takes the funnel both builds carry`() {
        assertTrue(admits(funnel()))
        assertTrue(admits(method("addNewEdgeToCollection", "Z", builder, FEED_UNIT_EDGE, "Lfixture/OtherOptions;")))
    }

    @Test
    fun `a method of that name and return type in another shape is not the funnel`() {
        val lookalikes = listOf(
            method("addNewEdgeToCollection", "Z", builder, FEED_UNIT_EDGE),
            method("addNewEdgeToCollection", "Z", builder, FEED_UNIT_EDGE, "Lfixture/Options;", "I"),
            method("addNewEdgeToCollection", "Z", FEED_UNIT_EDGE, builder, "Lfixture/Options;"),
            method("addNewEdgeToCollection", "Z", "Ljava/util/List;", FEED_UNIT_EDGE, "Lfixture/Options;"),
            method("addNewEdgeToCollection", "Z", builder, "Lcom/facebook/graphql/model/GraphQLStory;", "Lfixture/Options;"),
            method("addNewEdgeToCollection", "Z", builder, FEED_UNIT_EDGE, "I"),
            method("addNewEdgeToCollection", "V", builder, FEED_UNIT_EDGE, "Lfixture/Options;"),
        )
        for (lookalike in lookalikes) {
            assertFalse("${lookalike.parameterTypes.joinToString("")})${lookalike.returnType}", admits(lookalike))
        }
    }

    @Test
    fun `one funnel is the guard's method`() {
        val only = funnel()
        assertSame(only, listOf(only).oneFeedFunnel { it })
    }

    /**
     * The patcher's own lookup took the first of two without a word, which would guard one
     * collection and leave the other passing every edge.
     */
    @Test
    fun `two funnels stop the patch and name both`() {
        val refused = assertThrows(PatchException::class.java) {
            listOf(funnel("Lfixture/First;"), funnel("Lfixture/Second;")).oneFeedFunnel { it }
        }
        val message = refused.message.orEmpty()
        assertTrue(message, message.contains("found 2"))
        assertTrue(message, message.contains("Lfixture/First;->addNewEdgeToCollection("))
        assertTrue(message, message.contains("Lfixture/Second;->addNewEdgeToCollection("))
    }

    @Test
    fun `no funnel stops the patch`() {
        val refused = assertThrows(PatchException::class.java) { emptyList<Method>().oneFeedFunnel { it } }
        assertTrue(refused.message, refused.message.orEmpty().contains("found 0"))
    }

    @Test
    fun `the category getter is the one zero-argument method returning the enum`() {
        val getter = method("category", FEED_STORY_CATEGORY)
        val others = listOf(
            method("categoryFor", FEED_STORY_CATEGORY, "I"),
            method("typeName", "Ljava/lang/String;"),
        )
        assertSame(getter, storyCategoryGetterOf(others + getter))

        val none = assertThrows(PatchException::class.java) { storyCategoryGetterOf(others) }
        assertTrue(none.message, none.message.orEmpty().contains("GraphQLFeedStoryCategory"))
        assertTrue(none.message, none.message.orEmpty().contains("found 0"))

        val two = assertThrows(PatchException::class.java) {
            storyCategoryGetterOf(others + getter + method("category2", FEED_STORY_CATEGORY))
        }
        assertTrue(two.message, two.message.orEmpty().contains("found 2"))
    }

    @Test
    fun `the feed unit getter is the one zero-argument method holding its literal`() {
        val getter = method("unit", "Lfixture/FeedUnit;", strings = listOf("inflateFeedUnit"))
        val others = listOf(
            method("cachedUnit", "Lfixture/FeedUnit;"),
            method("unitFor", "Lfixture/FeedUnit;", "I", strings = listOf("inflateFeedUnit")),
            method("other", "Lfixture/FeedUnit;", strings = listOf("inflateFeedUnitLater")),
        )
        assertEquals("unit", feedUnitGetterOf(others + getter).name)

        val none = assertThrows(PatchException::class.java) { feedUnitGetterOf(others) }
        assertTrue(none.message, none.message.orEmpty().contains("inflateFeedUnit"))
        assertTrue(none.message, none.message.orEmpty().contains("found 0"))

        val two = assertThrows(PatchException::class.java) {
            feedUnitGetterOf(others + getter + method("unit2", "Lfixture/FeedUnit;", strings = listOf("inflateFeedUnit")))
        }
        assertTrue(two.message, two.message.orEmpty().contains("found 2"))
    }
}
