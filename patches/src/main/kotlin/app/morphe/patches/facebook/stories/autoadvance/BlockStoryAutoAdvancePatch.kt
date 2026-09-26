/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.autoadvance

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val AUTO_NAVIGATION = "StoryviewerAutoPlayNavigationController.moveToNextBucketOrThread"
private const val STORY_BUCKET = "Lcom/facebook/stories/model/StoryBucket;"
private const val STORY_CARD = "Lcom/facebook/stories/model/StoryCard;"
private const val WAIT_FOR_TAP = "$EXTENSION_PACKAGE/stories/StoryAdvance;->waitForTap()Z"

/** Keeps the completion progress, but leaves the move to the next story to the user's gesture. */
@Suppress("unused")
val blockStoryAutoAdvancePatch = bytecodePatch(
    name = "Stop Story auto-advance",
    description = "Keeps each Story on screen until you tap or swipe. Turn the switch off for Facebook's timing.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val hooks = classDefByStrings(AUTO_NAVIGATION, StringComparisonType.EQUALS)
            .mapNotNull(::autoAdvanceHook)
        val hook = hooks.singleOrNull() ?: throw PatchException(
            "Expected one Story progress callback with a completion navigation call, found ${hooks.size}",
        )
        val (callback, callIndex) = hook
        val mutable = mutableClassDefBy(callback.definingClass).methods.single {
            it.name == callback.name && it.parameterTypes == callback.parameterTypes
        }
        mutable.requireLocals("Stop Story auto-advance", 1)
        mutable.addInstructionsWithLabels(
            callIndex,
            """
                invoke-static { }, $WAIT_FOR_TAP
                move-result v0
                if-eqz v0, :navigate
                return-void
            """,
            ExternalLabel("navigate", mutable.getInstruction(callIndex)),
        )
        enableStatus("storyAutoAdvance")
    }
}

/** The only callback that receives Story progress and invokes this controller's auto navigation. */
internal fun autoAdvanceHook(owner: ClassDef): Pair<Method, Int>? {
    val navigators = owner.methods.filter { holdsString(it, AUTO_NAVIGATION) && it.returnType == "V" }
    if (navigators.size != 1) return null
    val navigator = navigators.single()
    val callbacks = owner.methods.mapNotNull { method ->
        if (method.returnType != "V" ||
            method.parameterTypes.map { it.toString() } != listOf(STORY_BUCKET, STORY_CARD, "I")
        ) return@mapNotNull null
        val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val completions = instructions.withIndex().filter { (_, instruction) ->
            (instruction as? NarrowLiteralInstruction)?.narrowLiteral == 1000
        }
        val calls = instructions.withIndex().filter { (_, instruction) ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == owner.type && ref.name == navigator.name &&
                ref.parameterTypes.map { it.toString() } == navigator.parameterTypes.map { it.toString() } &&
                ref.returnType == navigator.returnType
        }
        if (completions.size != 1 || calls.size != 1 ||
            calls.single().index <= completions.single().index ||
            calls.single().value.opcode !in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_DIRECT)
        ) return@mapNotNull null
        method to calls.single().index
    }
    return callbacks.singleOrNull()
}
