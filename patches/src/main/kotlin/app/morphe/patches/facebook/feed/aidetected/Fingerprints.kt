/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.aidetected

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.security.MessageDigest

/** Kept name. The feed's story model, and the unit the GenAI rule reads. */
internal const val GRAPHQL_STORY = "Lcom/facebook/graphql/model/GraphQLStory;"

/** Kept name. Every tree-backed GraphQL model, with the cached field readers. */
internal const val BASE_MODEL_WITH_TREE = "Lcom/facebook/graphql/modelutil/BaseModelWithTree;"

/** Kept name. The JNI tree under every model, which holds the model's GraphQL type tag. */
internal const val TREE_JNI = "Lcom/facebook/graphservice/tree/TreeJNI;"

/**
 * Kept name. The plugin that draws Facebook's own AI label in a feed post's header. The rule reads
 * the flag this plugin reads, from the model it reads it on.
 */
internal const val GEN_AI_TRANSPARENCY_PLUGIN =
    "Lcom/facebook/feed/plugins/header/subtitle/impl/genaitransparency/GenAiTransparencyPlugin;"

/**
 * The GraphQL names behind the rule. Facebook's tree models look a field up by the
 * `String.hashCode()` of its name (`name` is 3373707, `label` 102727412, in the same plugin) and
 * build a model for a type named by the first four bytes of the MD5 of the type's name. Both
 * survive Redex, because the schema, not the obfuscator, picks them.
 *
 * Read from 573, 577 and 580 (2026-09-25): GraphQLStory has one zero-argument method that asks
 * `getCachedModel` for the field keyed `ai_generated_detected_info` (0xb4f9e684) as the type tagged
 * `XFBAIGeneratedDetectedInfo` (0x70da9d19). It is `A0W()` returning `LX/41R;` in 573,
 * `A0W()` returning `LX/3zX;` in 577 and `A0X()` returning `LX/3zi;` in 580, where 577's `A0X()`
 * is the neighbouring `ai_generated_self_disclosure_info` accessor. GenAiTransparencyPlugin.A01
 * calls it and reads the boolean keyed `was_detected_as_ai_generated` (0x723ea5fe) with
 * `getCachedBoolean` in all three. FroggoMorphePatches' 573 filter pointed there first; its names
 * don't carry over, which is why nothing here writes one down.
 */
internal const val DETECTED_INFO_FIELD = "ai_generated_detected_info"
internal const val DETECTED_INFO_TYPE = "XFBAIGeneratedDetectedInfo"
internal const val DETECTED_FLAG = "was_detected_as_ai_generated"

/** How Facebook's tree models key a field: the name's String.hashCode(). */
internal fun treeFieldKey(name: String): Int = name.hashCode()

/** How Facebook's tree models tag a GraphQL type: the first four bytes of the name's MD5, big-endian. */
internal fun treeTypeTag(name: String): Int {
    val digest = MessageDigest.getInstance("MD5").digest(name.toByteArray(Charsets.UTF_8))
    return ((digest[0].toInt() and 0xff) shl 24) or ((digest[1].toInt() and 0xff) shl 16) or
        ((digest[2].toInt() and 0xff) shl 8) or (digest[3].toInt() and 0xff)
}

private val DETECTED_INFO_KEY = treeFieldKey(DETECTED_INFO_FIELD)
private val DETECTED_INFO_TAG = treeTypeTag(DETECTED_INFO_TYPE)
private val DETECTED_FLAG_KEY = treeFieldKey(DETECTED_FLAG)

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.literal(): Int? = (this as? NarrowLiteralInstruction)?.narrowLiteral

private fun MethodReference.isTreeCall(name: String, parameters: List<String>): Boolean =
    definingClass == BASE_MODEL_WITH_TREE && this.name == name &&
        parameterTypes.map { it.toString() } == parameters

/**
 * Whether [method] is GraphQLStory's accessor of the detected-AI info: public, no arguments, and a
 * body that asks `getCachedModel` for [DETECTED_INFO_FIELD] as [DETECTED_INFO_TYPE]. The two keys
 * together pick it: the self-disclosure accessor beside it loads other ones.
 */
internal fun isDetectedInfoAccessor(method: Method): Boolean {
    if (method.definingClass != GRAPHQL_STORY || method.parameterTypes.isNotEmpty()) return false
    if (!AccessFlags.PUBLIC.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags)) return false
    if (method.returnType.length < 3 || !method.returnType.startsWith("L")) return false
    val body = method.body()
    return body.any { it.literal() == DETECTED_INFO_KEY } &&
        body.any { it.literal() == DETECTED_INFO_TAG } &&
        body.any { it.methodReference()?.isTreeCall("getCachedModel", listOf("I", "Ljava/lang/Class;", "I")) == true }
}

/** The one detected-info accessor [story] declares, or every candidate when there isn't exactly one. */
internal fun detectedInfoAccessors(story: ClassDef): List<Method> = story.methods.filter(::isDetectedInfoAccessor)

/**
 * Whether [method] calls [accessor] and then, within a few instructions, loads the key of
 * [DETECTED_FLAG] and reads it with `getCachedBoolean`. That is how GenAiTransparencyPlugin
 * decides a post carries Facebook's detected-AI label.
 */
internal fun readsDetectedFlag(method: Method, accessor: Method): Boolean {
    val body = method.body()
    return body.indices.any { index ->
        val call = body[index].methodReference() ?: return@any false
        if (call.definingClass != GRAPHQL_STORY || call.name != accessor.name ||
            call.returnType != accessor.returnType || call.parameterTypes.isNotEmpty()
        ) return@any false
        val after = body.subList(index + 1, minOf(body.size, index + 7))
        val key = after.indexOfFirst { it.literal() == DETECTED_FLAG_KEY }
        key >= 0 && after.drop(key + 1).any { it.methodReference()?.isTreeCall("getCachedBoolean", listOf("I")) == true }
    }
}

/** Whether [treeModel] has the public `getCachedBoolean(int)` the extension reads the flag with. */
internal fun hasPublicBooleanReader(treeModel: ClassDef): Boolean = treeModel.methods.any {
    it.name == "getCachedBoolean" && it.returnType == "Z" && it.parameterTypes.map { p -> p.toString() } == listOf("I") &&
        AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
}

/** Whether [tree] has the public `mTypeTag` field the extension checks a model's type with. */
internal fun hasPublicTypeTag(tree: ClassDef): Boolean = tree.fields.any {
    it.name == "mTypeTag" && it.type == "I" &&
        AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
}
