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

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Every patch in this source, and what Pause does to it.
 *
 * <p>Pause and safe mode work through the switches: while either is on, every switch answers off
 * and the hook behind it takes Facebook's own path. An edit made when you patched has no switch
 * to ask. A neutered method, a disabled manifest component, a button added to a sidebar and a
 * forced menu item all stay in until you patch again. Some patches are both, so each one says
 * which of its parts stay.
 *
 * <p>The settings screen and the diagnostic report read this list, so they can't disagree about
 * it. A family is found in this build by the name of its {@link SettingsStatus} method, the same
 * name the patch uses to switch that method on.
 */
public enum PatchFamily {
    SPONSORED_POSTS("Hide sponsored posts", "sponsoredPosts", null,
            Settings.HIDE_SPONSORED_POSTS, Settings.HIDE_PROMOTED_POSTS),
    SUGGESTED_POSTS("Hide suggested and promoted posts", "suggestedPosts", null,
            Settings.HIDE_SUGGESTED_POSTS),
    SPONSORED_STORIES("Hide sponsored stories", "sponsoredStories", null,
            Settings.HIDE_SPONSORED_STORIES),
    SPONSORED_REELS("Hide sponsored reels", "sponsoredReels", "the Reels banner and mid-roll ad block",
            Settings.HIDE_SPONSORED_REELS),
    EXTERNAL_BROWSER("Open links in external browser", "externalBrowser", null,
            Settings.OPEN_LINKS_EXTERNALLY),
    STORY_DOWNLOAD("Download any story", "storyDownload", "Save in every story's menu",
            Settings.DOWNLOAD_STORIES),
    REEL_DOWNLOAD("Download any reel", "reelDownload", "the Download button on reels"),
    AD_PREFETCH("Block background ad prefetch", "adPrefetch", "the background ad prefetch block"),
    AD_TELEMETRY("Block ad telemetry", "adTelemetry", "the ad telemetry block"),
    AUDIENCE_NETWORK("Disable Audience Network", "audienceNetwork", "the Audience Network block"),
    AMOLED_THEME("AMOLED black theme", "amoledTheme", "the AMOLED black theme"),
    RESTORE_TRUST("Restore screens on re-signed builds", "restoreTrust", "the re-signed build fix");

    /** The patch's name in Morphe Manager. */
    public final String patchName;

    /** The {@link SettingsStatus} method the patch switches on. */
    final String statusMethod;

    /** What of this patch stays in while Hushfacebook is paused, or null when nothing does. */
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
     * What of these families stays in while Hushfacebook is paused, as a sentence, or null when
     * a pause turns every one of them off.
     */
    @Nullable
    static String staysWhilePausedSummary(Set<PatchFamily> inBuild) {
        List<String> parts = new ArrayList<>();
        for (PatchFamily family : values()) {
            if (inBuild.contains(family) && family.staysWhilePaused != null) parts.add(family.staysWhilePaused);
        }
        if (parts.isEmpty()) return null;
        String list = joinAsSentence(parts);
        return Character.toUpperCase(list.charAt(0)) + list.substring(1) + (parts.size() == 1
                ? ". It was set when you patched, so Pause can't turn it off. To rule it out, patch again "
                        + "without the patch it comes from."
                : ". They were set when you patched, so Pause can't turn them off. To rule one out, patch "
                        + "again without the patch it comes from.");
    }

    /** "a", "a and b", "a, b and c". */
    private static String joinAsSentence(List<String> parts) {
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
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

    private String reportLine(boolean paused) {
        StringBuilder line = new StringBuilder(patchName).append(": ");
        if (switches.isEmpty()) {
            return line.append("no switch, stays in while paused: ").append(staysWhilePaused).toString();
        }
        line.append(paused ? "switch, paused so it answers off (saved " : "switch (");
        for (int i = 0; i < switches.size(); i++) {
            if (i > 0) line.append(", ");
            BooleanSetting setting = switches.get(i);
            line.append(setting.key).append(setting.savedValue() ? "=on" : "=off");
        }
        line.append(')');
        if (staysWhilePaused != null) line.append("; stays in while paused: ").append(staysWhilePaused);
        return line.toString();
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
