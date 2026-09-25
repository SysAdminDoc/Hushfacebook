/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharelinks

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/** Kept name. The signed-in session every share-link method takes first or second. */
internal const val FB_USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

/** Kept name. The model behind the live video share dialog. */
internal const val LIVE_SHARE_DIALOG_MODEL =
    "Lcom/facebook/facecast/display/sharedialog/utils/LiveStreamingShareDialogModel;"

/**
 * ExternalShareTracker's main method: it takes the link a share hands out and returns it with
 * `mibextid` added, chosen by where the share started and where it goes, and logs the share.
 * Copy link from the post, reel, profile and story menus, WhatsApp, SMS, Instagram Direct, More
 * options and Send in Messenger all come through it.
 *
 * Its class and name are Redex names (`LX/dmd;->A04` in 580, `LX/aUT;->A04` in 577). The log tag
 * is the one literal both builds keep inline: the key name itself is a MobileConfig default, and
 * 577 reads its other literals from a string table. The session is a kept class.
 */
internal object ExternalShareTrackerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(FB_USER_SESSION, "Ljava/lang/Integer;", "Ljava/lang/Integer;",
        "Ljava/lang/String;", "Ljava/lang/String;", "Z"),
    strings = listOf("ExternalShareTracker"),
)

/**
 * Adds `sfnsn` (`mo`, `wa`, `wawes`) to a link sent to WhatsApp. A static (url, value) method
 * whose only literal of note is the key.
 */
internal object SfnsnAppenderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
    strings = listOf("sfnsn"),
)

/** Adds `ref=share` to a story's link for some stories. GraphQLStory is a kept class. */
internal object RefShareAppenderFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Lcom/facebook/graphql/model/GraphQLStory;", "Ljava/lang/String;"),
    strings = listOf("ref", "share"),
)

/** A group's share link: `mibextid` added directly, `controlMsg` for Messenger and `controlTwi`. */
internal object GroupShareLinkFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(FB_USER_SESSION, "Ljava/lang/Integer;", "Ljava/lang/String;"),
    strings = listOf("controlMsg", "controlTwi"),
)

/**
 * The live video share dialog's link, with `sfnsn` added. The class is kept and its method is
 * renamed each build (`COD` in 580, `COZ` in 577), so only its shape and the key find it.
 */
internal object LiveShareLinkFingerprint : Fingerprint(
    definingClass = LIVE_SHARE_DIALOG_MODEL,
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("sfnsn"),
)
