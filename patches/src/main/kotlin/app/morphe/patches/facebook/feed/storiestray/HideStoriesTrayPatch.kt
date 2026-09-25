/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.storiestray

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.Method

/** The extension's switch for the tray, which takes the adapter it was asked from. */
internal const val HIDE_STORIES_TRAY = "$EXTENSION_PACKAGE/feed/FeedFilter;->hideStoriesTray(I)Z"

/** What the hook passes, matching the extension's `FeedFilter.LEGACY_TRAY` and `UNIFIED_TRAY`. */
internal const val LEGACY_TRAY = 0
internal const val UNIFIED_TRAY = 1

/**
 * Removes the row of stories at the top of the news feed, Create story included.
 *
 * The tray is never an edge, so the feed guard can't see it (a debug log of every edge on a
 * signed-in 580 showed none while the tray was on screen). It is an adapter the feed's adapter
 * configuration adds, in one of two methods (see Fingerprints.kt). Both get the same hook at their
 * first instruction: ask the extension, and return null when it says so, which is what the method
 * returns when Facebook's own gates leave the tray out. The one local the hook uses holds nothing
 * yet at that point. Off by default: stories are people's own posts.
 */
@Suppress("unused")
val hideStoriesTrayPatch = bytecodePatch(
    name = "Hide Stories tray",
    description = "Removes the row of stories at the top of the news feed, Create story included.",
    default = false,
) {
    category("Feed")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val legacy = classDefByStrings(ADD_STORIES_ADAPTER, StringComparisonType.EQUALS).flatMap(::legacyTrayAdapters)
        val classic = legacy.singleOrNull() ?: throw PatchException(
            "Expected one static tray adapter holding \"$ADD_STORIES_ADAPTER\", found ${legacy.size}",
        )
        val configuration = mutableClassDefBy(classic.definingClass)
        val unified = unifiedTrayAdapters(configuration).singleOrNull() ?: throw PatchException(
            "${classic.definingClass} has no single unified tray adapter holding \"$TRAY_ADAPTER_START\", " +
                "\"$TRAY_ADAPTER_STOP\" and \"$TOFU\"",
        )

        configuration.methods.first { it.sameAs(classic) }.skipWhenTrayHidden(LEGACY_TRAY)
        configuration.methods.first { it.sameAs(unified) }.skipWhenTrayHidden(UNIFIED_TRAY)
        enableStatus("storiesTray")
    }
}

private fun MutableMethod.sameAs(other: Method) =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

/**
 * Returns null from the adapter method before it does anything when the extension says the tray is
 * hidden. At the first instruction no local holds anything, so v0 is free, and `const/4` and
 * `return-object` both reach it.
 */
private fun MutableMethod.skipWhenTrayHidden(adapter: Int) {
    requireLocals("Hide Stories tray", 1)
    addInstructionsWithLabels(
        0,
        """
            const/4 v0, $adapter
            invoke-static { v0 }, $HIDE_STORIES_TRAY
            move-result v0
            if-eqz v0, :keep
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("keep", getInstruction(0)),
    )
}
