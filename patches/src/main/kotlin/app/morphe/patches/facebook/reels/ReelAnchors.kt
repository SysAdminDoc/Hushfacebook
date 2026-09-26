/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.shared.redexOriginalName
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Where Clean up Reels hooks, found by kept names only (read from 577 and 580, 2026-09-25). The
 * obfuscated names in these comments are for reviewers; the code never writes one down.
 *
 * - The chips under a reel: one static method takes the reel's attribution models as an
 *   ImmutableList, switches on each one's getTypeName() over the XFBFBShorts*Attribution literals,
 *   and returns the ones it will draw from an ImmutableList$Builder (580 LX/AyX;->A00, 577
 *   LX/B1d;->A00). Both builds end it in one `return-object` after `build()`.
 * - The Follow button: the bug report dumper (the method holding "WatchFeedData.txt") writes each
 *   Reels viewer config value beside its name, and the value beside the name ending
 *   "removeFollowingButton" comes from a no-argument boolean getter (580 LX/4Xa;->A1c, written
 *   twice, under getPlayerTabGrowthConfig and getUDDConfig; 577 LX/52v;->A1b, after a split
 *   ".removeFollowingButton"). Its only other readers are the reel author row's two renderers.
 * - The footer previews: two runnables whose Redex names are kept, each of which only starts one
 *   query and attaches its callback (580 LX/Aw3 and LX/XZu, 577 LX/B8E and LX/h8J).
 */

internal const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"
private const val IMMUTABLE_LIST_BUILDER = "Lcom/google/common/collect/ImmutableList\$Builder;"
private const val OBJECT_ARRAY = "[Ljava/lang/Object;"

/** How the chip filter's answer becomes a list again: Facebook's own Guava, by name. */
internal const val COPY_OF = "$IMMUTABLE_LIST->copyOf($OBJECT_ARRAY)$IMMUTABLE_LIST"

/**
 * The chip types the extension's `ReelDeclutter.HIDDEN_CHIPS` hides. The builder has to name every
 * one, so a build that renamed a chip stops the patch instead of leaving that chip in quietly.
 * ReelAnchorsFixtureTest holds the two lists together.
 */
internal val HIDDEN_CHIPS = listOf(
    "XFBFBShortsRemixAttribution",
    "XFBFBShortsTemplateAttribution",
    "XFBFBShortsAddYoursStickerAttribution",
    "XFBFBShortsEditsAppAttribution",
    "XFBFBShortsSendStarsAttribution",
    "XFBFBShortsInstantGamesAttribution",
    "XFBFBShortsPartnerAppAttribution",
    "XFBFBShortsExternalLinkAttribution",
)

/** The chip the patch looks the builder up by. Two dozen methods hold it; one builds the list. */
internal const val CHIP_ANCHOR = "XFBFBShortsRemixAttribution"

internal const val WATCH_FEED_DUMP = "WatchFeedData.txt"
internal const val REMOVE_FOLLOWING_BUTTON = "removeFollowingButton"

/** A literal only the reel author row's main renderer holds. */
internal const val AUTHOR_COMPONENT = "FbShortsViewerVideoAuthorComponentSpec:placeholder_only_for_blank_pfp"

/** The author row's second renderer: a Litho component's `render`, a name the framework keeps. */
internal const val RENDER = "render"

internal const val HOT_COMMENT_RUNNABLE = "FbShortsViewerFooterHotCommentHelper\$createHotCommentQueryRunnable\$1"
internal const val SOCIAL_BUBBLES_RUNNABLE =
    "FbShortsViewerFooterSocialBubblesHelper\$setupSocialBubblesQuery\$socialBubblesQueryRunnable\$1"

/** The query the hot comment runnable starts, the plain one beside its XAR variant. */
internal const val INLINE_COMMENTS_QUERY = "FBShortsInlineCommentsQuery"

/** The one literal the social bubbles runnable loads: the reel it asks about. */
internal const val VIDEO_ID = "video_id"

private const val RUNNABLE = "Ljava/lang/Runnable;"

private val Instruction.methodReference
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private val Instruction.string
    get() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun MethodReference.sameAs(other: MethodReference) =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

/** The strings [method] loads. */
internal fun stringsOf(method: Method): Set<String> = method.body().mapNotNull { it.string }.toSet()

/** Whether [method] calls [callee] anywhere in its body. */
internal fun calls(method: Method, callee: MethodReference): Boolean =
    method.body().any { it.methodReference?.sameAs(callee) == true }

/**
 * Whether [method] is the chip list builder: static, an ImmutableList in and out, every hidden chip
 * named, each item read by `getTypeName()`, and the answer built by `ImmutableList$Builder.build()`.
 */
internal fun isChipListBuilder(method: Method): Boolean {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != IMMUTABLE_LIST) return false
    if (method.parameterTypes.none { it.toString() == IMMUTABLE_LIST }) return false
    val body = method.body()
    if (body.isEmpty() || !stringsOf(method).containsAll(HIDDEN_CHIPS)) return false
    val calls = body.mapNotNull { it.methodReference }
    return calls.any { it.definingClass == IMMUTABLE_LIST_BUILDER && it.name == "build" && it.returnType == IMMUTABLE_LIST } &&
        calls.any { it.name == "getTypeName" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" }
}

/** The chip list builders [classDef] declares. */
internal fun chipListBuilders(classDef: ClassDef): List<Method> = classDef.methods.filter(::isChipListBuilder)

/** Whether [immutableList] has the public static `copyOf(Object[])` the chip hook rebuilds with. */
internal fun hasCopyOfArray(immutableList: ClassDef): Boolean = immutableList.methods.any {
    it.name == "copyOf" && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.returnType == IMMUTABLE_LIST && it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT_ARRAY)
}

/** Every register an instruction's call passes. */
private fun invokeRegisters(instruction: Instruction): List<Int> = when (instruction) {
    is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
    is FiveRegisterInstruction -> listOf(
        instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
    ).take(instruction.registerCount)
    else -> emptyList()
}

private fun isInvoke(instruction: Instruction) = instruction.opcode.name.startsWith("invoke")

/** Whether [instruction] leaves something new in [register]. */
private fun writes(instruction: Instruction, register: Int): Boolean {
    if (!instruction.opcode.setsRegister()) return false
    val target = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return target == register || (instruction.opcode.setsWideRegister() && target + 1 == register)
}

private val MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/** How far the trace walks from a literal to its write, and back from the write to a value. */
private const val WINDOW = 12

/**
 * The boolean getter whose answer the dump writes beside the name at [literal], or null.
 *
 * <p>The dump writes each value with a call that takes the name, or a name built from it (577 joins
 * a prefix to ".removeFollowingButton" first). That call is followed forward from the literal. Its
 * arguments are then followed back to a no-argument boolean getter, through `String.valueOf(Z)` (580
 * turns the answer into text first) and plain moves. Nearness alone picks wrong: on 580 the getter of
 * the next name sits closer to the "getUDDConfig" literal than its own does.
 */
internal fun followGetterAt(body: List<Instruction>, literal: Int): MethodReference? {
    val key = mutableSetOf((body[literal] as? OneRegisterInstruction)?.registerA ?: return null)
    var write = -1
    var index = literal + 1
    val end = minOf(body.size, literal + WINDOW)
    while (index < end) {
        val instruction = body[index]
        if (isInvoke(instruction) && invokeRegisters(instruction).any(key::contains)) {
            val call = instruction.methodReference ?: return null
            if (call.returnType == "V") {
                write = index
                break
            }
            // A name built from the literal: the call's answer carries the key on.
            val result = body.getOrNull(index + 1)
            if (result?.opcode == Opcode.MOVE_RESULT_OBJECT) {
                key += (result as OneRegisterInstruction).registerA
                index += 2
                continue
            }
        } else if (instruction.opcode in OBJECT_MOVES && (instruction as TwoRegisterInstruction).registerB in key) {
            key += (instruction as TwoRegisterInstruction).registerA
        } else {
            key.removeAll { writes(instruction, it) }
            if (key.isEmpty()) return null
        }
        index++
    }
    if (write < 0) return null
    val getters = invokeRegisters(body[write]).filterNot(key::contains)
        .mapNotNull { getterFeeding(body, write, it, depth = 0) }
    return getters.distinctBy { "${it.definingClass}->${it.name}" }.singleOrNull()
}

/** The boolean getter whose answer reaches [register] just before [before], or null. */
private fun getterFeeding(body: List<Instruction>, before: Int, register: Int, depth: Int): MethodReference? {
    if (depth > 3) return null
    for (index in before - 1 downTo maxOf(0, before - WINDOW)) {
        val instruction = body[index]
        if (!writes(instruction, register)) continue
        val call = body.getOrNull(index - 1)?.takeIf(::isInvoke)
        return when (instruction.opcode) {
            Opcode.MOVE_RESULT -> call?.takeIf(::isBooleanGetterCall)?.methodReference
            Opcode.MOVE_RESULT_OBJECT -> {
                val invoke = call ?: return null
                val text = invoke.methodReference ?: return null
                val valueOfBoolean = text.definingClass == "Ljava/lang/String;" && text.name == "valueOf" &&
                    text.parameterTypes.map(CharSequence::toString) == listOf("Z")
                if (valueOfBoolean) getterFeeding(body, index - 1, invokeRegisters(invoke).single(), depth + 1) else null
            }
            in MOVES -> getterFeeding(body, index, (instruction as TwoRegisterInstruction).registerB, depth + 1)
            else -> null
        }
    }
    return null
}

/** An instance call to a method that takes nothing and answers a boolean. */
private fun isBooleanGetterCall(instruction: Instruction): Boolean {
    val call = instruction.methodReference ?: return false
    return (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
        call.returnType == "Z" && call.parameterTypes.isEmpty() && invokeRegisters(instruction).size == 1
}

/** What the dump says about the Follow button: the getter, or why none was found. */
internal class FollowGetter(val getter: MethodReference?, val literals: Int, val problem: String?)

/**
 * The getter every "…removeFollowingButton" value in [dumper] comes from. Each literal has to trace
 * to one getter, and all of them to the same one.
 */
internal fun followButtonGetter(dumper: Method): FollowGetter {
    val body = dumper.body()
    val literals = body.indices.filter { body[it].string?.endsWith(REMOVE_FOLLOWING_BUTTON) == true }
    if (literals.isEmpty()) return FollowGetter(null, 0, "the dump names no \"$REMOVE_FOLLOWING_BUTTON\" value")
    val getters = literals.map { followGetterAt(body, it) }
    if (getters.any { it == null }) {
        return FollowGetter(null, literals.size,
            "${getters.count { it == null }} of ${literals.size} \"$REMOVE_FOLLOWING_BUTTON\" values trace to no boolean getter")
    }
    val distinct = getters.filterNotNull().distinctBy { "${it.definingClass}->${it.name}" }
    if (distinct.size != 1) {
        return FollowGetter(null, literals.size,
            "the \"$REMOVE_FOLLOWING_BUTTON\" values come from ${distinct.size} getters: ${distinct.joinToString()}")
    }
    return FollowGetter(distinct.single(), literals.size, null)
}

/**
 * Why [readers] aren't the Follow getter's known readers, or null when they are: the dump, the
 * author row's renderer holding [AUTHOR_COMPONENT], and one other `render`. A new reader could draw
 * the button somewhere this hasn't been checked, so it stops the patch.
 */
internal fun followReaderProblem(readers: List<Method>, dumper: Method): String? {
    fun describe() = readers.joinToString { "${it.definingClass}->${it.name}" }
    if (readers.size != 3) return "the Follow getter has ${readers.size} readers, expected 3: ${describe()}"
    val others = readers.filterNot { it.definingClass == dumper.definingClass && it.name == dumper.name }
    if (others.size != 2) return "the dump isn't among the Follow getter's readers: ${describe()}"
    val author = others.filter { holdsString(it, AUTHOR_COMPONENT) }
    if (author.size != 1) return "${author.size} of the Follow getter's readers hold \"$AUTHOR_COMPONENT\": ${describe()}"
    val second = others.single { it !== author.single() }
    if (second.name != RENDER) return "the Follow getter's last reader isn't a render method: ${describe()}"
    return null
}

/**
 * The `run()` of [classDef] when Redex left it the name [redexName], it's a Runnable, and its run
 * loads [holding]; otherwise null.
 */
internal fun footerRunnable(classDef: ClassDef, redexName: String, holding: String): Method? {
    if (redexOriginalName(classDef) != redexName) return null
    if (classDef.interfaces.none { it.toString() == RUNNABLE }) return null
    return classDef.methods.singleOrNull {
        it.name == "run" && it.returnType == "V" && it.parameterTypes.isEmpty() && it.implementation != null &&
            holdsString(it, holding)
    }
}
