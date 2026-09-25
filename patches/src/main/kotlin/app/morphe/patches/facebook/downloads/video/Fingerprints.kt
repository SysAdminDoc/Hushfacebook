/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.video

import app.morphe.patcher.Fingerprint

internal const val MENU = "Landroid/view/Menu;"
internal const val MENU_ITEM = "Landroid/view/MenuItem;"
internal const val VIEW = "Landroid/view/View;"

/** Kept name. Every post menu builder takes the player origin last. */
internal const val PLAYER_ORIGIN = "Lcom/facebook/video/common/playerorigin/PlayerOrigin;"

/** Kept names: the post, one of its attachments, and an attachment's media. */
internal const val GRAPHQL_STORY = "Lcom/facebook/graphql/model/GraphQLStory;"
internal const val GRAPHQL_STORY_ATTACHMENT = "Lcom/facebook/graphql/model/GraphQLStoryAttachment;"
internal const val GRAPHQL_MEDIA = "Lcom/facebook/graphql/model/GraphQLMedia;"
internal const val IMMUTABLE_LIST = "Lcom/google/common/collect/ImmutableList;"

/**
 * The GraphQL fields the getters read, as the hash Facebook's models look a field up by: the Java
 * hash of the field's GraphQL name. Redex renames the getters every build and leaves these alone.
 * On 580 `GraphQLStory` reads `attachments` in `A0n()` and `attached_story` in `A04()`, on 577 in
 * `A0m()` and `A03()`; `GraphQLStoryAttachment` reads `media` in `A01()` on both (fixtures,
 * 2026-09-25). Each is the only no-argument getter of its class holding that constant.
 */
internal val ATTACHMENTS_FIELD = "attachments".hashCode()
internal val ATTACHED_STORY_FIELD = "attached_story".hashCode()
internal val MEDIA_FIELD = "media".hashCode()

/** The name Facebook's own Download video row reports itself under. */
internal const val STOCK_DOWNLOAD_ROW = "DOWNLOAD_VIDEO"

/**
 * The method that fills the menu of a post in the video feed: Watch, and a video opened from the
 * news feed. It logs under its helper's name, and it's where Facebook adds its own Download video
 * row, for the videos whose owner allows one.
 *
 * Read from the fixtures (2026-09-25): on 580 it's `LX/SA3;->A0j`, on 577 `LX/Sct;->A0i`, each
 * `(Menu, View, <post props>, ..., PlayerOrigin)V` with both literals, and the only method in
 * either build that holds both. Its superclass (`LX/2rF;` on 580, `LX/2xZ;` on 577) declares the
 * same method, and every post menu, feed ones included, goes through that one: the patch finds
 * the call site from there. Facebook's own row sets `fb_ic_download_24` (0x7f1a02de on 580,
 * 0x7f1a02df on 577, per aapt2) just before it names itself, which is the icon the item borrows.
 */
internal object VideoFeedStoryMenuFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("VideoFeedStoryMenuHelper", STOCK_DOWNLOAD_ROW),
    custom = { method, _ ->
        val parameters = method.parameterTypes.map(CharSequence::toString)
        parameters.size >= 3 && parameters[0] == MENU && parameters[1] == VIEW && parameters.last() == PLAYER_ORIGIN
    },
)
