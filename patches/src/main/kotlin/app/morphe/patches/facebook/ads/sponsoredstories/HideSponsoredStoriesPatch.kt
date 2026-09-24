/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/hidesponsoredstories/HideSponsoredStoriesPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: each pass-through asks the settings switch first, and
 * the list's register is computed for static methods and wide parameters too.
 */
package app.morphe.patches.facebook.ads.sponsoredstories

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.settings.settingsPatch

private const val SWITCH = "$EXTENSION_PACKAGE/feed/FeedFilter;->hideSponsoredStories()Z"

@Suppress("unused")
val hideSponsoredStoriesPatch = bytecodePatch(
    name = "Hide sponsored stories",
    description = "Removes ad cards from the story viewer, so swiping through stories only shows stories people posted.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    // Return the caller's list unchanged rather than filtering the result: Facebook does exactly
    // this when a bucket data source has nothing to contribute, and it avoids depending on the ad
    // bucket's numeric type -- a bare integer with no name behind it, and the one thing here that
    // could silently change between releases.
    //
    // All four sources are neutered, not just one. They sit in the same chain, a factory picks
    // between two of them by flag, and one of them only fetches once the viewer is already open --
    // which is the ad that survives into a session and disappears on the next cold start.
    execute {
        listOf(
            InorganicClashResolverFingerprint,
            AdBucketDataSourceFingerprint,
            AdPaginatingBucketInserterFingerprint,
            StoryViewerMidCardFingerprint,
        ).forEach { fingerprint ->
            val method = fingerprint.method

            val listParameter = method.parameterTypes.indexOfLast { it.toString() == IMMUTABLE_LIST }
            if (listParameter < 0) {
                throw PatchException(
                    "${method.definingClass}->${method.name} takes no ImmutableList to pass through: " +
                        method.parameterTypes.joinToString(),
                )
            }
            // Index 0: no local holds anything yet, so v0 is free to take the switch's answer.
            method.requireLocals("Hide sponsored stories", 1)

            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $SWITCH
                    move-result v0
                    if-eqz v0, :original
                    return-object ${method.parameterRegister(listParameter)}
                """,
                ExternalLabel("original", method.getInstruction(0)),
            )
        }

        enableStatus("sponsoredStories")
    }
}
