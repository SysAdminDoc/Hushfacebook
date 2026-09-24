/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/blockadtelemetry/BlockAdTelemetryPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.ads.telemetry

import app.morphe.patches.facebook.shared.neuterVoidMethods
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.settings.settingsPatch

/** Ad measurement that runs whether or not an ad is shown. All keep their real names. */
private val AD_TELEMETRY = listOf(
    // Watches for you taking a screenshot of an ad. The controller's void methods add and remove
    // the detector's listener, so neutering them means it is never registered.
    "Lcom/facebook/ads/screenshot/AdsScreenshotController;",
    "Lcom/facebook/ads/AdsScreenshotDetector;",
    // Ad attribution for apps you install: the tracker's scheduler and the service that does the
    // reporting (doHandleIntent keeps its name).
    "Lcom/facebook/feed/platformads/AppInstallTrackerScheduler;",
    "Lcom/facebook/feed/platformads/AppInstallService;",
)

@Suppress("unused")
val blockAdTelemetryPatch = bytecodePatch(
    name = "Block ad telemetry",
    description = "Stops Facebook watching for screenshots of ads and reporting which apps you " +
        "install for ad attribution.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    // Deliberately not covered:
    //
    // - AdVisualQualityEngine / OcrPreprocessor (on-device OCR of ad creative). Their only entry
    //   points are suspend functions returning Object, so an early return hands null to a
    //   continuation that does not expect one. They only run on a rendered ad anyway.
    // - PigeonFeedUnitSponsoredImpressionLogger. Its one clean entry point marks an impression as
    //   *already logged*; neutering it invites repeat logging rather than none.
    execute {
        val neutered = AD_TELEMETRY.sumOf { neuterVoidMethods(it) }

        check(neutered > 0) {
            "No ad telemetry classes found; com.facebook.ads.screenshot / " +
                "com.facebook.feed.platformads were renamed or removed"
        }

        enableStatus("adTelemetry")
    }
}
