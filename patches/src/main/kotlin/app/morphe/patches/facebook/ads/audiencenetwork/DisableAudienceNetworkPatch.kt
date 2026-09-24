/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/disableaudiencenetwork/DisableAudienceNetworkPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: the manifest edit sits behind a bytecode patch, so
 * the settings screen can say the patch is in.
 */
package app.morphe.patches.facebook.ads.audiencenetwork

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.shared.compat.AppCompatibilities
import org.w3c.dom.Element
import app.morphe.patches.facebook.misc.settings.settingsPatch

/**
 * The bridge through which the Facebook app serves ads to *other* apps: an app embedding the
 * Audience Network SDK binds to the installed Facebook app to fetch and render its ads.
 */
private val AUDIENCE_NETWORK_COMPONENTS = setOf(
    "com.facebook.ads.internal.ipc.AudienceNetworkRemoteService",
    "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity",
    "com.facebook.ads.internal.ipc.AudienceNetworkExportedActivity",
    "com.facebook.ads.AudienceNetworkActivity",
    "com.facebook.audiencenetwork.AudienceNetworkService",
)

private val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")

/**
 * Disabling is enough (nothing can bind to or start the component), and it keeps the manifest diff
 * readable and reversible, unlike removing the entries.
 */
private val disableAudienceNetworkResourcePatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            var disabled = 0

            COMPONENT_TAGS.forEach { tag ->
                val nodes = document.getElementsByTagName(tag)
                for (index in 0 until nodes.length) {
                    val element = nodes.item(index) as? Element ?: continue
                    if (element.getAttribute("android:name") !in AUDIENCE_NETWORK_COMPONENTS) {
                        continue
                    }

                    element.setAttribute("android:enabled", "false")
                    disabled++
                }
            }

            check(disabled > 0) {
                "No Audience Network components found in the manifest; they were renamed or removed"
            }
        }
    }
}

@Suppress("unused")
val disableAudienceNetworkPatch = bytecodePatch(
    name = "Disable Audience Network",
    description = "Stops Facebook serving ads to other apps. Those apps then show their own ads " +
        "or none, and rewarded ads can fail.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    dependsOn(disableAudienceNetworkResourcePatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        enableStatus("audienceNetwork")
    }
}
