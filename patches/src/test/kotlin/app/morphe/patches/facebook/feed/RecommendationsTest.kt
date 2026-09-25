/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed

import app.morphe.RepoFiles
import app.morphe.patches.facebook.feed.suggested.RECOMMENDATION_CONTEXT_STUB
import app.morphe.patches.facebook.feed.suggested.RECOMMENDATION_LABEL
import app.morphe.patches.facebook.shared.FEED_UNIT_EDGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anchors of the "Suggested for you" rule: GraphQLStory's recommendation accessor, Facebook's
 * own "hide suggested posts" validator and the edge predicate it asks. Each rule has a control that
 * must fail it.
 */
class RecommendationsTest {
    private val model = "Lcom/example/Model;"
    private val predicateOwner = "Lcom/example/Predicates;"

    @Test
    fun `the keys are the ones Facebook's builds load`() {
        assertEquals(0xd4bc5329.toInt(), treeFieldKey(RECOMMENDATION_CONTEXT_FIELD))
        assertEquals(0xcc3b6841.toInt(), treeTypeTag(RECOMMENDATION_CONTEXT_TYPE))
        assertEquals(0x6ca2c94b, treeFieldKey(RECOMMENDED_FLAG))
    }

    private fun method(
        name: String,
        vararg body: Instruction,
        definingClass: String = GRAPHQL_STORY,
        parameters: List<String> = emptyList(),
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        returnType: String = model,
    ): Method = ImmutableMethod(
        definingClass, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags, null, null,
        ImmutableMethodImplementation(8, body.toList(), null, null),
    )

    private fun literal(register: Int, value: Int) = ImmutableInstruction31i(Opcode.CONST, register, value)

    private fun call(opcode: Opcode, owner: String, name: String, parameters: List<String>, returns: String, vararg registers: Int) =
        ImmutableInstruction35c(
            opcode, registers.size, registers.getOrElse(0) { 0 }, registers.getOrElse(1) { 0 },
            registers.getOrElse(2) { 0 }, registers.getOrElse(3) { 0 }, 0,
            ImmutableMethodReference(owner, name, parameters, returns),
        )

    private fun accessor(name: String, field: String, type: String) = method(
        name,
        ImmutableInstruction21c(Opcode.CONST_CLASS, 2, ImmutableTypeReference(model)),
        literal(1, treeTypeTag(type)),
        literal(0, treeFieldKey(field)),
        call(Opcode.INVOKE_VIRTUAL, BASE_MODEL_WITH_TREE, "getCachedModel", listOf("I", "Ljava/lang/Class;", "I"),
            BASE_MODEL_WITH_TREE, 7, 0, 2, 1),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
    )

    @Test
    fun `the accessor is the one loading the recommendation context's two keys`() {
        val story = ImmutableClassDef(GRAPHQL_STORY, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null,
            null, listOf(
                accessor("A0f", RECOMMENDATION_CONTEXT_FIELD, RECOMMENDATION_CONTEXT_TYPE),
                accessor("A0X", "ai_generated_detected_info", "XFBAIGeneratedDetectedInfo"),
                accessor("A0g", RECOMMENDATION_CONTEXT_FIELD, "XFBAIGeneratedDetectedInfo"),
                accessor("A0h", "ai_generated_detected_info", RECOMMENDATION_CONTEXT_TYPE),
            ))
        assertEquals(listOf("A0f"), recommendationContextAccessors(story).map { it.name })
    }

    /** Facebook's predicate on 577 and 580: the edge's node, a GraphQLStory, its context, the flag. */
    private fun predicate(accessorName: String, key: Int) = method(
        "A00",
        call(Opcode.INVOKE_VIRTUAL, FEED_UNIT_EDGE, "BOq", emptyList(), "Ljava/lang/Object;", 2),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
        ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference(GRAPHQL_STORY)),
        call(Opcode.INVOKE_VIRTUAL, GRAPHQL_STORY, accessorName, emptyList(), model, 1),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
        ImmutableInstruction21t(Opcode.IF_EQZ, 1, 6),
        literal(0, key),
        call(Opcode.INVOKE_VIRTUAL, BASE_MODEL_WITH_TREE, "getCachedBoolean", listOf("I"), "Z", 1, 0),
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
        ImmutableInstruction11x(Opcode.RETURN, 1),
        definingClass = predicateOwner,
        parameters = listOf(FEED_UNIT_EDGE),
        flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        returnType = "Z",
    )

    @Test
    fun `the predicate has to read the recommended flag through that accessor`() {
        val accessor = accessor("A0f", RECOMMENDATION_CONTEXT_FIELD, RECOMMENDATION_CONTEXT_TYPE)
        assertTrue(readsRecommendedFlag(predicate("A0f", treeFieldKey(RECOMMENDED_FLAG)), accessor))
        assertFalse("another flag", readsRecommendedFlag(predicate("A0f", treeFieldKey("was_detected_as_ai_generated")), accessor))
        assertFalse("through another accessor", readsRecommendedFlag(predicate("A0e", treeFieldKey(RECOMMENDED_FLAG)), accessor))
    }

    /** Facebook's validator: the trace name, then its static edge predicate among other calls. */
    private fun validator(vararg calls: Instruction) = method(
        "CZn",
        ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(HIDE_RECOMMENDATIONS_VALIDATOR)),
        *calls,
        ImmutableInstruction11x(Opcode.RETURN, 0),
        definingClass = "Lcom/example/Validator;",
        parameters = listOf("Lcom/facebook/auth/usersession/FbUserSession;", FEED_UNIT_EDGE),
        returnType = "Z",
    )

    @Test
    fun `the validator's edge predicates are its static edge-to-boolean calls`() {
        val theOne = call(Opcode.INVOKE_STATIC, predicateOwner, "A00", listOf(FEED_UNIT_EDGE), "Z", 7)
        val found = edgePredicateCalls(validator(
            theOne,
            call(Opcode.INVOKE_VIRTUAL, predicateOwner, "A01", listOf(FEED_UNIT_EDGE), "Z", 5, 7),
            call(Opcode.INVOKE_STATIC, predicateOwner, "A02", listOf("Ljava/lang/Object;"), "Z", 7),
            call(Opcode.INVOKE_STATIC, predicateOwner, "A03", listOf(FEED_UNIT_EDGE), "V", 7),
        ))
        assertEquals(listOf("A00"), found.map { it.name })
    }

    @Test
    fun `a call resolves only to a static method of the same prototype`() {
        val call = ImmutableMethodReference(predicateOwner, "A00", listOf(FEED_UNIT_EDGE), "Z")
        val target = predicate("A0f", treeFieldKey(RECOMMENDED_FLAG))
        val sameNameInstance = method("A00", ImmutableInstruction11x(Opcode.RETURN, 0), definingClass = predicateOwner,
            parameters = listOf(FEED_UNIT_EDGE), returnType = "Z")
        val otherPrototype = method("A00", ImmutableInstruction11x(Opcode.RETURN, 0), definingClass = predicateOwner,
            parameters = listOf("Ljava/lang/Object;"), flags = AccessFlags.STATIC.value, returnType = "Z")
        fun owner(vararg methods: Method) = ImmutableClassDef(predicateOwner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            null, null, null, null, methods.toList())
        // Methods compare equal by signature, so the one handed back is held to by identity.
        assertTrue("the static method wasn't the one resolved",
            resolveStatic(owner(sameNameInstance, otherPrototype, target), call) === target)
        assertNull(resolveStatic(owner(sameNameInstance, otherPrototype), call))
    }

    @Test
    fun `a method holds a string only when it loads exactly that string`() {
        assertTrue(holdsString(validator(), HIDE_RECOMMENDATIONS_VALIDATOR))
        assertFalse(holdsString(validator(), "HideRecommendationsFeedUnitValidator"))
        assertFalse(holdsString(validator(), "$HIDE_RECOMMENDATIONS_VALIDATOR.extra"))
    }

    /** The patch and the extension name the same flag and type, and the same stub. Read as text. */
    @Test
    fun `the patch and the extension read the same flag through the same stub`() {
        val java = File(RepoFiles.root,
            "extensions/facebook/src/main/java/" + RECOMMENDATION_LABEL.removePrefix("L").removeSuffix(";") + ".java")
        assertTrue("the patch fills in a stub in ${java.path}, which isn't there", java.isFile)
        val text = java.readText()
        for (name in listOf(RECOMMENDATION_CONTEXT_FIELD, RECOMMENDATION_CONTEXT_TYPE, RECOMMENDED_FLAG)) {
            assertTrue("the extension doesn't name \"$name\"", text.contains("\"$name\""))
        }
        assertTrue("the extension has no public static Object $RECOMMENDATION_CONTEXT_STUB(Object)",
            Regex("""public static Object $RECOMMENDATION_CONTEXT_STUB\(Object \w+\)""").containsMatchIn(text))
    }
}
