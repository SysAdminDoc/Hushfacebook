/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.aidetected

import app.morphe.RepoFiles
import app.morphe.patches.facebook.feed.BASE_MODEL_WITH_TREE
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.TREE_JNI
import app.morphe.patches.facebook.feed.hasPublicBooleanReader
import app.morphe.patches.facebook.feed.hasPublicTypeTag
import app.morphe.patches.facebook.feed.treeFieldKey
import app.morphe.patches.facebook.feed.treeTypeTag
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keys the GenAI patch looks for, and the shapes it takes for GraphQLStory's accessor and for
 * Facebook's own label reading the flag through it. Each rule has a control that must fail it.
 */
class GenAiTransparencyTest {
    private val model = "Lcom/example/InfoModel;"

    /**
     * The keys, as the fixtures carry them. The scheme itself is checked on names Facebook writes
     * beside their keys: `name` and `label` in GenAiTransparencyPlugin, and WatchInterest's tag in
     * a model's own tree builder (573).
     */
    @Test
    fun `the keys are the ones Facebook's builds load`() {
        assertEquals(3373707, treeFieldKey("name"))
        assertEquals(102727412, treeFieldKey("label"))
        assertEquals(0x6b360757, treeTypeTag("WatchInterest"))

        assertEquals(0xb4f9e684.toInt(), treeFieldKey(DETECTED_INFO_FIELD))
        assertEquals(0x70da9d19, treeTypeTag(DETECTED_INFO_TYPE))
        assertEquals(0x723ea5fe, treeFieldKey(DETECTED_FLAG))
        // The self-disclosure accessor beside it loads these, and has to stay a different method.
        assertEquals(0x73da0c74, treeFieldKey("ai_generated_self_disclosure_info"))
        assertEquals(0x9213d34e.toInt(), treeTypeTag("XFBAIGeneratedSelfDisclosureInfo"))
    }

    private fun method(
        name: String,
        vararg body: Instruction,
        definingClass: String = GRAPHQL_STORY,
        parameters: List<String> = emptyList(),
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        returnType: String = model,
    ): Method = ImmutableMethod(
        definingClass, name, parameters.map { ImmutableMethodParameter(it, null, null) },
        returnType, flags, null, null,
        ImmutableMethodImplementation(8, body.toList(), null, null),
    )

    private fun literal(register: Int, value: Int) = ImmutableInstruction31i(Opcode.CONST, register, value)

    private fun treeCall(name: String, parameters: List<String>, returnType: String, vararg registers: Int) =
        ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL, registers.size,
            registers.getOrElse(0) { 0 }, registers.getOrElse(1) { 0 }, registers.getOrElse(2) { 0 },
            registers.getOrElse(3) { 0 }, 0,
            ImmutableMethodReference(BASE_MODEL_WITH_TREE, name, parameters, returnType),
        )

    private fun getCachedModel() = treeCall("getCachedModel", listOf("I", "Ljava/lang/Class;", "I"), BASE_MODEL_WITH_TREE, 7, 0, 2, 1)

    /** GraphQLStory's accessor as 573, 577 and 580 write it. */
    private fun accessor(name: String = "A0X", field: Int = treeFieldKey(DETECTED_INFO_FIELD), tag: Int = treeTypeTag(DETECTED_INFO_TYPE)) =
        method(
            name,
            ImmutableInstruction21c(Opcode.CONST_CLASS, 2, ImmutableTypeReference(model)),
            literal(1, tag),
            literal(0, field),
            getCachedModel(),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(model)),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )

    @Test
    fun `the accessor is picked by both keys together`() {
        assertTrue(isDetectedInfoAccessor(accessor()))
        assertFalse("the self-disclosure accessor",
            isDetectedInfoAccessor(accessor(field = treeFieldKey("ai_generated_self_disclosure_info"),
                tag = treeTypeTag("XFBAIGeneratedSelfDisclosureInfo"))))
        assertFalse("the field key alone", isDetectedInfoAccessor(accessor(tag = 0x1234)))
        assertFalse("the type tag alone", isDetectedInfoAccessor(accessor(field = 0x1234)))
        assertFalse("no getCachedModel", isDetectedInfoAccessor(method("A0X",
            literal(1, treeTypeTag(DETECTED_INFO_TYPE)), literal(0, treeFieldKey(DETECTED_INFO_FIELD)),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))))
        val shape = accessor()
        assertFalse("another class", isDetectedInfoAccessor(ImmutableMethod(
            "Lcom/example/Other;", shape.name, shape.parameters, shape.returnType, shape.accessFlags, null, null, shape.implementation)))
        assertFalse("static", isDetectedInfoAccessor(ImmutableMethod(
            shape.definingClass, shape.name, shape.parameters, shape.returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, shape.implementation)))
        assertFalse("private", isDetectedInfoAccessor(ImmutableMethod(
            shape.definingClass, shape.name, shape.parameters, shape.returnType,
            AccessFlags.PRIVATE.value, null, null, shape.implementation)))
    }

    /** Facebook's label: call the accessor, check for null, load the flag's key, read it. */
    private fun label(accessorName: String, key: Int) = method(
        "A01",
        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0,
            ImmutableMethodReference(GRAPHQL_STORY, accessorName, emptyList(), model)),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3),
        ImmutableInstruction21t(Opcode.IF_EQZ, 3, 4),
        literal(0, key),
        treeCall("getCachedBoolean", listOf("I"), "Z", 3, 0),
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 4),
        definingClass = GEN_AI_TRANSPARENCY_PLUGIN,
        parameters = listOf("Ljava/lang/Object;"),
        flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        returnType = "Ljava/lang/Object;",
    )

    @Test
    fun `the label has to read the detected flag through that accessor`() {
        val found = accessor("A0X")
        assertTrue(readsDetectedFlag(label("A0X", treeFieldKey(DETECTED_FLAG)), found))
        assertFalse("another flag", readsDetectedFlag(label("A0X", 0x1234), found))
        assertFalse("through another accessor", readsDetectedFlag(label("A0Y", treeFieldKey(DETECTED_FLAG)), found))
        // The key loaded but read some other way.
        val noRead = method("A01",
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0,
                ImmutableMethodReference(GRAPHQL_STORY, "A0X", emptyList(), model)),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3),
            literal(0, treeFieldKey(DETECTED_FLAG)),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 3),
            definingClass = GEN_AI_TRANSPARENCY_PLUGIN)
        assertFalse("no getCachedBoolean", readsDetectedFlag(noRead, found))
    }

    private fun classWith(type: String, methods: List<Method> = emptyList(), fields: List<ImmutableField> = emptyList()) =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, methods)

    private fun booleanReader(flags: Int, parameter: String = "I", returnType: String = "Z") = ImmutableMethod(
        BASE_MODEL_WITH_TREE, "getCachedBoolean", listOf(ImmutableMethodParameter(parameter, null, null)), returnType,
        flags, null, null, null,
    )

    /** The extension reads these two by reflection, so each has to be there, public and an instance member. */
    @Test
    fun `the members the extension reflects on have to be public instance members`() {
        val publicFinal = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        assertTrue(hasPublicBooleanReader(classWith(BASE_MODEL_WITH_TREE, listOf(booleanReader(publicFinal)))))
        assertFalse("missing", hasPublicBooleanReader(classWith(BASE_MODEL_WITH_TREE)))
        assertFalse("private", hasPublicBooleanReader(classWith(BASE_MODEL_WITH_TREE,
            listOf(booleanReader(AccessFlags.PRIVATE.value)))))
        assertFalse("static", hasPublicBooleanReader(classWith(BASE_MODEL_WITH_TREE,
            listOf(booleanReader(AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)))))
        assertFalse("takes a long", hasPublicBooleanReader(classWith(BASE_MODEL_WITH_TREE,
            listOf(booleanReader(publicFinal, parameter = "J")))))
        assertFalse("answers an int", hasPublicBooleanReader(classWith(BASE_MODEL_WITH_TREE,
            listOf(booleanReader(publicFinal, returnType = "I")))))

        fun tag(flags: Int, type: String = "I") = ImmutableField(TREE_JNI, "mTypeTag", type, flags, null, null, null)
        assertTrue(hasPublicTypeTag(classWith(TREE_JNI, fields = listOf(tag(publicFinal)))))
        assertFalse("missing", hasPublicTypeTag(classWith(TREE_JNI)))
        assertFalse("private", hasPublicTypeTag(classWith(TREE_JNI, fields = listOf(tag(AccessFlags.PRIVATE.value)))))
        assertFalse("static", hasPublicTypeTag(classWith(TREE_JNI,
            fields = listOf(tag(AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)))))
        assertFalse("a long", hasPublicTypeTag(classWith(TREE_JNI, fields = listOf(tag(publicFinal, type = "J")))))
    }

    /**
     * The patch and the extension name the same GraphQL flag and type, and the stub the patch
     * fills in is the one the extension declares. The kept classes are looked up by the reader
     * every story flag shares. Read as text, so nothing loads a patch class.
     */
    @Test
    fun `the patch and the extension read the same flag through the same stub`() {
        val java = File(RepoFiles.root,
            "extensions/facebook/src/main/java/" + GEN_AI_LABEL.removePrefix("L").removeSuffix(";") + ".java")
        assertTrue("the patch fills in a stub in ${java.path}, which isn't there", java.isFile)
        val text = java.readText()
        assertTrue("the extension reads another flag", text.contains("\"$DETECTED_FLAG\""))
        assertTrue("the extension checks another type", text.contains("\"$DETECTED_INFO_TYPE\""))
        assertTrue("the extension has no public static Object $DETECTED_INFO_STUB(Object)",
            Regex("""public static Object $DETECTED_INFO_STUB\(Object \w+\)""").containsMatchIn(text))
        val reader = File(java.parentFile, "StoryFlag.java").readText()
        for (kept in listOf(GRAPHQL_STORY, BASE_MODEL_WITH_TREE, TREE_JNI)) {
            val binary = kept.removePrefix("L").removeSuffix(";").replace('/', '.')
            assertTrue("the extension doesn't look $binary up", reader.contains("\"$binary\""))
        }
    }
}
