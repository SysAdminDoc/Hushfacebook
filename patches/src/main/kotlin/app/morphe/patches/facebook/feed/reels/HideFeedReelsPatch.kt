/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.reels

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.shared.FEED_STORY_CATEGORY
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * The story categories Facebook files the feed's rows of reels under, as the extension's
 * `FeedFilter.REELS_CATEGORIES` names them. FeedReelsParityTest holds the two lists together.
 */
internal val REELS_CATEGORIES = listOf("FB_SHORTS", "FB_SHORTS_FALLBACK", "END_OF_FEED_REELS")

/** The names GraphQLFeedStoryCategory's static initializer builds its constants with. */
internal fun categoryNames(category: ClassDef): Set<String> =
    category.methods.filter { it.name == "<clinit>" }.flatMap { method ->
        method.implementation?.instructions?.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        } ?: emptyList()
    }.toSet()

/**
 * Removes the feed's rows of reels: the "Reels" carousels between posts, and the reels Facebook
 * adds where the feed you follow ends.
 *
 * Each row is a feed edge of its own, filed under a reels story category (a debug log of every
 * edge on a signed-in 580 showed `FB_SHORTS` and `END_OF_FEED_REELS`, both ShowcaseFeedUnit), so
 * the rule lives in the shared feed guard with the others, and `addNewEdgeToCollection` still
 * carries one Hushfacebook guard. The rule matches the categories by name, so the patch holds the
 * build to all of them: a Facebook that renamed one would otherwise leave the switch hiding nothing
 * without a word. A reel a friend posts is an ordinary post and stays. Off by default, like the
 * Stories tray.
 */
@Suppress("unused")
val hideFeedReelsPatch = bytecodePatch(
    name = "Hide Reels in the feed",
    description = "Removes the rows of reels between posts in the news feed, and the reels Facebook adds " +
        "where your feed ends. A reel a friend posts stays.",
    default = false,
) {
    category("Feed")
    dependsOn(settingsPatch)
    dependsOn(feedFilterHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val names = categoryNames(classDefBy(FEED_STORY_CATEGORY))
        val missing = REELS_CATEGORIES.filterNot(names::contains)
        if (missing.isNotEmpty()) {
            throw PatchException(
                "GraphQLFeedStoryCategory has no ${missing.joinToString()}, so the feed's reels rows can't be told apart",
            )
        }
        enableStatus("feedReels")
    }
}
