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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.preference.TwoStatePreference;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Set;

import app.morphe.extension.facebook.download.SaveFolder;
import app.morphe.extension.shared.L10n;
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
 * which is how the shared fragment keeps them in sync with stored values. Every word is read from
 * {@link L10n} in the phone's language; the product names and the address stay as they are.
 */
@SuppressWarnings("deprecation")
public final class HushfacebookPreferenceFragment extends AbstractPreferenceFragment {
    static final String SOURCE_URL = "https://github.com/SysAdminDoc/Hushfacebook";
    /** The English of the row listing what Pause can't reach, and its key in {@link L10n}. */
    static final String STAYS_WHILE_PAUSED = "Stays in while paused";

    /** Thrown by the next initialize() and then cleared: how a test reaches the recovery page. */
    static volatile RuntimeException failNextInitialization;

    /** Where a settings file waiting on the person's answer is kept across the page being rebuilt. */
    static final String PENDING_IMPORT_STATE = "hushfacebook_pending_import";

    /**
     * A settings file that was read and waits on the person's answer, as
     * {@link SettingsBackup.Snapshot#toBundle()} wrote it, or null.
     */
    @Nullable
    Bundle pendingImport;

    /** The preview of {@link #pendingImport} while it's on screen. */
    @Nullable
    AlertDialog importPreview;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState != null) pendingImport = savedInstanceState.getBundle(PENDING_IMPORT_STATE);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ListView list = view.findViewById(android.R.id.list);
        if (list != null) {
            list.setDivider(null);
            list.setDividerHeight(0);
            ScreenColors colors = ScreenColors.shown;
            list.setBackgroundColor(colors == null ? Color.BLACK : colors.background);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        SettingsBackupPreference.onPageResumed(this);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingImport != null) outState.putBundle(PENDING_IMPORT_STATE, pendingImport);
    }

    @Override
    public void onDestroyView() {
        // The preview is drawn over this page's window. It stays unanswered, and the page that
        // replaces this one shows it again.
        SettingsBackupPreference.closePreview(this);
        super.onDestroyView();
    }

    /**
     * The file pickers' answers. Android finds this page by the name the framework gave it when
     * the picker was opened, and a page rebuilt while the picker was up is given the same name.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SettingsBackupPreference.onResult(this, requestCode, resultCode, data);
    }

    /** Shows what the switches are saved as, after an import wrote them. */
    void refreshSwitches() {
        updateUIToSettingValues();
    }

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
        // on a phone (2026-09-24). The rows get a dark Material theme instead, or with the Material
        // You theme in the build, the phone's dark or light one in its wallpaper colours.
        ScreenColors.shown = ScreenColors.forScreen(getContext());
        Context context = themed(getContext());
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);

        screen.addPreference(statusCard(context));
        // The export row below reads these; registering twice keeps one.
        PatchFamily.registerDiagnostics();
        Set<PatchFamily> build = PatchFamily.inThisBuild();

        if (build.contains(PatchFamily.SPONSORED_POSTS) || build.contains(PatchFamily.SUGGESTED_POSTS)
                || build.contains(PatchFamily.STORIES_TRAY) || build.contains(PatchFamily.FEED_REELS)
                || build.contains(PatchFamily.RETURN_REFRESH)
                || build.contains(PatchFamily.AI_DETECTED_POSTS)) {
            PreferenceCategory feed = category(screen, L10n.t("News feed"));
            if (build.contains(PatchFamily.SPONSORED_POSTS)) {
                feed.addPreference(toggle(context, Settings.HIDE_SPONSORED_POSTS, L10n.t("Hide sponsored posts"),
                        L10n.t("Paid ads in the feed. They're dropped before Facebook adds them, so no gap is left.")));
                feed.addPreference(toggle(context, Settings.HIDE_PROMOTED_POSTS, L10n.t("Hide promoted posts"),
                        L10n.t("Posts Facebook files as promotions rather than as ads.")));
            }
            if (build.contains(PatchFamily.SUGGESTED_POSTS)) {
                feed.addPreference(toggle(context, Settings.HIDE_SUGGESTED_POSTS,
                        L10n.t("Hide suggested and promoted units"),
                        L10n.t("\"Pages you may like\" and Facebook's own upsell cards. The in-feed surveys go too.")));
                feed.addPreference(toggle(context, Settings.HIDE_SUGGESTED_FOR_YOU,
                        L10n.t("Hide \"Suggested for you\" posts"),
                        L10n.t("Posts Facebook slips into your feed from people and pages you don't follow and groups you haven't joined.")));
                feed.addPreference(toggle(context, Settings.HIDE_PEOPLE_YOU_MAY_KNOW,
                        L10n.t("Hide \"People you may know\""),
                        L10n.t("The row of friend suggestions between posts.")));
            }
            if (build.contains(PatchFamily.STORIES_TRAY)) {
                feed.addPreference(toggle(context, Settings.HIDE_STORIES_TRAY, L10n.t("Hide the Stories tray"),
                        L10n.t("The row of stories at the top of the feed, Create story included.")));
            }
            if (build.contains(PatchFamily.FEED_REELS)) {
                feed.addPreference(toggle(context, Settings.HIDE_FEED_REELS, L10n.t("Hide Reels in the feed"),
                        L10n.t("The rows of reels between posts, and the reels Facebook adds where your feed ends.")));
            }
            if (build.contains(PatchFamily.RETURN_REFRESH)) {
                feed.addPreference(toggle(context, Settings.BLOCK_RETURN_REFRESH,
                        L10n.t("Keep feed position on return"),
                        L10n.t("Returning to Facebook within ten minutes keeps your place. Pull to refresh still works.")));
            }
            if (build.contains(PatchFamily.AI_DETECTED_POSTS)) {
                feed.addPreference(toggle(context, Settings.HIDE_AI_DETECTED_POSTS, L10n.t("Hide AI-detected posts"),
                        L10n.t("Posts that Facebook's own detection marked as made with AI. A post labeled only by "
                                + "the person who shared it stays. This one starts off because it hasn't been tried "
                                + "on a real feed yet.")));
            }
        }

        if (build.contains(PatchFamily.SPONSORED_STORIES) || build.contains(PatchFamily.STORY_DOWNLOAD)) {
            PreferenceCategory stories = category(screen, L10n.t("Stories"));
            if (build.contains(PatchFamily.SPONSORED_STORIES)) {
                stories.addPreference(toggle(context, Settings.HIDE_SPONSORED_STORIES, L10n.t("Hide sponsored stories"),
                        L10n.t("Ad cards between the stories people posted.")));
            }
            if (build.contains(PatchFamily.STORY_DOWNLOAD)) {
                stories.addPreference(toggle(context, Settings.DOWNLOAD_STORIES, L10n.t("Save any story"),
                        L10n.t("Adds Save to the menu of anyone's story, and saves at the best quality the "
                                + "player streams. Off or paused, only your own stories have Save, and it's "
                                + "Facebook's own.")));
            }
        }

        if (build.contains(PatchFamily.SPONSORED_REELS) || build.contains(PatchFamily.REEL_DOWNLOAD)) {
            PreferenceCategory reels = category(screen, L10n.t("Reels and Watch"));
            if (build.contains(PatchFamily.SPONSORED_REELS)) {
                reels.addPreference(toggle(context, Settings.HIDE_SPONSORED_REELS, L10n.t("Hide sponsored reels"),
                        L10n.t("Ads that arrive inside a page of reels. Banners and mid-rolls stay blocked while the "
                                + "patch is in, whatever this switch or Pause says. So do ads the app adds on its own.")));
            }
            if (build.contains(PatchFamily.REEL_DOWNLOAD)) {
                reels.addPreference(toggle(context, Settings.DOWNLOAD_REELS, L10n.t("Download button on reels"),
                        L10n.t("A Download button in the sidebar of every reel saves it at the best quality "
                                + "the player streams. Off or paused, reels show only Facebook's own buttons.")));
            }
        }

        if (build.contains(PatchFamily.STORY_DOWNLOAD) || build.contains(PatchFamily.REEL_DOWNLOAD)
                || build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
            PreferenceCategory downloads = category(screen, L10n.t("Downloads"));
            if (build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_VIDEOS, L10n.t("Download feed and Watch videos"),
                        L10n.t("Adds Download to phone to the menu of a video in the feed or in Watch, below "
                                + "Facebook's own items, and saves at the best quality the player streams. Off or "
                                + "paused, the menu is Facebook's own.")));
            }
            downloads.addPreference(folderRow(context));
        }

        if (build.contains(PatchFamily.EXTERNAL_BROWSER) || build.contains(PatchFamily.SANITIZE_SHARING_LINKS)) {
            PreferenceCategory links = category(screen, L10n.t("Links"));
            if (build.contains(PatchFamily.EXTERNAL_BROWSER)) {
                links.addPreference(toggle(context, Settings.OPEN_LINKS_EXTERNALLY, L10n.t("Open links in your browser"),
                        L10n.t("Web links leave Facebook's in-app browser. Facebook's own pages still open in the app.")));
            }
            if (build.contains(PatchFamily.SANITIZE_SHARING_LINKS)) {
                links.addPreference(toggle(context, Settings.SANITIZE_SHARING_LINKS,
                        L10n.t("Remove tracking from shared links"),
                        L10n.t("Takes tracking tags such as mibextid off the links you share or copy. A "
                                + "facebook.com/share/ link is made for one share, so Facebook can still trace it back to you.")));
            }
        }

        if (build.contains(PatchFamily.AD_PREFETCH) || build.contains(PatchFamily.AD_TELEMETRY)
                || build.contains(PatchFamily.AUDIENCE_NETWORK) || build.contains(PatchFamily.AMOLED_THEME)
                || build.contains(PatchFamily.MATERIAL_YOU_THEME) || build.contains(PatchFamily.RESTORE_TRUST)) {
            PreferenceCategory patched = category(screen, L10n.t("Set when you patched"));
            if (build.contains(PatchFamily.AD_PREFETCH)) {
                patched.addPreference(info(context, L10n.t("Background ad prefetch blocked"),
                        L10n.t("Facebook doesn't download ads or its ad model in the background.")));
            }
            if (build.contains(PatchFamily.AD_TELEMETRY)) {
                patched.addPreference(info(context, L10n.t("Ad telemetry blocked"),
                        L10n.t("No screenshot watching for ads, and no reports of which apps you install.")));
            }
            if (build.contains(PatchFamily.AUDIENCE_NETWORK)) {
                patched.addPreference(info(context, L10n.t("Audience Network off"),
                        L10n.t("Facebook doesn't serve ads to other apps on this phone.")));
            }
            if (build.contains(PatchFamily.AMOLED_THEME)) {
                patched.addPreference(info(context, L10n.t("AMOLED black theme"),
                        L10n.t("Dark mode draws black instead of dark grey. Turn on dark mode in Facebook to see it.")));
            }
            if (build.contains(PatchFamily.MATERIAL_YOU_THEME)) {
                patched.addPreference(info(context, L10n.t("Material You theme"),
                        L10n.t("Facebook's dark mode takes its colours from your wallpaper, and this screen does too. "
                                + "Android 11 has no wallpaper colours, so it gets a fixed blue palette. Turn on dark "
                                + "mode in Facebook to see it.")));
            }
            if (build.contains(PatchFamily.RESTORE_TRUST)) {
                patched.addPreference(info(context, L10n.t("Re-signed build fix"),
                        L10n.t("Profiles and some Settings pages open again on this re-signed build.")));
            }
            patched.addPreference(info(context, L10n.t("Changing these"),
                    L10n.t("They're chosen in Morphe Manager when you patch, and Pause doesn't turn them off. "
                            + "Patch again to change them.")));
        }

        PreferenceCategory hushfacebook = category(screen, "Hushfacebook");
        hushfacebook.addPreference(toggle(context, BaseSettings.PAUSED, L10n.t("Pause Hushfacebook"),
                L10n.t("From the next start, every switch above acts as if it were off, and Facebook's own code "
                        + "runs in its place. Debug logging keeps working, and your settings stay as they are.")));
        String stays = PatchFamily.staysWhilePausedSummary(build);
        if (stays != null) hushfacebook.addPreference(info(context, L10n.t(STAYS_WHILE_PAUSED), stays));
        // Morphe Manager can export the patch choices and the signing key, not these switches.
        hushfacebook.addPreference(new BackupRow(this, context, SettingsBackupPreference.EXPORT,
                L10n.t("Export settings"),
                L10n.t("Saves the switches and the save folder from the sections above to a file you choose. "
                        + "Pause and Debug logging stay out of it.")));
        hushfacebook.addPreference(new BackupRow(this, context, SettingsBackupPreference.IMPORT,
                L10n.t("Import settings"),
                L10n.t("Choose a settings file. You'll see how many switches it changes before anything does.")));
        hushfacebook.addPreference(toggle(context, BaseSettings.DEBUG, L10n.t("Debug logging"),
                L10n.t("Writes what each patch does to the Android log. Leave it off unless you're reporting a problem.")));
        // Both rows come without a title of their own: Hushfeed's gave them one from string
        // resources that Facebook's APK doesn't have, and untitled they showed as blank rows.
        ExportDiagnosticReportPreference export = new ExportRow(context);
        export.setTitle(L10n.t("Export diagnostic report"));
        export.setSummary(L10n.t("Copy a short report, or save the full one to Download/Morphe. Links, account and "
                + "post ids, session cookies and names are left out."));
        hushfacebook.addPreference(export);
        ClearLogBufferPreference clear = new ClearRow(context);
        clear.setTitle(L10n.t("Clear diagnostic data"));
        clear.setClearAndUndoSummaries(L10n.t("Empties the log and the filter counts a report would include."),
                L10n.t("Diagnostic data cleared. Tap again to put it back."));
        hushfacebook.addPreference(clear);

        PreferenceCategory about = category(screen, L10n.t("About"));
        about.addPreference(info(context, L10n.t("Version"), L10n.f("Hushfacebook %1$s on Facebook %2$s",
                L10n.isolate(Utils.getPatchesReleaseVersion()), L10n.isolate(Utils.getAppVersionName()))));

        Preference source = new Row(context);
        source.setTitle(L10n.t("Source code and issues"));
        source.setSummary("github.com/SysAdminDoc/Hushfacebook");
        source.setPersistent(false);
        source.setOnPreferenceClickListener(p -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)));
            return true;
        });
        about.addPreference(source);

        // Section 7b asks that its notice reach the person using the software, and a file in the
        // repository does not reach them.
        Preference licenses = new Row(context);
        licenses.setTitle(L10n.t("Licenses"));
        licenses.setSummary(L10n.t("GPL-3.0, with the notices of the projects this is built on"));
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
        Preference card = new Row(context);
        card.setPersistent(false);
        ScreenColors colors = ScreenColors.shown;
        boolean paused = HushfacebookPause.isPaused();
        int fill = paused
                ? (colors == null ? 0xFF858D9C : colors.switchOff)
                : (colors == null ? 0xFF1769E0 : colors.accent);
        int diameter = Math.round(40 * context.getResources().getDisplayMetrics().density);
        Bitmap mark = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mark);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(fill);
        canvas.drawCircle(diameter / 2f, diameter / 2f, diameter / 2f, paint);
        if (!paused) {
            paint.setColor(colors != null && !colors.light ? colors.background : Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(diameter / 10f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            Path check = new Path();
            check.moveTo(diameter * .24f, diameter * .52f);
            check.lineTo(diameter * .43f, diameter * .70f);
            check.lineTo(diameter * .77f, diameter * .32f);
            canvas.drawPath(check, paint);
        }
        card.setIcon(new BitmapDrawable(context.getResources(), mark));
        if (!paused) {
            card.setTitle(L10n.t("Hushfacebook is on"));
            card.setSummary(L10n.f("Version %1$s for Facebook %2$s",
                    L10n.isolate(Utils.getPatchesReleaseVersion()), L10n.isolate(Utils.getAppVersionName())));
            card.setSelectable(false);
            return card;
        }
        card.setTitle(L10n.t("Hushfacebook is paused"));
        card.setSummary(pausedSummary(HushfacebookPause.reason(), context.getPackageName())
                + " " + L10n.t("Tap to turn it back on."));
        card.setOnPreferenceClickListener(p -> {
            boolean markerGone = HushfacebookPause.turnBackOn(context);
            Preference pause = findPreference(BaseSettings.PAUSED.key);
            if (pause instanceof SwitchPreference) ((SwitchPreference) pause).setChecked(false);
            card.setSummary(markerGone
                    ? L10n.t("Hushfacebook turns back on when Facebook restarts.")
                    : L10n.f("The file %1$s couldn't be removed. Delete it from %2$s to turn Hushfacebook back on.",
                            L10n.isolate(HushfacebookPause.MARKER_FILE_NAME),
                            L10n.isolate(markerFolder(context.getPackageName()))));
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
                why = L10n.t("Facebook closed three times within a minute of starting, so Hushfacebook paused itself.");
                break;
            case MARKER_FILE:
                why = L10n.f("A file named %1$s in %2$s paused Hushfacebook.",
                        L10n.isolate(HushfacebookPause.MARKER_FILE_NAME), L10n.isolate(markerFolder(packageName)));
                break;
            default:
                why = L10n.t("You paused Hushfacebook.");
                break;
        }
        return why + " " + L10n.t("Every switch but Debug logging acts as if it were off, and what was set when you "
                + "patched stays in. Your settings stay as they are.");
    }

    /**
     * Where the marker file goes, the way a file manager shows it: the app's own files folder, not
     * the folder above it, which is the one a person finds first.
     */
    static String markerFolder(String packageName) {
        return "Android/data/" + packageName + "/files";
    }

    /**
     * The theme every row and dialog on this screen is built with, over Facebook's own: dark
     * Material, or with the Material You theme in the build, the phone's dark or light setting.
     */
    static Context themed(Context base) {
        return new ContextThemeWrapper(base, ScreenColors.themeFor(base));
    }

    /** The recovery page draws on the same page as the rows, so it gets the same theme. */
    @Override
    protected Context pageContext(Activity activity) {
        return themed(activity);
    }

    private static PreferenceCategory category(PreferenceScreen screen, String title) {
        PreferenceCategory category = new Heading(screen.getContext());
        category.setTitle(title);
        screen.addPreference(category);
        return category;
    }

    static SwitchPreference toggle(Context context, BooleanSetting setting, String title, String summary) {
        SwitchPreference preference = new Toggle(context);
        preference.setKey(setting.key);
        preference.setTitle(title);
        preference.setSummary(summary);
        return preference;
    }

    /**
     * The folder every save goes to. What's typed is cleaned before it's kept, so the row, the
     * setting and the next save all show the one folder name the save will use.
     */
    static FolderRow folderRow(Context context) {
        FolderRow row = new FolderRow(context);
        row.setKey(Settings.SAVE_FOLDER.key);
        row.setTitle(L10n.t("Save folder"));
        row.setDialogTitle(L10n.t("Save folder"));
        row.setDialogMessage(L10n.f("One folder name for your saves. Slashes and other characters a folder name "
                + "can't hold become underscores. Leave it empty to use %1$s.", L10n.isolate(SaveFolder.DEFAULT)));
        EditText field = row.getEditText();
        field.setSingleLine(true);
        field.setHint(SaveFolder.DEFAULT);
        row.setText(Settings.SAVE_FOLDER.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String clean = SaveFolder.sanitize(raw);
            if (clean.equals(raw)) return true;
            // Keeps the clean name in place of what was typed. The store changes, and the shared
            // page reads the setting from the row as it does for any change.
            ((FolderRow) preference).setText(clean);
            return false;
        });
        return row;
    }

    /** "Videos go to Movies/Clips and photos to Pictures/Clips." for the folder [leaf]. */
    static String folderSummary(String leaf) {
        String videos = Environment.DIRECTORY_MOVIES + "/" + leaf;
        String photos = Environment.DIRECTORY_PICTURES + "/" + leaf;
        return L10n.f("Videos go to %1$s and photos to %2$s.", L10n.isolate(videos), L10n.isolate(photos));
    }

    private static Preference info(Context context, String title, String summary) {
        Preference preference = new Row(context);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setSelectable(false);
        preference.setPersistent(false);
        return preference;
    }

    /**
     * Lets a row's title and summary wrap to as many lines as they need. Android draws a row's
     * title on one line and cuts its summary at ten, and at 200% text size the paused card, the
     * Pause row and the list of what Pause can't reach ran past ten lines and lost their ends.
     */
    static void showAllText(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) summary.setMaxLines(Integer.MAX_VALUE);
    }

    /** A section title, which a screen reader announces as a heading so a reader can jump between sections. */
    static final class Heading extends PreferenceCategory {
        Heading(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            view.setAccessibilityHeading(true);
            showAllText(view);
            ScreenColors.heading(view);
        }
    }

    /** A row of text, which a screen reader calls a button when a tap does something. */
    static final class Row extends Preference {
        Row(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(isSelectable() ? new RowSemantics(this, Button.class) : null);
        }
    }

    /** A switch row, which a screen reader calls a switch and reads as on or off. */
    static final class Toggle extends SwitchPreference {
        Toggle(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Switch.class));
        }
    }

    /**
     * The save folder's row. Its summary follows its text, whoever sets it: the person, the shared
     * page syncing it from the setting, or an import.
     */
    static final class FolderRow extends EditTextPreference {
        FolderRow(Context context) {
            super(context);
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(folderSummary(SaveFolder.sanitize(text)));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its edit dialog takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    static final class ExportRow extends ExportDiagnosticReportPreference {
        ExportRow(Context context) {
            super(context);
        }

        @Override
        protected void onDialogShown(AlertDialog dialog) {
            ScreenColors.dialog(dialog);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }
    }

    static final class ClearRow extends ClearLogBufferPreference {
        ClearRow(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }
    }

    /** Export settings or Import settings. */
    static final class BackupRow extends SettingsBackupPreference {
        BackupRow(HushfacebookPreferenceFragment page, Context context, int action, String title, String summary) {
            super(page, context, action, title, summary);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }
    }

    /**
     * What a screen reader hears about a row a tap acts on. The list itself gives such a row its
     * place and a click action and no role, and the switch drawn in a switch row can't take
     * focus, so on its own a switch row said neither that it was a switch nor whether it was on.
     * A double tap goes through the list, the way a tap does, so the preference's own click runs.
     */
    static final class RowSemantics extends View.AccessibilityDelegate {
        private final Preference preference;
        private final Class<? extends View> role;

        RowSemantics(Preference preference, Class<? extends View> role) {
            this.preference = preference;
            this.role = role;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            AbsListView list = listOf(host);
            int position = list == null ? AdapterView.INVALID_POSITION : list.getPositionForView(host);
            if (position != AdapterView.INVALID_POSITION) {
                list.onInitializeAccessibilityNodeInfoForItem(host, position, info);
            }
            info.setClassName(role.getName());
            if (preference instanceof TwoStatePreference) {
                info.setCheckable(true);
                info.setChecked(((TwoStatePreference) preference).isChecked());
            }
            if (preference.isEnabled() && !info.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) {
                info.setClickable(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            }
        }

        @Override
        public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(host, event);
            // The click event is what a screen reader answers a double tap with, so it carries the
            // state the tap left.
            event.setClassName(role.getName());
            if (preference instanceof TwoStatePreference) {
                event.setChecked(((TwoStatePreference) preference).isChecked());
            }
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            AbsListView list = listOf(host);
            if (action == AccessibilityNodeInfo.ACTION_CLICK && list != null && preference.isEnabled()) {
                int position = list.getPositionForView(host);
                if (position != AdapterView.INVALID_POSITION) {
                    return list.performItemClick(host, position, list.getItemIdAtPosition(position));
                }
            }
            return super.performAccessibilityAction(host, action, arguments);
        }

        @Nullable
        private static AbsListView listOf(View row) {
            return row.getParent() instanceof AbsListView ? (AbsListView) row.getParent() : null;
        }
    }

    /**
     * The notice itself stays in English, as the licence texts it carries are: a translation of
     * the GPL is not the licence.
     */
    private static void showNotice(Context context) {
        TextView text = new TextView(context);
        text.setText(LicenseNotice.TEXT);
        text.setTextIsSelectable(true);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(text);
        ScreenColors colors = ScreenColors.shown;
        if (colors != null) text.setTextColor(colors.summary);
        ScreenColors.dialog(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Licenses"))
                .setView(scroll)
                .setPositiveButton(L10n.t("OK"), null)
                .show());
    }

    @Override
    protected CharSequence initializationErrorTitle(@Nullable Context context) {
        return L10n.t(context, "Hushfacebook settings couldn't open");
    }
}
