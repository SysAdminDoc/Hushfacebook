/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.refresh

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.Method

private const val CONTROLLER = "FeedRefreshTriggerController"
private const val ON_REFRESH = "onRefresh"
private const val SKIP = "$EXTENSION_PACKAGE/feed/ReturnRefresh;->skip()Z"

/** Stops only the refresh fired by FeedRefreshTriggerController when Facebook returns to view. */
@Suppress("unused")
val blockReturnRefreshPatch = bytecodePatch(
    name = "Block background-return feed refresh",
    description = "Keeps your feed position when you return to Facebook within ten minutes. " +
        "Pull to refresh and a fresh launch still work.",
    default = false,
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val callbacks = classDefByStrings(CONTROLLER, StringComparisonType.EQUALS)
            .flatMap { it.methods.filter(::isReturnRefreshCallback) }
        val callback = callbacks.singleOrNull() ?: throw PatchException(
            "Expected one FeedRefreshTriggerController resume callback holding $ON_REFRESH, found ${callbacks.size}",
        )
        mutableClassDefBy(callback.definingClass).methods.first {
            it.name == callback.name && it.parameterTypes == callback.parameterTypes
        }.skipBriefReturnRefresh()
        enableStatus("returnRefresh")
    }
}

private fun isReturnRefreshCallback(method: Method): Boolean =
    method.returnType == "V" && method.parameterTypes.size == 1 && method.implementation != null &&
        holdsString(method, CONTROLLER) && holdsString(method, ON_REFRESH)

private fun MutableMethod.skipBriefReturnRefresh() {
    requireLocals("Block background-return feed refresh", 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $SKIP
            move-result v0
            if-eqz v0, :keep
            return-void
        """,
        ExternalLabel("keep", getInstruction(0)),
    )
}
