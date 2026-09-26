/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/externalbrowser/ForceExternalBrowserPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.misc.externalbrowser

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patches.facebook.misc.settings.settingsPatch

private const val EXTENSION_CLASS = "Lapp/morphe/extension/facebook/misc/ExternalBrowser;"

private const val REDIRECT =
    "$EXTENSION_CLASS->redirect(Landroid/app/Activity;Landroid/content/Intent;)Z"

private const val PATCH = "Open links in external browser"

/**
 * Facebook ships **two** in-app browsers. The newer one opens a tapped link.
 *
 * `handleByBrowserLite` sets the component of the launch intent, and it names the `litev2`
 * activity. Nothing else can redirect an explicit component bind. Thus a hook on the original
 * browser alone leaves each ordinary link in the app. The first device round showed this. The hook
 * was correct in the build, and it never ran.
 *
 * Both names are kept names, and `AndroidManifest.xml` declares them. Each has a subclass that
 * comes through `super.onCreate`. Thus these two entries cover four activities.
 */
private val IN_APP_BROWSERS = listOf(
    // The newer browser. Ordinary links go to this one.
    "Lcom/facebook/browser/litev2/lite/BrowserLiteDIActivity;",
    // The original. Some surfaces still reach it, thus it keeps its hook.
    "Lcom/facebook/browser/lite/BrowserLiteActivity;",
)

@Suppress("unused")
val openLinksExternallyPatch = bytecodePatch(
    name = "Open links in external browser",
    description = "Opens web links in your default browser instead of Facebook's in-app " +
        "browser, without Facebook's click tracker or the fbclid tag it adds. Facebook pages still " +
        "open in the app.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())
    dependsOn(facebookExtensionPatch)

    // Each hook goes in after the superclass call. At that point the activity is a valid Activity
    // for the extension, and the browser is not built yet. The extension returns false when the
    // link must stay in the app. The injected branch then continues into the original code.
    execute {
        val hooked = IN_APP_BROWSERS.sumOf { descriptor ->
            val classDef = mutableClassDefByOrNull(descriptor) ?: return@sumOf 0

            // onCreate: the URL is the data of the launch intent. Read it from the activity.
            val onCreate = lifecycleMethod(classDef.methods, descriptor, "onCreate", "Landroid/os/Bundle;")
            onCreate.hookRedirect(
                loadIntent = """
                    invoke-virtual { p0 }, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
                    move-result-object v0
                """,
            )

            // onNewIntent: the new URL comes in as the parameter. getIntent() still returns the
            // intent that started the browser, which holds the previous link.
            val onNewIntent = lifecycleMethod(classDef.methods, descriptor, "onNewIntent", "Landroid/content/Intent;")
            onNewIntent.hookRedirect(loadIntent = null)

            2
        }

        check(hooked > 0) {
            "No in-app browser activity. The com.facebook.browser packages have new names."
        }

        enableStatus("externalBrowser")
    }
}

/** The browser activity's [name] method taking one [parameter], or a refusal naming it. */
internal fun <T : Method> lifecycleMethod(methods: Iterable<T>, activity: String, name: String, parameter: String): T =
    methods.filter { it.name == name && it.parameterTypes.map(CharSequence::toString) == listOf(parameter) }
        .singleOrPatchException("$PATCH: $activity's $name($parameter)")

/**
 * Put the redirect after the superclass call of this method.
 *
 * [loadIntent] is the smali that leaves the intent in `v0`. It must hold its own
 * `move-result-object`, because the call that gets the intent is of no use without one. It is null
 * when the intent is already the parameter of the method.
 *
 * The redirect must go after the superclass call. Before it, the superclass call does not run, and
 * Android answers with `SuperNotCalledException`.
 */
internal fun MutableMethod.hookRedirect(loadIntent: String?) {
    val injectIndex = ownSuperCallIndex() + 1
    val intent = if (loadIntent != null) "v0" else "p1"

    // The calls below name p0, and onNewIntent's p1, in operands that only reach v15. The
    // patcher's smali compiler leaves out an instruction whose register doesn't fit, without a
    // word, so a method with its parameters higher up has to stop the patch here instead.
    val highest = localRegisterCount() + if (loadIntent != null) 0 else 1
    if (highest > 15) {
        throw PatchException(
            "$PATCH: $definingClass->$name holds a parameter the redirect names in v$highest, above v15",
        )
    }
    val call = """
        ${loadIntent ?: ""}
        invoke-static { p0, $intent }, $REDIRECT
        move-result v0
    """

    val traceClose = traceCloseIndex()

    if (traceClose != null) {
        // The method opens a trace section in its prologue. A direct return leaves that section
        // open. Thus the branch jumps to the instruction that loads the marker of the close call.
        addInstructionsWithLabels(
            injectIndex,
            "$call\nif-nez v0, :handled",
            ExternalLabel("handled", getInstruction(traceClose)),
        )
    } else {
        // There is no trace section to balance, thus the redirect returns. The label binds to the
        // real instruction that comes after, and never to one inside the injected block.
        addInstructionsWithLabels(
            injectIndex,
            "$call\nif-eqz v0, :keepInApp\nreturn-void",
            ExternalLabel("keepInApp", getInstruction(injectIndex)),
        )
    }
}

/**
 * Where this method calls the method it overrides: the one `invoke-super` with its own name,
 * parameters and return type.
 *
 * Not simply the first super call. `BrowserLiteDIActivity.onCreate` makes two on both builds,
 * `onCreate` and then `getResources()`, so the first is only right while they stay in that order.
 * A redirect after a super call that comes first would finish the activity before
 * `super.onCreate` ran. None, or two, stops the patch.
 */
internal fun MutableMethod.ownSuperCallIndex(): Int =
    instructions().withIndex().filter { (_, instruction) ->
        val call = instruction.methodReferenceOrNull()
        (instruction.opcode == Opcode.INVOKE_SUPER || instruction.opcode == Opcode.INVOKE_SUPER_RANGE) &&
            call != null && call.name == name && call.returnType == returnType &&
            call.parameterTypes.map(CharSequence::toString) == parameterTypes.map(CharSequence::toString)
    }.map { it.index }
        .singleOrPatchException("$PATCH: $definingClass->$name's call to super.$name")

/**
 * Where the trace section of the method closes, or null when the method opens none.
 *
 * Facebook wraps these methods in a section. A static call that returns the marker of the section
 * opens it. A static call on the same class that takes the marker back closes it.
 *
 * The index is the instruction that feeds the close call. A jump to that instruction keeps the
 * section balanced and keeps the value of the marker.
 */
private fun MutableMethod.traceCloseIndex(): Int? {
    val instructions = instructions()

    val tracer = instructions.firstNotNullOfOrNull { instruction ->
        instruction.methodReferenceOrNull()
            ?.takeIf { instruction.opcode == Opcode.INVOKE_STATIC && it.returnType == "I" }
            ?.definingClass
    } ?: return null

    val closeIndex = instructions.indexOfLast { instruction ->
        instruction.opcode == Opcode.INVOKE_STATIC &&
            instruction.methodReferenceOrNull()
                ?.let { it.definingClass == tracer && it.returnType == "V" } == true
    }

    return if (closeIndex > 0) closeIndex - 1 else null
}

private fun MutableMethod.instructions(): List<Instruction> =
    implementation?.instructions?.toList()
        ?: throw IllegalStateException("$definingClass->$name has no body to patch")

private fun Instruction.methodReferenceOrNull() =
    (this as? ReferenceInstruction)?.reference as? MethodReference
