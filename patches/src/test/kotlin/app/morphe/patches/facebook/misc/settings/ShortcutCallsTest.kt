/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.Fixtures
import app.morphe.RepoFiles
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Facebook's shortcut calls go through SettingsEntry, which puts the Hushfacebook shortcut back in
 * front after each one. Facebook pushes its own at rank 0, the newest push goes first, and the
 * Hushfacebook shortcut ended up last, where a launcher showing a few cut it off (#2).
 */
class ShortcutCallsTest {
    private fun call(name: String): ImmutableMethodReference {
        val shape = SHORTCUT_CALLS[name] ?: "(Ljava/util/List;)V"
        val parameters = Regex("""L[^;]+;""").findAll(shape.substringBefore(')')).map { it.value }.toList()
        return ImmutableMethodReference(SHORTCUT_MANAGER, name, parameters, shape.substringAfter(')'))
    }

    private fun method(registers: Int, vararg instructions: Instruction): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lcom/example/Shortcuts;",
            "publish",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(registers, instructions.toList(), null, null),
        ),
    )

    @Test
    fun aCallGoesToTheStandInWithTheSameRegisters() {
        val method = method(
            3,
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, call("pushDynamicShortcut")),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 0, 0, 0, 0, call("updateShortcuts")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        assertEquals(2, method.rerouteShortcutCalls())

        val (push, update, result) = method.implementation!!.instructions.take(3)
        assertEquals(Opcode.INVOKE_STATIC, push.opcode)
        assertEquals(
            "Lapp/morphe/extension/facebook/settings/SettingsEntry;->pushDynamicShortcut(" +
                "Landroid/content/pm/ShortcutManager;Landroid/content/pm/ShortcutInfo;)V",
            (push as ReferenceInstruction).reference.toString(),
        )
        assertEquals(listOf(1, 2), (push as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
        assertEquals(
            "Lapp/morphe/extension/facebook/settings/SettingsEntry;->updateShortcuts(" +
                "Landroid/content/pm/ShortcutManager;Ljava/util/List;)Z",
            (update as ReferenceInstruction).reference.toString(),
        )
        assertEquals(listOf(1, 0), (update as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
        assertEquals("the answer is read where it was", Opcode.MOVE_RESULT, result.opcode)
        assertEquals(0, (result as OneRegisterInstruction).registerA)
    }

    @Test
    fun aRangeCallStaysARangeCall() {
        val method = method(
            20,
            ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 17, 2, call("setDynamicShortcuts")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 19, 1, call("removeAllDynamicShortcuts")),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        assertEquals(2, method.rerouteShortcutCalls())

        val instructions = method.implementation!!.instructions.toList()
        val set = instructions[0] as RegisterRangeInstruction
        assertEquals(Opcode.INVOKE_STATIC_RANGE, instructions[0].opcode)
        assertEquals(17 to 2, set.startRegister to set.registerCount)
        val clear = instructions[2] as RegisterRangeInstruction
        assertEquals(Opcode.INVOKE_STATIC_RANGE, instructions[2].opcode)
        assertEquals(19 to 1, clear.startRegister to clear.registerCount)
        assertEquals(
            "Lapp/morphe/extension/facebook/settings/SettingsEntry;->removeAllDynamicShortcuts(" +
                "Landroid/content/pm/ShortcutManager;)V",
            (instructions[2] as ReferenceInstruction).reference.toString(),
        )
    }

    @Test
    fun callsThatCanOnlyMoveItUpStay() {
        val remove = ImmutableMethodReference(SHORTCUT_MANAGER, "removeDynamicShortcuts", listOf("Ljava/util/List;"), "V")
        val elsewhere = ImmutableMethodReference(
            "Landroidx/core/content/pm/ShortcutManagerCompat;", "pushDynamicShortcut",
            listOf("Landroid/content/pm/ShortcutInfo;"), "V",
        )
        val method = method(
            3,
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, remove),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, elsewhere),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        assertEquals(0, method.rerouteShortcutCalls())
        method.implementation!!.instructions.take(2).forEach { assertEquals(Opcode.INVOKE_VIRTUAL, it.opcode) }
        assertNull(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, remove).shortcutCall())
    }

    /** Each call sent has its stand-in in SettingsEntry, public and static, the manager first. */
    @Test
    fun everyCallSentHasAStandIn() {
        val entry = File(RepoFiles.root, "extensions/facebook/src/main/java/app/morphe/extension/facebook/settings/SettingsEntry.java")
            .readText()
        val javaTypes = mapOf("V" to "void", "Z" to "boolean")
        SHORTCUT_CALLS.forEach { (name, shape) ->
            val answer = javaTypes.getValue(shape.substringAfter(')'))
            val declared = Regex("""public static $answer $name\(ShortcutManager manager\b""").containsMatchIn(entry)
            assertEquals("SettingsEntry has no public static $answer $name(ShortcutManager manager, ...)", true, declared)
        }
    }

    /**
     * The receipt refuses a patched build that still makes one of these calls outside the extension,
     * by a no-call rule per call in scripts/injected-mutation-contracts.txt. A rule spelled wrong
     * matches no call and reports "0 call sites" for ever, and a call added to [SHORTCUT_CALLS]
     * without a rule is never looked for. So the ShortcutManager rules and the calls the rewrite
     * sends are the same set, each allowed only under the prefix the rewrite leaves alone.
     */
    @Test
    fun theContractFileHoldsEveryCallTheRewriteSends() {
        val rules = File(RepoFiles.root, "scripts/injected-mutation-contracts.txt").readLines()
            .map { it.trim() }
            .filter { it.startsWith("no-call ") }
            .map { it.split(Regex("""\s+""")) }
            .filter { it.getOrNull(1).orEmpty().startsWith("$SHORTCUT_MANAGER->") }
        assertEquals(
            "the no-call rules for ShortcutManager against the calls the rewrite sends",
            SHORTCUT_CALLS.map { (name, shape) -> "$SHORTCUT_MANAGER->$name$shape" }.sorted(),
            rules.map { it[1] }.sorted(),
        )
        rules.forEach { rule ->
            assertEquals("${rule[1]}: where the call is allowed", listOf("outside", EXTENSION_ROOT), rule.drop(2))
        }
    }

    /**
     * Both declared builds push through the AndroidX helper and the Messenger chat shortcuts, and
     * update through the helper and two account switcher paths. Every one of them is sent.
     */
    @Test
    fun eachDeclaredBuildSendsEveryCallThatRanksItsShortcuts() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val methods = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.methodSection.any { it.definingClass == SHORTCUT_MANAGER && it.name in SHORTCUT_CALLS }
                }) { method -> method.implementation?.instructions?.any { it.shortcutCall() != null } == true }
                val sent = methods.flatMap { method -> method.implementation!!.instructions.mapNotNull { it.shortcutCall()?.name } }
                    .groupingBy { it }.eachCount()
                assertEquals(
                    "${bundle.name}: shortcut calls",
                    mapOf("pushDynamicShortcut" to 2, "updateShortcuts" to 3),
                    sent,
                )
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
