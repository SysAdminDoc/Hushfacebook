/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.ContextThemeWrapper;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Set;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.ClearLogBufferPreference;
import app.morphe.extension.shared.settings.preference.ExportDiagnosticReportPreference;

/**
 * The preference list, built in code rather than from an XML resource so the bundle adds no
 * resources to Facebook. A switch appears only when its patch is in this build
 * ({@link PatchFamily}); a patch that works entirely at patch time gets a line saying so, and
 * what Pause can't reach is listed under the Pause switch. Switches are keyed by their setting,
 * which is how the shared fragment keeps them in sync with stored values.
 */
@SuppressWarnings("deprecation")
public final class HushfacebookPreferenceFragment extends AbstractPreferenceFragment {
    static final String SOURCE_URL = "https://github.com/SysAdminDoc/Hushfacebook";
    static final String STAYS_WHILE_PAUSED = "Stays in while paused";

    /** Thrown by the next initialize() and then cleared: how a test reaches the recovery page. */
    static volatile RuntimeException failNextInitialization;

    @Override
    protected void initialize() {
        RuntimeException fault = failNextInitialization;
        if (fault != null) {
            failNextInitialization = null;
            throw fault;
        }
        // Loads the switches before the shared fragment syncs them to the screen.
        Settings.HIDE_SPONSORED_POSTS.get();

        // Every row inflates with the theme of the context it was built with. Facebook's activity
        // theme is light, so its near-black primary text vanished on this screen's black background
        // on a phone (2026-09-24). The rows get a dark Material theme instead.
        Context context = themed(getContext());
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);

        screen.addPreference(statusCard(context));
        // The export row below reads these; registering twice keeps one.
        PatchFamily.registerDiagnostics();
        Set<PatchFamily> build = PatchFamily.inThisBuild();

        if (build.contains(PatchFamily.SPONSORED_POSTS) || build.contains(PatchFamily.SUGGESTED_POSTS)) {
            PreferenceCategory feed = category(screen, "News feed");
            if (build.contains(PatchFamily.SPONSORED_POSTS)) {
                feed.addPreference(toggle(context, Settings.HIDE_SPONSORED_POSTS, "Hide sponsored posts",
                        "Paid ads in the feed. They're dropped before Facebook adds them, so no gap is left."));
                feed.addPreference(toggle(context, Settings.HIDE_PROMOTED_POSTS, "Hide promoted posts",
                        "Posts Facebook files as promotions rather than as ads."));
            }
            if (build.contains(PatchFamily.SUGGESTED_POSTS)) {
                feed.addPreference(toggle(context, Settings.HIDE_SUGGESTED_POSTS, "Hide suggested and promoted units",
                        "\"Pages you may like\" and Facebook's own upsell cards. The in-feed surveys go too."));
            }
        }

        if (build.contains(PatchFamily.SPONSORED_STORIES) || build.contains(PatchFamily.STORY_DOWNLOAD)) {
            PreferenceCategory stories = category(screen, "Stories");
            if (build.contains(PatchFamily.SPONSORED_STORIES)) {
                stories.addPreference(toggle(context, Settings.HIDE_SPONSORED_STORIES, "Hide sponsored stories",
                        "Ad cards between the stories people posted."));
            }
            if (build.contains(PatchFamily.STORY_DOWNLOAD)) {
                stories.addPreference(toggle(context, Settings.DOWNLOAD_STORIES, "Save any story",
                        "Save in a story's menu downloads it at the best quality the player streams. "
                                + "Off or paused, Facebook's own save runs instead, and Save stays in the menu."));
            }
        }

        if (build.contains(PatchFamily.SPONSORED_REELS) || build.contains(PatchFamily.REEL_DOWNLOAD)) {
            PreferenceCategory reels = category(screen, "Reels and Watch");
            if (build.contains(PatchFamily.SPONSORED_REELS)) {
                reels.addPreference(toggle(context, Settings.HIDE_SPONSORED_REELS, "Hide sponsored reels",
                        "Ads that arrive inside a page of reels. Banners, mid-rolls and ads the app adds on its "
                                + "own stay blocked while the patch is in, whatever this switch or Pause says."));
            }
            if (build.contains(PatchFamily.REEL_DOWNLOAD)) {
                reels.addPreference(info(context, "Download button on reels",
                        "A Download button sits in the sidebar of every reel. It stays while Hushfacebook is paused."));
            }
        }

        if (build.contains(PatchFamily.EXTERNAL_BROWSER)) {
            PreferenceCategory links = category(screen, "Links");
            links.addPreference(toggle(context, Settings.OPEN_LINKS_EXTERNALLY, "Open links in your browser",
                    "Web links leave Facebook's in-app browser. Facebook's own pages still open in the app."));
        }

        if (build.contains(PatchFamily.AD_PREFETCH) || build.contains(PatchFamily.AD_TELEMETRY)
                || build.contains(PatchFamily.AUDIENCE_NETWORK) || build.contains(PatchFamily.AMOLED_THEME)
                || build.contains(PatchFamily.RESTORE_TRUST)) {
            PreferenceCategory patched = category(screen, "Set when you patched");
            if (build.contains(PatchFamily.AD_PREFETCH)) {
                patched.addPreference(info(context, "Background ad prefetch blocked",
                        "Facebook doesn't download ads or its ad model in the background."));
            }
            if (build.contains(PatchFamily.AD_TELEMETRY)) {
                patched.addPreference(info(context, "Ad telemetry blocked",
                        "No screenshot watching for ads, and no reports of which apps you install."));
            }
            if (build.contains(PatchFamily.AUDIENCE_NETWORK)) {
                patched.addPreference(info(context, "Audience Network off",
                        "Facebook doesn't serve ads to other apps on this phone."));
            }
            if (build.contains(PatchFamily.AMOLED_THEME)) {
                patched.addPreference(info(context, "AMOLED black theme",
                        "Dark mode draws black instead of dark grey. Turn on dark mode in Facebook to see it."));
            }
            if (build.contains(PatchFamily.RESTORE_TRUST)) {
                patched.addPreference(info(context, "Re-signed build fix",
                        "Profiles and some Settings pages open again on this re-signed build."));
            }
            patched.addPreference(info(context, "Changing these",
                    "They're chosen in Morphe Manager when you patch, and Pause doesn't turn them off. "
                            + "Patch again to change them."));
        }

        PreferenceCategory hushfacebook = category(screen, "Hushfacebook");
        hushfacebook.addPreference(toggle(context, BaseSettings.PAUSED, "Pause Hushfacebook",
                "From the next start, every switch above acts as if it were off, and Facebook's own code "
                        + "runs in its place. Debug logging keeps working, and your settings stay as they are."));
        String stays = PatchFamily.staysWhilePausedSummary(build);
        if (stays != null) hushfacebook.addPreference(info(context, STAYS_WHILE_PAUSED, stays));
        hushfacebook.addPreference(toggle(context, BaseSettings.DEBUG, "Debug logging",
                "Writes what each patch does to the Android log. Leave it off unless you're reporting a problem."));
        // Both rows come without a title of their own: Hushfeed's gave them one from string
        // resources that Facebook's APK doesn't have, and untitled they showed as blank rows.
        ExportDiagnosticReportPreference export = new ExportDiagnosticReportPreference(context);
        export.setTitle("Export diagnostic report");
        export.setSummary("Copy a short report, or save the full one to Download/Morphe. Links, account and "
                + "post ids, session cookies and names are left out.");
        hushfacebook.addPreference(export);
        ClearLogBufferPreference clear = new ClearLogBufferPreference(context);
        clear.setTitle("Clear diagnostic data");
        clear.setClearAndUndoSummaries("Empties the log and the filter counts a report would include.",
                "Diagnostic data cleared. Tap again to put it back.");
        hushfacebook.addPreference(clear);

        PreferenceCategory about = category(screen, "About");
        about.addPreference(info(context, "Version",
                "Hushfacebook " + Utils.getPatchesReleaseVersion() + " on Facebook " + Utils.getAppVersionName()));

        Preference source = new Preference(context);
        source.setTitle("Source code and issues");
        source.setSummary("github.com/SysAdminDoc/Hushfacebook");
        source.setPersistent(false);
        source.setOnPreferenceClickListener(p -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)));
            return true;
        });
        about.addPreference(source);

        // Section 7b asks that its notice reach the person using the software, and a file in the
        // repository does not reach them.
        Preference licenses = new Preference(context);
        licenses.setTitle("Licenses");
        licenses.setSummary("GPL-3.0, with the notices of the projects this is built on");
        licenses.setPersistent(false);
        licenses.setOnPreferenceClickListener(p -> {
            showNotice(context);
            return true;
        });
        about.addPreference(licenses);
    }

    /**
     * The first row: whether Hushfacebook is on, and why not when it isn't. Paused by safe mode or
     * the marker file, a tap turns it back on from the next start.
     */
    private Preference statusCard(Context context) {
        Preference card = new Preference(context);
        card.setPersistent(false);
        if (!HushfacebookPause.isPaused()) {
            card.setTitle("Hushfacebook is on");
            card.setSummary("Version " + Utils.getPatchesReleaseVersion()
                    + " for Facebook " + Utils.getAppVersionName());
            card.setSelectable(false);
            return card;
        }
        card.setTitle("Hushfacebook is paused");
        card.setSummary(pausedSummary(HushfacebookPause.reason(), context.getPackageName()) + " Tap to turn it back on.");
        card.setOnPreferenceClickListener(p -> {
            boolean markerGone = HushfacebookPause.turnBackOn(context);
            Preference pause = findPreference(BaseSettings.PAUSED.key);
            if (pause instanceof SwitchPreference) ((SwitchPreference) pause).setChecked(false);
            card.setSummary(markerGone
                    ? "Hushfacebook turns back on when Facebook restarts."
                    : "The file " + HushfacebookPause.MARKER_FILE_NAME + " couldn't be removed. Delete it "
                            + "from " + markerFolder(context.getPackageName()) + " to turn Hushfacebook back on.");
            return true;
        });
        return card;
    }

    /**
     * Why this start runs paused, what a pause does and doesn't reach, and that nothing the reader
     * saved has changed.
     */
    static String pausedSummary(HushfacebookPause.Reason reason, String packageName) {
        String why;
        switch (reason) {
            case CRASH_LOOP:
                why = "Facebook closed three times within a minute of starting, so Hushfacebook paused itself.";
                break;
            case MARKER_FILE:
                why = "A file named " + HushfacebookPause.MARKER_FILE_NAME + " in " + markerFolder(packageName)
                        + " paused Hushfacebook.";
                break;
            default:
                why = "You paused Hushfacebook.";
                break;
        }
        return why + " Every switch but Debug logging acts as if it were off, and what was set when you "
                + "patched stays in. Your settings stay as they are.";
    }

    /**
     * Where the marker file goes, the way a file manager shows it: the app's own files folder, not
     * the folder above it, which is the one a person finds first.
     */
    static String markerFolder(String packageName) {
        return "Android/data/" + packageName + "/files";
    }

    /** The dark Material theme every row on this screen is built with, over Facebook's own. */
    static Context themed(Context base) {
        return new ContextThemeWrapper(base, android.R.style.Theme_Material_NoActionBar);
    }

    /** The recovery page draws on the same black page, so it gets the same theme. */
    @Override
    protected Context pageContext(Activity activity) {
        return themed(activity);
    }

    private static PreferenceCategory category(PreferenceScreen screen, String title) {
        PreferenceCategory category = new PreferenceCategory(screen.getContext());
        category.setTitle(title);
        screen.addPreference(category);
        return category;
    }

    static SwitchPreference toggle(Context context, BooleanSetting setting, String title, String summary) {
        SwitchPreference preference = new SwitchPreference(context);
        preference.setKey(setting.key);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setSingleLineTitle(false);
        return preference;
    }

    private static Preference info(Context context, String title, String summary) {
        Preference preference = new Preference(context);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setSelectable(false);
        preference.setPersistent(false);
        preference.setSingleLineTitle(false);
        return preference;
    }

    private static void showNotice(Context context) {
        TextView text = new TextView(context);
        text.setText(LicenseNotice.TEXT);
        text.setTextIsSelectable(true);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(text);
        new AlertDialog.Builder(context)
                .setTitle("Licenses")
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    protected CharSequence initializationErrorTitle(@Nullable Context context) {
        return "Hushfacebook settings couldn't open";
    }
}
