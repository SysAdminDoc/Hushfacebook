/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.security.MessageDigest

/** Kept name. The feed's story model, whose flags the story rules read. */
internal const val GRAPHQL_STORY = "Lcom/facebook/graphql/model/GraphQLStory;"

/** Kept name. Every tree-backed GraphQL model, with the cached field readers. */
internal const val BASE_MODEL_WITH_TREE = "Lcom/facebook/graphql/modelutil/BaseModelWithTree;"

/** Kept name. The JNI tree under every model, which holds the model's GraphQL type tag. */
internal const val TREE_JNI = "Lcom/facebook/graphservice/tree/TreeJNI;"

/**
 * How Facebook's tree models key a field: the `String.hashCode()` of its GraphQL name. The same
 * plugin that reads the GenAI flag loads 3373707 for `name` and 102727412 for `label`.
 */
internal fun treeFieldKey(name: String): Int = name.hashCode()

/**
 * How Facebook's tree models tag a GraphQL type: the first four bytes of the MD5 of its name,
 * big-endian. Both schemes survive Redex, because the schema, not the obfuscator, picks them.
 */
internal fun treeTypeTag(name: String): Int {
    val digest = MessageDigest.getInstance("MD5").digest(name.toByteArray(Charsets.UTF_8))
    return ((digest[0].toInt() and 0xff) shl 24) or ((digest[1].toInt() and 0xff) shl 16) or
        ((digest[2].toInt() and 0xff) shl 8) or (digest[3].toInt() and 0xff)
}

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.literal(): Int? = (this as? NarrowLiteralInstruction)?.narrowLiteral

private fun MethodReference.isTreeCall(name: String, parameters: List<String>): Boolean =
    definingClass == BASE_MODEL_WITH_TREE && this.name == name &&
        parameterTypes.map { it.toString() } == parameters

/**
 * Whether [method] is GraphQLStory's accessor of the model held under [field], of GraphQL type
 * [type]: public, no arguments, returning an object, and a body that asks `getCachedModel` for
 * that field key as that type tag. The two keys together pick it: the accessors beside it load
 * other ones.
 */
internal fun isStoryModelAccessor(method: Method, field: String, type: String): Boolean {
    if (method.definingClass != GRAPHQL_STORY || method.parameterTypes.isNotEmpty()) return false
    if (!AccessFlags.PUBLIC.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags)) return false
    if (method.returnType.length < 3 || !method.returnType.startsWith("L")) return false
    val body = method.body()
    return body.any { it.literal() == treeFieldKey(field) } &&
        body.any { it.literal() == treeTypeTag(type) } &&
        body.any { it.methodReference()?.isTreeCall("getCachedModel", listOf("I", "Ljava/lang/Class;", "I")) == true }
}

/** Every accessor [story] declares of the model under [field] as [type]. A patch wants exactly one. */
internal fun storyModelAccessors(story: ClassDef, field: String, type: String): List<Method> =
    story.methods.filter { isStoryModelAccessor(it, field, type) }

/**
 * Whether [method] calls [accessor] and then, within a few instructions, loads the key of [flag]
 * and reads it with `getCachedBoolean`. That is how Facebook's own code reads a story flag.
 */
internal fun readsStoryFlag(method: Method, accessor: Method, flag: String): Boolean {
    val key = treeFieldKey(flag)
    val body = method.body()
    return body.indices.any { index ->
        val call = body[index].methodReference() ?: return@any false
        if (call.definingClass != GRAPHQL_STORY || call.name != accessor.name ||
            call.returnType != accessor.returnType || call.parameterTypes.isNotEmpty()
        ) return@any false
        val after = body.subList(index + 1, minOf(body.size, index + 7))
        val loaded = after.indexOfFirst { it.literal() == key }
        loaded >= 0 && after.drop(loaded + 1).any { it.methodReference()?.isTreeCall("getCachedBoolean", listOf("I")) == true }
    }
}

/** Whether [treeModel] has the public `getCachedBoolean(int)` the extension reads a flag with. */
internal fun hasPublicBooleanReader(treeModel: ClassDef): Boolean = treeModel.methods.any {
    it.name == "getCachedBoolean" && it.returnType == "Z" && it.parameterTypes.map { p -> p.toString() } == listOf("I") &&
        AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
}

/** Whether [tree] has the public `mTypeTag` field the extension checks a model's type with. */
internal fun hasPublicTypeTag(tree: ClassDef): Boolean = tree.fields.any {
    it.name == "mTypeTag" && it.type == "I" &&
        AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
}

/** Stops the patch unless the members every story flag is read through are there and public. */
internal fun BytecodePatchContext.requireStoryFlagReaders() {
    if (!hasPublicBooleanReader(classDefBy(BASE_MODEL_WITH_TREE))) {
        throw PatchException("BaseModelWithTree has no public getCachedBoolean(int)")
    }
    if (!hasPublicTypeTag(classDefBy(TREE_JNI))) {
        throw PatchException("TreeJNI has no public int mTypeTag")
    }
}

/**
 * Fills in the extension's `public static Object [stubName](Object)` on [stubClass] with a call to
 * [accessor], a public instance method with no arguments that answers an object. The argument is
 * cast to the accessor's own class: GraphQLStory for the story flags, and the renamed class that
 * answers the type name for a unit's accessor. Only the parameter register is used, so the stub's
 * own register count doesn't matter; the extension checks what the unit is before it calls the stub.
 */
internal fun BytecodePatchContext.fillStoryModelStub(stubClass: String, stubName: String, accessor: Method) {
    if (accessor.parameterTypes.isNotEmpty() || AccessFlags.STATIC.isSet(accessor.accessFlags) ||
        !accessor.returnType.startsWith("L")
    ) {
        throw PatchException("${accessor.definingClass}->${accessor.name} isn't an accessor $stubName can call")
    }
    val stub = mutableClassDefBy(stubClass).methods.singleOrNull {
        it.name == stubName && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.returnType == "Ljava/lang/Object;" &&
            it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("$stubClass has no static Object $stubName(Object)")

    val host = accessor.definingClass
    stub.addInstructions(
        0,
        """
            check-cast p0, $host
            invoke-virtual { p0 }, $host->${accessor.name}()${accessor.returnType}
            move-result-object p0
            return-object p0
        """,
    )
}
