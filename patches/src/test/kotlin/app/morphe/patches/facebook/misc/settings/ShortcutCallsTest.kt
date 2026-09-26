/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.RepoFiles
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.FACEBOOK_APPLICATION
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * Each call sent has its stand-in in the SettingsEntry the bundle ships, public and static, with
     * exactly the descriptor the rewrite writes: the manager, then the framework call's own
     * parameters, then its answer. Read from the compiled extension, so a Java parameter that
     * compiles to another type fails here. A match on the source's name and first parameter let
     * that through to a NoSuchMethodError in Facebook's notification code.
     */
    @Test
    fun everyCallSentHasAStandIn() {
        val declared = ExtensionDex.classDef(ENTRY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$ENTRY->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            .toSet()
        SHORTCUT_CALLS.forEach { (name, shape) ->
            val descriptor = "$ENTRY->$name($SHORTCUT_MANAGER${shape.substringAfter('(')}"
            assertEquals("what the rewrite writes for $name", descriptor, standIn(call(name)))
            assertTrue(
                "SettingsEntry declares no public static $descriptor. Its methods of that name: " +
                    declared.filter { it.contains("->$name(") },
                descriptor in declared,
            )
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
     * The settings patch itself, run over an application, a main activity and two classes that make
     * all five calls: Facebook's, where every call goes to its stand-in, and the extension's, whose
     * calls are the real ones the stand-ins make and stay. Sending those would make each stand-in
     * call itself.
     */
    @Test
    fun theSettingsPatchSendsEveryCallOutsideTheExtension() {
        val facebook = "Lfixture/ShortcutPublisher;"
        val context = PatchContexts.of(hosts() + publisher(facebook) + publisher(ENTRY))

        settingsPatch.execute(context)

        val sent = context.mutableClassDefBy(facebook).methods.single().instructions()
        assertEquals("framework calls left in Facebook's code", emptyList<String>(), sent.mapNotNull { it.frameworkCall() })
        assertEquals("stand-ins in Facebook's code", SHORTCUT_CALLS.keys.sorted(), sent.mapNotNull { it.standInCall() }.sorted())
        val kept = context.mutableClassDefBy(ENTRY).methods.single().instructions()
        assertEquals("the extension's own calls", SHORTCUT_CALLS.keys.sorted(), kept.mapNotNull { it.frameworkCall() }.sorted())
        assertEquals("stand-ins in the extension", emptyList<String>(), kept.mapNotNull { it.standInCall() })
    }

    /**
     * Both declared builds push through the AndroidX helper and the Messenger chat shortcuts, and
     * update through the helper and two account switcher paths. The settings patch, run over each
     * build's classes that make those calls, sends every one to its stand-in with the registers it
     * had, the instruction count unchanged, and leaves none behind. The application and main
     * activity the patch's other hooks go into are stand-ins here, since this reads only the
     * shortcut calls. A whole patching run is out of a unit test's reach: there the receipt's
     * no-call rules read the patched APK.
     */
    @Test
    fun eachDeclaredBuildSendsEveryCallThatRanksItsShortcuts() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val callers = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.methodSection.none { it.definingClass == SHORTCUT_MANAGER && it.name in SHORTCUT_CALLS }) {
                        return@forEach
                    }
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.instructions().any { it.frameworkCall() != null } }) {
                            callers += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val found = callers.flatMap { it.methods }.flatMap { it.instructions() }
                    .mapNotNull { it.frameworkCall() }.groupingBy { it }.eachCount()
                assertEquals(
                    "${bundle.name}: shortcut calls in the stock build",
                    mapOf("pushDynamicShortcut" to 2, "updateShortcuts" to 3),
                    found,
                )
                val hosts = hosts()
                assertTrue(
                    "${bundle.name}: a class making the calls is one the test stands in for",
                    callers.none { caller -> hosts.any { it.type == caller.type } },
                )

                val context = PatchContexts.of(hosts + callers)
                settingsPatch.execute(context)

                val sent = mutableMapOf<String, Int>()
                for (before in callers) {
                    val after = context.mutableClassDefBy(before.type).methods
                    for (original in before.methods) {
                        val was = original.instructions()
                        if (was.none { it.frameworkCall() != null }) continue
                        val where = "${bundle.name}: ${original.definingClass}->${original.name}"
                        val now = after.single { it.sameSignatureAs(original) }.instructions()
                        assertEquals("$where: instruction count", was.size, now.size)
                        assertEquals("$where: framework calls left", emptyList<String>(), now.mapNotNull { it.frameworkCall() })
                        was.forEachIndexed { index, instruction ->
                            val framework = instruction.shortcutCall() ?: return@forEachIndexed
                            val replacement = now[index]
                            assertEquals(
                                "$where at $index",
                                standIn(framework),
                                (replacement as ReferenceInstruction).reference.toString(),
                            )
                            assertEquals(
                                "$where at $index: the static form of ${instruction.opcode}",
                                if (instruction is RegisterRangeInstruction) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC,
                                replacement.opcode,
                            )
                            assertEquals("$where at $index: registers", instruction.registers(), replacement.registers())
                            sent.merge(framework.name, 1, Int::plus)
                        }
                    }
                }
                assertEquals("${bundle.name}: calls sent to their stand-ins", found, sent)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** Parameters compared as text: dexlib2's lists of two kinds don't equal each other. */
    private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

    /** The [SHORTCUT_CALLS] name this instruction calls on ShortcutManager, by name alone, or null. */
    private fun Instruction.frameworkCall(): String? {
        val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
        return reference.name.takeIf { reference.definingClass == SHORTCUT_MANAGER && it in SHORTCUT_CALLS }
    }

    /** The [SHORTCUT_CALLS] name of the SettingsEntry stand-in this instruction calls statically, or null. */
    private fun Instruction.standInCall(): String? {
        if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return null
        val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
        return reference.name.takeIf { reference.definingClass == ENTRY && it in SHORTCUT_CALLS }
    }

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun parameters(vararg types: String) = types.map { ImmutableMethodParameter(it, null, null) }

    private fun body(registers: Int, vararg instructions: Instruction) =
        ImmutableMethodImplementation(registers, instructions.toList(), null, null)

    /** The application and the main activity, each with the method one of the settings patch's other hooks goes into. */
    private fun hosts(): List<ClassDef> {
        val mainTab = "Lcom/facebook/katana/activity/FbMainTabActivity;"
        val returns = ImmutableInstruction10x(Opcode.RETURN_VOID)
        return listOf(
            ImmutableClassDef(
                FACEBOOK_APPLICATION, AccessFlags.PUBLIC.value, "Landroid/app/Application;", null, null, null, null,
                listOf(ImmutableMethod(FACEBOOK_APPLICATION, "onCreate", parameters(), "V", AccessFlags.PUBLIC.value, null, null, body(1, returns))),
            ),
            ImmutableClassDef(
                mainTab, AccessFlags.PUBLIC.value, "Landroid/app/Activity;", null, null, null, null,
                listOf(
                    ImmutableMethod(mainTab, "onCreate", parameters("Landroid/os/Bundle;"), "V", AccessFlags.PUBLIC.value, null, null, body(2, returns)),
                    ImmutableMethod(mainTab, "onNewIntent", parameters("Landroid/content/Intent;"), "V", AccessFlags.PUBLIC.value, null, null, body(2, returns)),
                ),
            ),
        )
    }

    /**
     * A class of [type] whose one static method makes all five calls the way Facebook's code does:
     * v1 the manager, v2 a list, v3 a shortcut, each boolean answer read into v0, and the set as a
     * range call.
     */
    private fun publisher(type: String): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(
            ImmutableMethod(
                type, "publish", parameters(SHORTCUT_MANAGER, "Ljava/util/List;", "Landroid/content/pm/ShortcutInfo;"), "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                body(
                    4,
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 3, 0, 0, 0, call("pushDynamicShortcut")),
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, call("addDynamicShortcuts")),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                    ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 1, 2, call("setDynamicShortcuts")),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 2, 0, 0, 0, call("updateShortcuts")),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, call("removeAllDynamicShortcuts")),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
        ),
    )
}
