/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val SHORTCUT_MANAGER = "Landroid/content/pm/ShortcutManager;"

/** Every extension class sits under here, and the stand-ins make the real calls, so none is sent. */
private const val EXTENSION_ROOT = "Lapp/morphe/extension/"

/**
 * The ShortcutManager calls that add, replace, rank again or clear an app's dynamic shortcuts, by
 * name, with their parameters and answer. SettingsEntry has a static method of the same name for
 * each, taking the manager first and answering the same. Removing some of Facebook's own can only
 * move the Hushfacebook shortcut up, so `removeDynamicShortcuts` stays as it is.
 */
internal val SHORTCUT_CALLS = mapOf(
    "pushDynamicShortcut" to "(Landroid/content/pm/ShortcutInfo;)V",
    "addDynamicShortcuts" to "(Ljava/util/List;)Z",
    "setDynamicShortcuts" to "(Ljava/util/List;)Z",
    "updateShortcuts" to "(Ljava/util/List;)Z",
    "removeAllDynamicShortcuts" to "()V",
)

/** The call this instruction makes when it's one of [SHORTCUT_CALLS], or null. */
internal fun Instruction.shortcutCall(): MethodReference? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (call.definingClass != SHORTCUT_MANAGER) return null
    val shape = SHORTCUT_CALLS[call.name] ?: return null
    return call.takeIf { it.parameterTypes.joinToString("", "(", ")") + it.returnType == shape }
}

/** The SettingsEntry method that stands in for [call]: static, the manager first, the same answer. */
internal fun standIn(call: MethodReference): String =
    "$ENTRY->${call.name}($SHORTCUT_MANAGER${call.parameterTypes.joinToString("")})${call.returnType}"

/**
 * Sends each of this method's [SHORTCUT_CALLS] to SettingsEntry. Answers how many it sent.
 *
 * The stand-in reads the same registers in the same order, the manager first, so a `move-result`
 * after it stays right. A range call stays a range call, because its registers can be above v15.
 */
internal fun MutableMethod.rerouteShortcutCalls(): Int {
    val sites = (implementation ?: return 0).instructions.withIndex().mapNotNull { (index, instruction) ->
        instruction.shortcutCall()?.let { Triple(index, instruction, it) }
    }
    sites.asReversed().forEach { (index, instruction, call) ->
        val invoke = when (instruction) {
            is RegisterRangeInstruction -> {
                val last = instruction.startRegister + instruction.registerCount - 1
                "invoke-static/range { v${instruction.startRegister} .. v$last }, ${standIn(call)}"
            }
            is FiveRegisterInstruction -> {
                val registers = listOf(
                    instruction.registerC, instruction.registerD, instruction.registerE,
                    instruction.registerF, instruction.registerG,
                ).take(instruction.registerCount)
                "invoke-static { ${registers.joinToString { "v$it" }} }, ${standIn(call)}"
            }
            else -> error("$definingClass->$name: unexpected call form ${instruction.opcode}")
        }
        replaceInstruction(index, invoke)
    }
    return sites.size
}

/** True when this method makes one of [SHORTCUT_CALLS]. It only reads, so it needs no proxy. */
private fun Method.makesShortcutCall(): Boolean =
    implementation?.instructions?.any { it.shortcutCall() != null } == true

/**
 * Sends every [SHORTCUT_CALLS] call in Facebook to SettingsEntry, which puts the Hushfacebook
 * shortcut back in front after each. Answers how many calls it sent. A build that makes none has
 * nothing that could rank its own shortcuts ahead, so none isn't a failure.
 */
internal fun BytecodePatchContext.rerouteShortcutCalls(): Int {
    val callers = mutableListOf<String>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        if (classDef.methods.any { it.makesShortcutCall() }) callers += classDef.type
    }
    return callers.sumOf { type -> mutableClassDefBy(type).methods.sumOf { it.rerouteShortcutCalls() } }
}
