/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.FACEBOOK_APPLICATION
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.Opcode

private const val ENTRY = "$EXTENSION_PACKAGE/settings/SettingsEntry;"

/** The activity the manifest's launcher alias targets. A manifest name is never obfuscated. */
private const val MAIN_TAB_ACTIVITY = "Lcom/facebook/katana/activity/FbMainTabActivity;"

/**
 * The first class in [type]'s hierarchy, as far as the APK carries it, that declares this
 * method with a body.
 */
private fun BytecodePatchContext.declaredInHierarchy(
    type: String,
    name: String,
    vararg parameters: String,
): MutableMethod = superclassChain(type)
    .mapNotNull { mutableClassDefByOrNull(it) }
    .firstNotNullOfOrNull { classDef ->
        classDef.methods.singleOrNull { method ->
            method.name == name && method.returnType == "V" && method.implementation != null &&
                method.parameterTypes.map { it.toString() } == parameters.toList()
        }
    } ?: throw PatchException("No class of $type's hierarchy declares $name(${parameters.joinToString("")})V")

/**
 * Makes the Hushfacebook screen reachable: a long-press shortcut on Facebook's launcher icon opens
 * Facebook with an extra, the activity reports it, and the screen opens over the next Facebook
 * activity to resume. Every name used here is a manifest component or a framework override, which
 * the obfuscator keeps. Nothing is added to the manifest.
 */
@Suppress("unused")
val settingsPatch = bytecodePatch(
    name = "Hushfacebook settings",
    description = "Adds Hushfacebook settings to Facebook's launcher icon. Long-press the icon to turn " +
        "features on or off, pause Hushfacebook, save your switches to a file or load them, and export " +
        "diagnostics. The licenses are there too.",
    default = true,
) {
    category("Settings")
    dependsOn(facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Before each return of the application's onCreate, after Facebook's own startup: the
        // application overrides registerActivityLifecycleCallbacks, and the override is only safe
        // to call once Facebook has set itself up.
        val applicationOnCreate = declaredInHierarchy(FACEBOOK_APPLICATION, "onCreate")
        val returns = applicationOnCreate.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }
        if (returns.isEmpty()) throw PatchException("$FACEBOOK_APPLICATION.onCreate never returns")
        returns.asReversed().forEach { index ->
            applicationOnCreate.addInstruction(
                index,
                "invoke-static/range { p0 .. p0 }, $ENTRY->onApplicationCreate(Landroid/content/Context;)V",
            )
        }

        declaredInHierarchy(MAIN_TAB_ACTIVITY, "onCreate", "Landroid/os/Bundle;").addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $ENTRY->onActivityCreate(Landroid/app/Activity;)V",
        )
        declaredInHierarchy(MAIN_TAB_ACTIVITY, "onNewIntent", "Landroid/content/Intent;").addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $ENTRY->onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)V",
        )
    }
}
