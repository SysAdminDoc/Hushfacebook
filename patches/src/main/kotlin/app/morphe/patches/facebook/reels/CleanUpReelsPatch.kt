/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val DECLUTTER = "$EXTENSION_PACKAGE/reels/ReelDeclutter;"
internal const val FILTER_CHIPS = "$DECLUTTER->filterChips(Ljava/lang/Object;)[Ljava/lang/Object;"
internal const val HIDE_FOLLOW_BUTTON = "$DECLUTTER->hideFollowButton()Z"
internal const val HIDE_FOLLOWING_BUTTON = "$DECLUTTER->hideFollowingButton()Z"
internal const val SKIP_HOT_COMMENT = "$DECLUTTER->skipHotComment()Z"
internal const val SKIP_SOCIAL_BUBBLES = "$DECLUTTER->skipSocialBubbles()Z"

private const val PATCH = "Clean up Reels"

/**
 * Takes three things off the Reels viewer, each behind its own switch, all on once the patch is in:
 * the chips under a reel that prompt you to make something or promote something, the Follow button
 * in the author row (with the Following button an author you already follow gets there), and the
 * comment and friends' reaction previews in the footer.
 *
 * Every anchor is a kept name or literal (see ReelAnchors.kt), and every one of them is required: a
 * build where one can't be found stops the patch with what's missing, rather than shipping a switch
 * that quietly does nothing. The hooks ask the extension, which answers Facebook's own path until
 * the settings are ready, while paused, and whenever it fails.
 *
 * Off by default: nobody has seen it on a signed-in Reels feed yet.
 */
@Suppress("unused")
val cleanUpReelsPatch = bytecodePatch(
    name = "Clean up Reels",
    description = "Hides the Follow button on reels and the comment and reaction previews under them. " +
        "Buttons such as Remix, Use template, Add yours and Stars go too, and each part has its own switch.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        filterChips()
        hideFollowButtons()
        skipFooterQueries()
        enableStatus("reelDeclutter")
    }
}

/** The chip list builder gets the extension's filter before every return. */
private fun BytecodePatchContext.filterChips() {
    val immutableList = classDefByOrNull(IMMUTABLE_LIST)
        ?: throw PatchException("$PATCH: this Facebook build has no $IMMUTABLE_LIST")
    if (!hasCopyOfArray(immutableList)) {
        throw PatchException("$PATCH: ImmutableList has no public static copyOf(Object[]) to rebuild the chip list with")
    }
    val builders = classDefByStrings(CHIP_ANCHOR, StringComparisonType.EQUALS)
        .flatMap { owner -> chipListBuilders(owner).map { owner to it } }
    val (owner, builder) = builders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one ImmutableList builder naming every hidden chip (${HIDDEN_CHIPS.joinToString()}), " +
            "found ${builders.size}",
    )
    mutableClassDefBy(owner).methods.first { it.sameSignatureAs(builder) }.filterChipsBeforeEveryReturn()
}

/**
 * Sends the list at every `return-object` through the extension's chip filter. A null answer keeps
 * Facebook's list, and an array is rebuilt into an ImmutableList with Facebook's own copyOf. Each
 * hook goes in at the return's own control-flow label, so a branch that jumped to the return runs
 * it too. At a return nothing else is live, so one local other than the returned one holds the
 * answer. The branch lands on a nop of the hook's own, right before the return.
 */
internal fun MutableMethod.filterChipsBeforeEveryReturn() {
    val implementation = implementation ?: throw PatchException("$PATCH: $definingClass->$name has no body")
    val returns = implementation.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) throw PatchException("$PATCH: $definingClass->$name returns no chip list")
    val locals = localRegisterCount()

    returns.asReversed().forEach { (index, list) ->
        val answer = (0 until locals).firstOrNull { it != list }
            ?: throw PatchException("$PATCH: $definingClass->$name has no local besides v$list to hold the filter's answer")
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$list .. v$list }, $FILTER_CHIPS
                move-result-object v$answer
                if-eqz v$answer, :facebooks_chips
                invoke-static/range { v$answer .. v$answer }, $COPY_OF
                move-result-object v$list
                :facebooks_chips
                nop
            """,
        )
    }
}

/**
 * The author row's two buttons for following, both behind the Follow switch. Facebook's Follow
 * check answers no when the switch is on, so no Follow button is built for an author you don't
 * follow, and its config getter for removing the Following button answers true, so an author you
 * already follow gets none either. See ReelAnchors.kt for why one hook alone missed the first.
 */
private fun BytecodePatchContext.hideFollowButtons() {
    val dumpers = classDefByStrings(WATCH_FEED_DUMP, StringComparisonType.EQUALS)
        .flatMap { methodsHolding(it, WATCH_FEED_DUMP) }
    val dumper = dumpers.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method holding \"$WATCH_FEED_DUMP\", found ${dumpers.size}")
    val found = followButtonGetter(dumper)
    val getter = found.getter ?: throw PatchException("$PATCH: ${found.problem}")

    val readers = mutableListOf<Method>()
    classDefForEach { classDef -> classDef.methods.filterTo(readers) { calls(it, getter) } }
    followReaderProblem(readers, dumper)?.let { throw PatchException("$PATCH: $it") }

    val author = authorRow(readers, dumper)
        ?: throw PatchException("$PATCH: no single reader of the Follow getter holds \"$AUTHOR_COMPONENT\"")
    val asked = followCheck(author) { call -> classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) } }
    val check = asked.check ?: throw PatchException("$PATCH: ${asked.problem}")

    val method = mutableClassDefByOrNull(getter.definingClass)?.methods?.singleOrNull {
        it.name == getter.name && it.returnType == "Z" && it.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } ?: throw PatchException("$PATCH: ${getter.definingClass} declares no ${getter.name}()Z with a body")
    method.returnTrueWhen(HIDE_FOLLOWING_BUTTON)
    mutableClassDefBy(check.definingClass).methods.first { it.sameSignatureAs(check) }.returnFalseWhen(HIDE_FOLLOW_BUTTON)
}

/** Asks [hook] first thing and answers true when it says so. */
internal fun MutableMethod.returnTrueWhen(hook: String) = answerWhen(hook, true)

/** Asks [hook] first thing and answers false when it says so. */
internal fun MutableMethod.returnFalseWhen(hook: String) = answerWhen(hook, false)

/**
 * Asks [hook] first thing and answers [answer] when it says so. At the first instruction no local
 * holds anything yet, so v0 is free, and `const/4` and `return` both reach it.
 */
private fun MutableMethod.answerWhen(hook: String, answer: Boolean) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_answer
            const/4 v0, ${if (answer) "0x1" else "0x0"}
            return v0
        """,
        ExternalLabel("facebooks_answer", getInstruction(0)),
    )
}

/** The two footer runnables return before they query when the switch is on. */
private fun BytecodePatchContext.skipFooterQueries() {
    listOf(
        Triple(HOT_COMMENT_RUNNABLE, INLINE_COMMENTS_QUERY, SKIP_HOT_COMMENT),
        Triple(SOCIAL_BUBBLES_RUNNABLE, VIDEO_ID, SKIP_SOCIAL_BUBBLES),
    ).forEach { (redexName, holding, hook) ->
        val runs = classDefByStrings(holding, StringComparisonType.EQUALS)
            .mapNotNull { owner -> footerRunnable(owner, redexName, holding)?.let { owner to it } }
        val (owner, run) = runs.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one Runnable named $redexName whose run() holds \"$holding\", found ${runs.size}",
        )
        mutableClassDefBy(owner).methods.first { it.sameSignatureAs(run) }.returnVoidWhen(hook)
    }
}

/**
 * Asks [hook] first thing and returns when it says so. The runnable only starts its query and
 * attaches the callback that draws the answer, so returning leaves nothing half done.
 */
internal fun MutableMethod.returnVoidWhen(hook: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_query
            return-void
        """,
        ExternalLabel("facebooks_query", getInstruction(0)),
    )
}

private fun MutableMethod.sameSignatureAs(other: Method) =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }
