/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Every patch in this source, and what Pause does to it.
 *
 * <p>Pause and safe mode work through the switches: while either is on, every feature switch
 * answers off and the hook behind it takes Facebook's own path. The hook's code is still there,
 * only its answer changes, and Debug logging keeps its saved value. An edit made when you patched
 * has no switch to ask: a neutered method or a disabled manifest component stays in until you
 * patch again. The reel sidebar's Download button and the story menu's Save item were edits like
 * that until each asked its switch before it goes in, and the video menu's item asks its own the
 * same way. Some patches are both, so each one says which of its parts stay.
 *
 * <p>The settings screen and the diagnostic report read this list, so they can't disagree about
 * it. A family is found in this build by the name of its {@link SettingsStatus} method, the same
 * name the patch uses to switch that method on.
 */
public enum PatchFamily {
    SPONSORED_POSTS(FamilyNames.SPONSORED_POSTS, "sponsoredPosts", null,
            Settings.HIDE_SPONSORED_POSTS, Settings.HIDE_PROMOTED_POSTS),
    SUGGESTED_POSTS(FamilyNames.SUGGESTED_POSTS, "suggestedPosts", null,
            Settings.HIDE_SUGGESTED_POSTS, Settings.HIDE_SUGGESTED_FOR_YOU, Settings.HIDE_PEOPLE_YOU_MAY_KNOW),
    STORIES_TRAY(FamilyNames.STORIES_TRAY, "storiesTray", null,
            Settings.HIDE_STORIES_TRAY),
    FEED_REELS(FamilyNames.FEED_REELS, "feedReels", null,
            Settings.HIDE_FEED_REELS),
    RETURN_REFRESH(FamilyNames.RETURN_REFRESH, "returnRefresh", null,
            Settings.BLOCK_RETURN_REFRESH),
    AI_DETECTED_POSTS(FamilyNames.AI_DETECTED_POSTS, "aiDetectedPosts", null,
            Settings.HIDE_AI_DETECTED_POSTS),
    SPONSORED_STORIES(FamilyNames.SPONSORED_STORIES, "sponsoredStories", null,
            Settings.HIDE_SPONSORED_STORIES),
    SPONSORED_REELS(FamilyNames.SPONSORED_REELS, "sponsoredReels",
            "the part of the Reels ad block patched into the app",
            Settings.HIDE_SPONSORED_REELS),
    EXTERNAL_BROWSER(FamilyNames.EXTERNAL_BROWSER, "externalBrowser", null,
            Settings.OPEN_LINKS_EXTERNALLY),
    SANITIZE_SHARING_LINKS(FamilyNames.SANITIZE_SHARING_LINKS, "sanitizeSharingLinks", null,
            Settings.SANITIZE_SHARING_LINKS),
    STORY_DOWNLOAD(FamilyNames.STORY_DOWNLOAD, "storyDownload", null,
            Settings.DOWNLOAD_STORIES),
    REEL_DOWNLOAD(FamilyNames.REEL_DOWNLOAD, "reelDownload", null,
            Settings.DOWNLOAD_REELS),
    VIDEO_DOWNLOAD(FamilyNames.VIDEO_DOWNLOAD, "videoDownload", null,
            Settings.DOWNLOAD_VIDEOS),
    AD_PREFETCH(FamilyNames.AD_PREFETCH, "adPrefetch", "the background ad prefetch block"),
    AD_TELEMETRY(FamilyNames.AD_TELEMETRY, "adTelemetry", "the ad telemetry block"),
    AUDIENCE_NETWORK(FamilyNames.AUDIENCE_NETWORK, "audienceNetwork", "the Audience Network block"),
    AMOLED_THEME(FamilyNames.AMOLED_THEME, "amoledTheme", "the AMOLED black theme"),
    MATERIAL_YOU_THEME(FamilyNames.MATERIAL_YOU_THEME, "materialYouTheme", "the Material You theme"),
    RESTORE_TRUST(FamilyNames.RESTORE_TRUST, "restoreTrust", "the re-signed build fix");

    /** The patch's name in Morphe Manager. */
    public final String patchName;

    /** The {@link SettingsStatus} method the patch switches on. */
    final String statusMethod;

    /**
     * What of this patch stays in while Hushfacebook is paused, or null when nothing does. One
     * thing, never a plural: alone on the screen it's followed by "It was set when you patched".
     * The English is also a key of {@link L10n}: the screen shows it translated, and the report
     * keeps it in English.
     */
    @Nullable
    public final String staysWhilePaused;

    /** The switches Pause turns off for this patch. Empty when it has none. */
    public final List<BooleanSetting> switches;

    /** The families a test says this build carries, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Set<PatchFamily> inBuildForTests;

    PatchFamily(String patchName, String statusMethod, @Nullable String staysWhilePaused,
                BooleanSetting... switches) {
        this.patchName = patchName;
        this.statusMethod = statusMethod;
        this.staysWhilePaused = staysWhilePaused;
        this.switches = Collections.unmodifiableList(Arrays.asList(switches));
    }

    /** Whether this patch was selected for this build. */
    public boolean inBuild() {
        Set<PatchFamily> forced = inBuildForTests;
        if (forced != null) return forced.contains(this);
        try {
            return Boolean.TRUE.equals(SettingsStatus.class.getMethod(statusMethod).invoke(null));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logger.printException(() -> "Could not ask whether " + patchName + " is in this build", failure);
            return false;
        }
    }

    /** The families this build carries, in declaration order. */
    public static Set<PatchFamily> inThisBuild() {
        Set<PatchFamily> found = EnumSet.noneOf(PatchFamily.class);
        for (PatchFamily family : values()) {
            if (family.inBuild()) found.add(family);
        }
        return found;
    }

    /**
     * What of these families stays in while Hushfacebook is paused, as a sentence in the phone's
     * language, or null when a pause turns every one of them off. The list leads the sentence, so
     * its first letter is raised the way that language does it.
     */
    @Nullable
    static String staysWhilePausedSummary(Set<PatchFamily> inBuild) {
        List<String> parts = new ArrayList<>();
        for (PatchFamily family : values()) {
            if (inBuild.contains(family) && family.staysWhilePaused != null) parts.add(L10n.t(family.staysWhilePaused));
        }
        if (parts.isEmpty()) return null;
        return L10n.capitalize(L10n.quantity(parts.size(),
                "%1$s. It was set when you patched, so Pause can't turn it off. To rule it out, patch again "
                        + "without the patch it comes from.",
                "%1$s. They were set when you patched, so Pause can't turn them off. To rule one out, patch "
                        + "again without the patch it comes from.",
                L10n.join(parts)));
    }

    /**
     * One line per family in this build, saying whether a switch runs it, what the switch is set
     * to and what stays in while paused, then the families this build doesn't carry.
     */
    static List<String> reportLines(Set<PatchFamily> inBuild, boolean paused) {
        List<String> lines = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (PatchFamily family : values()) {
            if (inBuild.contains(family)) lines.add(family.reportLine(paused));
            else absent.add(family.patchName);
        }
        if (!absent.isEmpty()) lines.add("not in this build: " + String.join(", ", absent));
        return lines;
    }

    /**
     * "on", "disabled by its switch" or "disabled while paused", then the saved switches. A
     * family with two switches is on while either is: each hides its own kind of post.
     */
    private String reportLine(boolean paused) {
        StringBuilder line = new StringBuilder(patchName).append(": ");
        if (switches.isEmpty()) {
            return line.append("no switch, stays in while paused: ").append(staysWhilePaused).toString();
        }
        boolean anyOn = false;
        for (BooleanSetting setting : switches) anyOn |= setting.savedValue();
        line.append(paused ? "disabled while paused (saved " : anyOn ? "on (" : "disabled by its switch (");
        for (int i = 0; i < switches.size(); i++) {
            if (i > 0) line.append(", ");
            BooleanSetting setting = switches.get(i);
            line.append(setting.key).append(setting.savedValue() ? "=on" : "=off");
        }
        line.append(')');
        if (staysWhilePaused != null) line.append("; stays in while paused: ").append(staysWhilePaused);
        return line.toString();
    }

    /**
     * Registers the [PATCHES] report section, and tells Hook status which families no pause
     * reaches, so a paused export marks only the ones a switch runs. Registering twice keeps one.
     */
    public static void registerDiagnostics() {
        LogBufferManager.registerReportSection(REPORT);
        for (PatchFamily family : values()) {
            if (family.switches.isEmpty()) HookStatus.runsWhilePaused(family.patchName);
        }
    }

    /** The [PATCHES] section of the diagnostic report. */
    static final LogBufferManager.ReportSection REPORT = new LogBufferManager.ReportSection() {
        @Override
        public String title() {
            return "PATCHES";
        }

        @Override
        public List<String> lines() {
            return reportLines(inThisBuild(), HushfacebookPause.isPaused());
        }
    };
}
