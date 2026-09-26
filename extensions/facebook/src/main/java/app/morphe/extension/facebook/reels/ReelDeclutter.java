/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.feed.FeedFilter;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * What the Clean up Reels patch asks before a reel's footer is built.
 *
 * <p>Three switches, four hooks, all in the Reels viewer of Facebook 577 and 580:
 *
 * <ul>
 *   <li>The chips under a reel come from one method that walks the reel's attribution models and
 *       builds the list of those it will draw. The patch hands that list here before each return,
 *       and {@link #filterChips} answers the chips to keep, or null to keep the list as it came.
 *       Each chip is a generated model that answers its GraphQL type with {@code getTypeName()},
 *       and only the types in {@link #HIDDEN_CHIPS} go. A type this doesn't know stays.
 *   <li>The Follow button beside a reel's author is left out when Facebook's own config getter for
 *       removing it answers true. {@link #hideFollowButton} is asked first thing in that getter.
 *   <li>The comment Facebook picks to show under a reel and the bubbles of friends who reacted each
 *       come from a query a runnable starts. {@link #skipHotComment} and {@link #skipSocialBubbles}
 *       are asked first thing in each, and true makes the runnable return before it queries.
 * </ul>
 *
 * <p>Every hook fails open: until the settings are ready, while Hushfacebook is paused, with a
 * switch off, or when something throws, Facebook's own path runs.
 */
@SuppressWarnings("unused")
public final class ReelDeclutter {
    /**
     * The GraphQL types of the chips the chips switch hides: the prompts to make something of your
     * own (Remix, Use template, Add yours, Edits) and the promotions (Stars, instant games, a
     * partner app, a link out of Facebook). Facebook 577 and 580 both name all eight in the method
     * the patch hooks, and the patch holds each build to them.
     */
    static final String[] HIDDEN_CHIPS = {
            "XFBFBShortsRemixAttribution",
            "XFBFBShortsTemplateAttribution",
            "XFBFBShortsAddYoursStickerAttribution",
            "XFBFBShortsEditsAppAttribution",
            "XFBFBShortsSendStarsAttribution",
            "XFBFBShortsInstantGamesAttribution",
            "XFBFBShortsPartnerAppAttribution",
            "XFBFBShortsExternalLinkAttribution",
    };

    /** The diagnostic counter routes. */
    static final String CHIPS_ROUTE = "Reel chips";
    static final String FOLLOW_ROUTE = "Reel Follow button";
    static final String FOOTER_ROUTE = "Reel footer queries";

    /** The kind a chip is counted under when its type can't be read. It stays. */
    static final String UNREADABLE = "unreadable type";

    /** The footer queries' kinds in the report. */
    static final String HOT_COMMENT = "hot comment";
    static final String SOCIAL_BUBBLES = "friends' reactions";

    /** What has had its debug line, so each kind of change is logged once. */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private ReelDeclutter() {
    }

    /**
     * Injection point, before each return of the method that builds a reel's chip list. The chips
     * to keep, when the switch took any out, or null to keep the list Facebook built. The patch
     * rebuilds its list from the answer. Never throws.
     *
     * @param chips the list Facebook is about to return, an ImmutableList of attribution models.
     */
    public static Object[] filterChips(Object chips) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            if (!(chips instanceof List)) return null;
            List<?> list = (List<?>) chips;
            FeedFilterCounters.sawList(CHIPS_ROUTE, list.size());
            if (list.isEmpty() || !Utils.settingsReady() || !Settings.HIDE_REEL_CHIPS.get()) return null;

            List<Object> kept = new ArrayList<>(list.size());
            for (Object chip : list) {
                // Facebook's list can't hold null, and the list the patch rebuilds can't either.
                if (chip == null) return null;
                String type = FeedFilter.typeName(chip);
                // The report names every chip type a reel carried, which is how a new one is found.
                FeedFilterCounters.sawKind(CHIPS_ROUTE, type == null ? UNREADABLE : type);
                if (isHiddenChip(type)) {
                    FeedFilterCounters.removed(CHIPS_ROUTE, 1, type);
                    logOnce("chip " + type, () -> "Reel chips: hid " + type);
                    continue;
                }
                kept.add(chip);
            }
            return kept.size() == list.size() ? null : kept.toArray();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "reel chips", failure);
            Logger.printException(() -> "Reel chips: could not filter the list", failure);
            return null;
        }
    }

    /** Whether a chip of this GraphQL type is one the chips switch hides. */
    static boolean isHiddenChip(String type) {
        if (type == null) return false;
        for (String hidden : HIDDEN_CHIPS) {
            if (hidden.equals(type)) return true;
        }
        return false;
    }

    /**
     * Injection point, first thing in Facebook's config getter for removing the Follow button from a
     * reel's author row. True makes the getter answer true, so the row is built without the button.
     * Never throws: false runs Facebook's own getter.
     */
    public static boolean hideFollowButton() {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(FOLLOW_ROUTE, 1);
            boolean hide = Utils.settingsReady() && Settings.HIDE_REEL_FOLLOW_BUTTON.get();
            if (hide) {
                FeedFilterCounters.removed(FOLLOW_ROUTE, 1, "Follow button");
                logOnce("follow", () -> "Reel Follow button: hidden");
            }
            return hide;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "Follow button", failure);
            Logger.printException(() -> "Reel Follow button: could not read its switch", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in the runnable that queries the comment Facebook previews under
     * a reel. True makes it return without querying, so nothing is previewed. Never throws.
     */
    public static boolean skipHotComment() {
        return skipFooterQuery(HOT_COMMENT);
    }

    /**
     * Injection point, first thing in the runnable that queries which friends reacted to a reel.
     * True makes it return without querying, so no bubbles are drawn. Never throws.
     */
    public static boolean skipSocialBubbles() {
        return skipFooterQuery(SOCIAL_BUBBLES);
    }

    private static boolean skipFooterQuery(String kind) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            FeedFilterCounters.sawList(FOOTER_ROUTE, 1);
            FeedFilterCounters.sawKind(FOOTER_ROUTE, kind);
            boolean skip = Utils.settingsReady() && Settings.HIDE_REEL_SOCIAL_FOOTER.get();
            if (skip) {
                FeedFilterCounters.removed(FOOTER_ROUTE, 1, kind);
                logOnce("footer " + kind, () -> "Reel footer: skipped the " + kind + " query");
            }
            return skip;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, kind + " query", failure);
            Logger.printException(() -> "Reel footer: could not read its switch", failure);
            return false;
        }
    }

    /**
     * A debug line the first time each kind of change happens, and only once the settings can be read
     * and Debug logging is on, so turning logging on later still gets the line. These hooks run on
     * every reel the viewer builds, and a line each time would bury the log.
     */
    private static void logOnce(String key, Logger.LogMessage message) {
        if (!Utils.settingsReady() || !BaseSettings.DEBUG.get()) return;
        if (LOGGED.add(key)) Logger.printDebug(message);
    }

    /** Forgets which lines were logged, so a test sees each one again. */
    static void forgetLoggedForTests() {
        LOGGED.clear();
    }
}
