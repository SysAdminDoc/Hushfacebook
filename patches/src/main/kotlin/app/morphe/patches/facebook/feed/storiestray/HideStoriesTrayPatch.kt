/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.storiestray

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.feed.requireFeedTypeName
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/** The GraphQL type of the stories row. The extension's `FeedFilter.STORIES_TRAY_TYPE` matches it. */
internal const val STORIES_TRAY_TYPE = "StoriesTrayFeedUnit"

/**
 * The row of stories at the top of the news feed is a feed edge like any post, so the shared
 * feed guard drops it before Facebook adds it. Off by default: stories are people's own posts.
 */
@Suppress("unused")
val hideStoriesTrayPatch = bytecodePatch(
    name = "Hide Stories tray",
    description = "Removes the row of stories at the top of the news feed, Create story included.",
    default = false,
) {
    category("Feed")
    dependsOn(settingsPatch)
    dependsOn(feedFilterHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        requireFeedTypeName(STORIES_TRAY_TYPE)
        enableStatus("storiesTray")
    }
}
