/*
 * Forked from:
 * https://github.com/SysAdminDoc/hushfeed/blob/bcc57ee555f4346c4eb1cbdae9f6ca8a3fa6fef4/extensions/tiktok/src/test/java/app/morphe/extension/tiktok/settings/SettingsBackupTest.java
 * Copyright 2026 Hushfeed contributors (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: rewritten around the list of switches a file may
 * carry, the preview an import shows first, and the picker round trip through Hushfacebook's
 * settings dialog, the activity rebuilt behind the picker included.
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.SwitchPreference;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.shared.settings.preference.SharedPrefCategory;

/**
 * Export settings and Import settings: what a file may carry, what an import refuses, the preview
 * it shows first and the one commit it writes, and the round trip through Android's file picker.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SettingsBackupTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /**
     * Switches in {@link Settings} that are meant to stay out of a settings file, each with the
     * reason. None today. A switch that shows up in neither this nor the list fails the test
     * below, so it's a decision someone makes rather than one that happens.
     */
    private static final Map<String, String> STAYS_OUT = Collections.emptyMap();

    /** Hushfacebook's own state and its diagnostics. None of them is ever in a file. */
    private static List<Setting<?>> neverInAFile() {
        return Arrays.asList(BaseSettings.PAUSED, BaseSettings.SAFE_MODE, BaseSettings.DEBUG,
                BaseSettings.DEBUG_LOG_FILTERS, BaseSettings.DEBUG_STACKTRACE, BaseSettings.DEBUG_TOAST_ON_ERROR,
                BaseSettings.CAPTURE_JAVA_CRASHES, BaseSettings.MORPHE_LANGUAGE, BaseSettings.SHOW_MENU_ICONS,
                BaseSettings.FIRST_TIME_APP_LAUNCHED, BaseSettings.EXPERIMENTAL_APP_CONFIRMED,
                BaseSettings.CHECK_ENVIRONMENT_WARNINGS_ISSUED);
    }

    private static final String EXPORT_ROW = "action_export_settings";
    private static final String IMPORT_ROW = "action_import_settings";

    @Before
    public void startClean() {
        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) setting.resetToDefault();
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        ShadowToast.reset();
        ShadowAlertDialog.reset();
    }

    @After
    public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) setting.resetToDefault();
        BaseSettings.PAUSED.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        PauseForTests.resume();
        PatchFamily.inBuildForTests = null;
        AbstractPreferenceFragment.settingImportInProgress = false;
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    // ---- What a file carries ------------------------------------------------------------------

    /**
     * Every switch in Settings is either on the list or stays out with a reason. A new switch
     * fails here until someone decides which, so it can't slip into files, or out of them, unseen.
     */
    @Test
    public void theListIsEverySwitchInSettings() throws Exception {
        Set<String> switches = new TreeSet<>();
        for (BooleanSetting setting : PausedHooksTest.settingsSwitches()) switches.add(setting.key);
        Set<String> expected = new TreeSet<>(switches);
        expected.removeAll(STAYS_OUT.keySet());
        assertEquals("a switch in Settings has no decision: add it to SettingsBackup.ALLOWLIST, or to STAYS_OUT "
                + "here with the reason it stays out of a settings file", expected, keys(SettingsBackup.ALLOWLIST));
        assertTrue("STAYS_OUT names a switch Settings no longer has", switches.containsAll(STAYS_OUT.keySet()));
        assertEquals("the list names a switch twice", SettingsBackup.ALLOWLIST.size(),
                new HashSet<>(SettingsBackup.ALLOWLIST).size());
        // A file carries true or false. A setting of another kind in Settings needs a format that
        // can carry it before it can be decided on.
        for (Setting<?> setting : declaredSettings(Settings.class)) {
            assertTrue(setting.key + " isn't a switch, and a settings file carries only switches",
                    setting instanceof BooleanSetting);
        }
    }

    @Test
    public void nothingOfHushfacebooksOwnIsOnTheList() throws Exception {
        List<Setting<?>> own = declaredSettings(BaseSettings.class);
        assertTrue("the scan of BaseSettings found nothing", own.size() >= neverInAFile().size());
        for (Setting<?> setting : own) {
            assertFalse(setting.key + " is Hushfacebook's own and is on the list", SettingsBackup.ALLOWLIST.contains(setting));
        }
        for (Setting<?> setting : neverInAFile()) {
            assertFalse(setting.key + " is on the list", SettingsBackup.ALLOWLIST.contains(setting));
        }
    }

    /**
     * A file is a format name, a version and one true or false per switch, whatever else the
     * phone holds: not the pause, not debug logging or safe mode, not the log, the diagnostic
     * counts or anything that names the phone or the person.
     */
    @Test
    public void aFileIsItsFormatItsVersionAndOneValuePerSwitch() throws Exception {
        Settings.HIDE_PROMOTED_POSTS.save(false);
        Settings.DOWNLOAD_REELS.save(false);
        BaseSettings.PAUSED.save(true);
        BaseSettings.DEBUG.save(true);
        BaseSettings.DEBUG_LOG_FILTERS.save("downloads");
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        String cookie = "c_user=100012345678901; xs=secret-session";
        LogBufferManager.appendToLogBuffer("sentinel line " + cookie);
        HookStatus.invoked(FamilyNames.SPONSORED_POSTS);
        Context app = RuntimeEnvironment.getApplication();

        String text = SettingsBackup.create();
        JSONObject root = new JSONObject(text);
        assertEquals(new TreeSet<>(Arrays.asList("format", "schema", "settings")), names(root));
        assertEquals("hushfacebook-settings", root.get("format"));
        assertEquals(1, root.get("schema"));
        JSONObject switches = root.getJSONObject("settings");
        assertEquals(keys(SettingsBackup.ALLOWLIST), names(switches));
        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) {
            // Saved, not what a paused Facebook is answered: paused, every switch answers false.
            assertFalse(setting.get());
            assertEquals(setting.key, setting.savedValue(), switches.get(setting.key));
        }
        for (Setting<?> setting : Setting.allLoadedSettings()) {
            if (SettingsBackup.ALLOWLIST.contains(setting)) continue;
            assertFalse(setting.key + " is in the file", text.contains(setting.key));
        }
        for (String leak : new String[]{"sentinel", "c_user", "100012345678901", "secret-session",
                app.getPackageName(), FamilyNames.SPONSORED_POSTS, "downloads"}) {
            assertFalse("the file carries " + leak + ":\n" + text, text.contains(leak));
        }
    }

    @Test
    public void everySwitchRoundTripsBothWays() throws Exception {
        for (int pattern = 0; pattern < 2; pattern++) {
            Map<BooleanSetting, Boolean> chosen = new LinkedHashMap<>();
            int index = 0;
            for (BooleanSetting setting : SettingsBackup.ALLOWLIST) {
                boolean value = (index++ + pattern) % 2 == 0;
                setting.save(value);
                chosen.put(setting, value);
            }
            String file = SettingsBackup.create();
            for (BooleanSetting setting : SettingsBackup.ALLOWLIST) setting.save(!setting.savedValue());

            SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
            assertEquals(0, snapshot.unknown);
            assertEquals(SettingsBackup.ALLOWLIST.size(), snapshot.changes().size());
            assertEquals(SettingsBackup.ALLOWLIST.size(), SettingsBackup.apply(snapshot));
            for (Map.Entry<BooleanSetting, Boolean> entry : chosen.entrySet()) {
                BooleanSetting setting = entry.getKey();
                assertEquals(setting.key, entry.getValue(), setting.savedValue());
                // What the next start reads: a switch at its default is absent from the store.
                assertEquals(setting.key, entry.getValue(),
                        Setting.preferences.preferences.getBoolean(setting.key, setting.defaultValue));
            }
            assertEquals("a file read back is the file", file, SettingsBackup.create());
        }
    }

    // ---- Writing ---------------------------------------------------------------------------

    @Test
    public void anImportIsOneCommit() throws Exception {
        Settings.HIDE_SPONSORED_POSTS.save(false);
        String file = SettingsBackup.create();
        Settings.HIDE_SPONSORED_POSTS.save(true);
        Settings.OPEN_LINKS_EXTERNALLY.save(false);
        Settings.DOWNLOAD_STORIES.save(false);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file);
        assertEquals(3, snapshot.changes().size());

        Counts counts = new Counts();
        withStore(counting(Setting.preferences.preferences, counts, () -> false, false), () -> {
            assertEquals(3, SettingsBackup.apply(snapshot));
            return null;
        });
        assertEquals("editors opened", 1, counts.edits.get());
        assertEquals("commits", 1, counts.commits.get());
        assertEquals("asynchronous applies", 0, counts.applies.get());
        assertFalse(Settings.HIDE_SPONSORED_POSTS.savedValue());
        assertTrue(Settings.OPEN_LINKS_EXTERNALLY.savedValue());
        assertTrue(Settings.DOWNLOAD_STORIES.savedValue());

        // A file that changes nothing writes nothing.
        Counts none = new Counts();
        withStore(counting(Setting.preferences.preferences, none, () -> false, false), () -> {
            assertEquals(0, SettingsBackup.apply(SettingsBackup.parse(file)));
            return null;
        });
        assertEquals(0, none.edits.get());
    }

    @Test
    public void aFailedCommitIsPutBackAndSaysSo() throws Exception {
        String file = fileWith(Settings.HIDE_SPONSORED_POSTS, false, Settings.DOWNLOAD_REELS, false);
        Map<String, ?> before = store();
        Counts counts = new Counts();
        SettingsBackup.ApplyFailed failure = withStore(
                counting(Setting.preferences.preferences, counts, () -> counts.commits.get() == 1, false), () -> {
                    try {
                        SettingsBackup.apply(SettingsBackup.parse(file));
                        fail("a failed commit was reported as written");
                        return null;
                    } catch (SettingsBackup.ApplyFailed failed) {
                        return failed;
                    }
                });
        assertTrue("the switches are back, and the failure says they aren't", failure.rolledBack);
        assertEquals(before, store());
        assertTrue(Settings.HIDE_SPONSORED_POSTS.savedValue());
        assertTrue(Settings.DOWNLOAD_REELS.savedValue());
    }

    @Test
    public void aRollbackThatFailsTooSaysSomeSwitchesMayHaveChanged() throws Exception {
        String file = fileWith(Settings.HIDE_SPONSORED_POSTS, false);
        Counts counts = new Counts();
        // The first commit lands and reports failure; the one putting it back never lands.
        SettingsBackup.ApplyFailed failure = withStore(
                counting(Setting.preferences.preferences, counts, () -> true, true), () -> {
                    try {
                        SettingsBackup.apply(SettingsBackup.parse(file));
                        fail("a failed commit was reported as written");
                        return null;
                    } catch (SettingsBackup.ApplyFailed failed) {
                        return failed;
                    }
                });
        assertFalse("a store left changed was reported as put back", failure.rolledBack);
        assertEquals(2, counts.commits.get());
    }

    // ---- Refusals ------------------------------------------------------------------------------

    @Test
    public void aFileThatIsNotOneJsonObjectIsDamaged() throws Exception {
        String good = SettingsBackup.create();
        for (String bad : new String[]{good.substring(0, good.length() / 2), "[]", good + "{}", good + " x",
                "just some text", good.replace("\"schema\"", "schema"), good + '\0', ""}) {
            assertEquals(printable(bad), SettingsBackup.Reason.DAMAGED, reasonFor(bad));
        }
        // An editor's byte order mark isn't damage.
        assertEquals(0, SettingsBackup.parse((char) 0xFEFF + good).unknown);
    }

    @Test
    public void anOversizedFileIsRefusedWithoutBeingReadWhole() throws Exception {
        String good = SettingsBackup.create();
        int room = SettingsBackup.MAX_BYTES - good.getBytes(StandardCharsets.UTF_8).length;
        String full = good + repeat(' ', room);
        assertEquals("a file of exactly the limit is refused", SettingsBackup.MAX_BYTES,
                full.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(0, SettingsBackup.parse(full).unknown);
        assertEquals(SettingsBackup.Reason.SIZE, reasonFor(full + " "));

        byte[] atTheLimit = full.getBytes(StandardCharsets.UTF_8);
        assertEquals(full, SettingsBackup.read(new ByteArrayInputStream(atTheLimit)));
        byte[] over = (full + " ").getBytes(StandardCharsets.UTF_8);
        assertEquals(SettingsBackup.Reason.SIZE, readReason(new ByteArrayInputStream(over)));

        // A stream that never ends is given up on just past the limit, not read into memory.
        AtomicInteger pulled = new AtomicInteger();
        InputStream endless = new InputStream() {
            @Override public int read() {
                pulled.incrementAndGet();
                return ' ';
            }

            @Override public int read(byte[] buffer, int offset, int length) {
                Arrays.fill(buffer, offset, offset + length, (byte) ' ');
                pulled.addAndGet(length);
                return length;
            }
        };
        assertEquals(SettingsBackup.Reason.SIZE, readReason(endless));
        assertTrue("read " + pulled.get() + " bytes of an endless stream",
                pulled.get() <= SettingsBackup.MAX_BYTES + 8192);
    }

    @Test
    public void aNameGivenTwiceIsRefused() throws Exception {
        String key = Settings.HIDE_SPONSORED_POSTS.key;
        String twice = "{\"format\":\"hushfacebook-settings\",\"schema\":1,\"settings\":{\"" + key + "\":false,\""
                + key + "\":true}}";
        assertEquals(SettingsBackup.Reason.DUPLICATE, reasonFor(twice));
        assertEquals(SettingsBackup.Reason.DUPLICATE, reasonFor(
                "{\"format\":\"hushfacebook-settings\",\"format\":\"hushfacebook-settings\",\"schema\":1,\"settings\":{}}"));
        assertEquals("a name repeated inside a name this build doesn't know", SettingsBackup.Reason.DUPLICATE,
                reasonFor("{\"format\":\"hushfacebook-settings\",\"schema\":1,\"settings\":{\"later\":{\"a\":1,\"a\":2}}}"));
        // The same name in two different objects is two names.
        assertEquals(0, SettingsBackup.parse(
                "{\"format\":\"hushfacebook-settings\",\"schema\":1,\"settings\":{\"" + key + "\":false}}").unknown);
    }

    @Test
    public void aValueThatIsNotTrueOrFalseIsRefused() throws Exception {
        for (Object bad : new Object[]{"false", "true", 0, 1, JSONObject.NULL, new JSONObject(), new org.json.JSONArray(),
                1.5}) {
            JSONObject file = new JSONObject(SettingsBackup.create());
            file.getJSONObject("settings").put(Settings.DOWNLOAD_STORIES.key, bad);
            assertEquals(String.valueOf(bad), SettingsBackup.Reason.VALUE, reasonFor(file.toString()));
        }
    }

    @Test
    public void aFileFromANewerVersionIsRefused() throws Exception {
        JSONObject newer = new JSONObject(SettingsBackup.create()).put("schema", SettingsBackup.SCHEMA + 1);
        assertEquals(SettingsBackup.Reason.SCHEMA, reasonFor(newer.toString()));
        assertEquals(SettingsBackup.Reason.SCHEMA, reasonFor(new JSONObject(SettingsBackup.create()).put("schema", 99).toString()));
        // Anything else where the version goes was never written by Hushfacebook.
        String good = SettingsBackup.create();
        for (String version : new String[]{"0", "-1", "\"1\"", "1.0", "1.5", "4294967297", "null", "true", "[1]"}) {
            String bad = good.replace("\"schema\": 1", "\"schema\": " + version);
            assertTrue("the replacement missed", !bad.equals(good));
            assertEquals(version, SettingsBackup.Reason.FORMAT, reasonFor(bad));
        }
        JSONObject none = new JSONObject(good);
        none.remove("schema");
        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(none.toString()));
    }

    @Test
    public void aFileThatIsNotHushfacebooksIsRefused() throws Exception {
        String good = SettingsBackup.create();
        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(new JSONObject(good).put("format", "hushfeed-settings").toString()));
        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(new JSONObject(good).put("format", 1).toString()));
        JSONObject noFormat = new JSONObject(good);
        noFormat.remove("format");
        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(noFormat.toString()));
        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(new JSONObject(good).put("settings", "all of them").toString()));
        JSONObject noSettings = new JSONObject(good);
        noSettings.remove("settings");
        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(noSettings.toString()));
    }

    @Test
    public void bytesThatAreNotUtf8AreRefused() {
        assertEquals(SettingsBackup.Reason.ENCODING,
                readReason(new ByteArrayInputStream(new byte[]{'{', (byte) 0xC3, (byte) 0x28, '}'})));
        assertEquals(SettingsBackup.Reason.UNREADABLE, readReason(null));
        assertEquals(SettingsBackup.Reason.UNREADABLE, readReason(new InputStream() {
            @Override public int read() throws java.io.IOException {
                throw new java.io.IOException("content://provider/document/private-name.json went away");
            }
        }));
    }

    /**
     * A name this build doesn't know is counted for the preview and left alone, Pause and debug
     * logging included: naming them in a file changes neither.
     */
    @Test
    public void unknownNamesAreCountedAndLeftAlone() throws Exception {
        JSONObject file = new JSONObject(SettingsBackup.create()).put("exported_by", "a newer build");
        file.getJSONObject("settings")
                .put(Settings.HIDE_SPONSORED_REELS.key, false)
                .put(BaseSettings.PAUSED.key, true)
                .put(BaseSettings.DEBUG.key, true)
                .put("hushfacebook_kept_by_a_later_version", "whatever that version keeps here");
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(file.toString());
        assertEquals(4, snapshot.unknown);
        assertEquals(1, snapshot.changes().size());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertFalse(Settings.HIDE_SPONSORED_REELS.savedValue());
        assertFalse("a file paused Hushfacebook", BaseSettings.PAUSED.savedValue());
        assertFalse("a file turned on debug logging", BaseSettings.DEBUG.savedValue());
    }

    @Test
    public void aFileThatNamesSomeSwitchesLeavesTheRestAlone() throws Exception {
        Settings.OPEN_LINKS_EXTERNALLY.save(false);
        SettingsBackup.Snapshot snapshot = SettingsBackup.parse(fileWith(Settings.HIDE_SUGGESTED_POSTS, false));
        assertEquals(1, snapshot.values.size());
        assertEquals(1, SettingsBackup.apply(snapshot));
        assertFalse(Settings.HIDE_SUGGESTED_POSTS.savedValue());
        assertFalse("a switch the file didn't name was reset", Settings.OPEN_LINKS_EXTERNALLY.savedValue());
        assertTrue(Settings.HIDE_SPONSORED_POSTS.savedValue());
    }

    @Test
    public void aWaitingImportSurvivesAsABundle() throws Exception {
        JSONObject file = new JSONObject(fileWith(Settings.HIDE_PROMOTED_POSTS, false, Settings.DOWNLOAD_REELS, true));
        file.getJSONObject("settings").put("later_switch", true);
        SettingsBackup.Snapshot read = SettingsBackup.parse(file.toString());
        SettingsBackup.Snapshot back = SettingsBackup.Snapshot.fromBundle(read.toBundle());
        assertNotNull(back);
        assertEquals(read.values, back.values);
        assertEquals(1, back.unknown);

        // A saved state is read back as strictly as a file: only switches on the list, as true or false.
        Bundle state = read.toBundle();
        Bundle switches = state.getBundle("switches");
        switches.putBoolean(BaseSettings.PAUSED.key, true);
        switches.putString(Settings.HIDE_SPONSORED_POSTS.key, "false");
        SettingsBackup.Snapshot filtered = SettingsBackup.Snapshot.fromBundle(state);
        assertEquals(read.values, filtered.values);
        assertNull(SettingsBackup.Snapshot.fromBundle(null));
        assertNull(SettingsBackup.Snapshot.fromBundle(new Bundle()));
        Bundle negative = read.toBundle();
        negative.putInt("unknown", -1);
        assertNull(SettingsBackup.Snapshot.fromBundle(negative));
    }

    @Test
    public void everyRefusalHasItsOwnSentence() {
        Set<String> sentences = new HashSet<>();
        for (SettingsBackup.Reason reason : SettingsBackup.Reason.values()) {
            String sentence = SettingsBackupPreference.refusal(reason);
            assertTrue(reason + ": " + sentence, sentence.endsWith("Nothing was changed."));
            sentences.add(sentence);
        }
        assertEquals("two refusals read the same", SettingsBackup.Reason.values().length, sentences.size());
    }

    // ---- The screen and the picker ------------------------------------------------------------

    @Test
    public void exportWritesTheFileThroughTheSystemPicker() throws Exception {
        Settings.HIDE_PROMOTED_POSTS.save(false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            ShadowActivity.IntentForResult started = tap(activity, page, EXPORT_ROW);
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.getAction());
            assertEquals(SettingsBackupPreference.EXPORT, started.requestCode);
            assertEquals("application/json", started.intent.getType());
            assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE));
            String name = started.intent.getStringExtra(Intent.EXTRA_TITLE);
            assertTrue("the picker would suggest " + name, name.matches("hushfacebook-settings-\\d{8}-\\d{6}\\.json"));

            Uri uri = Uri.parse("content://settings-test/export.json");
            ByteArrayOutputStream written = new ByteArrayOutputStream();
            shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerOutputStream(uri, written);
            shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
            settle();

            assertEquals("Settings exported.", ShadowToast.getTextOfLatestToast());
            assertEquals(SettingsBackup.create(), new String(written.toByteArray(), StandardCharsets.UTF_8));
            assertTrue(page.findPreference(EXPORT_ROW).isEnabled());
        }
    }

    /** What Export writes is what Import takes back, through both pickers, every switch included. */
    @Test
    public void aFileExportedThroughThePickerImportsBackThroughIt() throws Exception {
        Settings.HIDE_SUGGESTED_POSTS.save(false);
        Settings.OPEN_LINKS_EXTERNALLY.save(false);
        Map<BooleanSetting, Boolean> exported = new LinkedHashMap<>();
        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) exported.put(setting, setting.savedValue());
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            Uri uri = Uri.parse("content://settings-test/round-trip.json");
            ByteArrayOutputStream written = new ByteArrayOutputStream();
            shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerOutputStream(uri, written);
            shadowOf(activity).receiveResult(tap(activity, page, EXPORT_ROW).intent, Activity.RESULT_OK,
                    new Intent().setData(uri));
            settle();

            for (BooleanSetting setting : SettingsBackup.ALLOWLIST) setting.save(!setting.savedValue());
            deliver(activity, tap(activity, page, IMPORT_ROW), new String(written.toByteArray(), StandardCharsets.UTF_8));
            AlertDialog preview = shownPreview();
            assertEquals(SettingsBackup.ALLOWLIST.size() + " switches will change.",
                    String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            for (Map.Entry<BooleanSetting, Boolean> entry : exported.entrySet()) {
                assertEquals(entry.getKey().key, entry.getValue(), entry.getKey().savedValue());
            }
        }
    }

    /** With nothing on the phone to answer either picker, both rows say so and stay usable. */
    @Test
    public void aPhoneWithNoFilePickerSaysSo() throws Exception {
        org.robolectric.shadows.ShadowApplication application = shadowOf(RuntimeEnvironment.getApplication());
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            application.checkActivities(true);
            for (String row : new String[]{EXPORT_ROW, IMPORT_ROW}) {
                ShadowToast.reset();
                click(page.findPreference(row));
                ShadowLooper.idleMainLooper();
                assertEquals(row, "This phone has no file picker, so there's no way to choose a file here.",
                        ShadowToast.getTextOfLatestToast());
                assertTrue(row + " stayed out of reach", page.findPreference(row).isEnabled());
            }
        } finally {
            application.checkActivities(false);
        }
    }

    @Test
    public void importShowsHowManySwitchesChangeAndWritesThemOnImport() throws Exception {
        JSONObject file = new JSONObject(fileWith(Settings.HIDE_SPONSORED_POSTS, false,
                Settings.HIDE_SPONSORED_STORIES, false, Settings.DOWNLOAD_STORIES, true));
        file.getJSONObject("settings").put("later_switch", true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            ShadowActivity.IntentForResult started = tap(activity, page, IMPORT_ROW);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.getAction());
            assertEquals(SettingsBackupPreference.IMPORT, started.requestCode);
            assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE));
            assertEquals("*/*", started.intent.getType());
            assertTrue(Arrays.asList(started.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)).contains("application/json"));

            Map<String, ?> before = store();
            deliver(activity, started, file.toString());
            AlertDialog preview = shownPreview();
            assertEquals("Import settings", String.valueOf(shadowOf(preview).getTitle()));
            assertEquals("2 switches will change.\n\n1 item in that file isn't a setting this version of "
                    + "Hushfacebook knows, so it'll be left out.", String.valueOf(shadowOf(preview).getMessage()));
            assertEquals("the preview wrote something", before, store());
            assertEquals("Import", String.valueOf(preview.getButton(AlertDialog.BUTTON_POSITIVE).getText()));
            assertEquals(activity.getString(android.R.string.cancel),
                    String.valueOf(preview.getButton(AlertDialog.BUTTON_NEGATIVE).getText()));

            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertEquals("Settings imported. 2 switches changed.", ShadowToast.getTextOfLatestToast());
            assertFalse(Settings.HIDE_SPONSORED_POSTS.savedValue());
            assertFalse(Settings.HIDE_SPONSORED_STORIES.savedValue());
            assertTrue(Settings.DOWNLOAD_STORIES.savedValue());
            assertFalse("the page still shows the switch on",
                    ((SwitchPreference) page.findPreference(Settings.HIDE_SPONSORED_POSTS.key)).isChecked());
            assertNull(page.pendingImport);
            assertFalse(AbstractPreferenceFragment.settingImportInProgress);
        }
    }

    @Test
    public void aFileThatChangesNothingSaysSoAndOffersNoImport() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            deliver(activity, tap(activity, page, IMPORT_ROW), SettingsBackup.create());
            AlertDialog preview = shownPreview();
            assertEquals("Your switches already match that file, so nothing will change.",
                    String.valueOf(shadowOf(preview).getMessage()));
            assertEquals(activity.getString(android.R.string.ok),
                    String.valueOf(preview.getButton(AlertDialog.BUTTON_POSITIVE).getText()));
            assertFalse(preview.getButton(AlertDialog.BUTTON_NEGATIVE).isShown());
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertNull(page.pendingImport);
        }
    }

    @Test
    public void cancellingThePreviewChangesNothing() throws Exception {
        String file = fileWith(Settings.OPEN_LINKS_EXTERNALLY, false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            Map<String, ?> before = store();

            deliver(activity, tap(activity, page, IMPORT_ROW), file);
            shownPreview().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            settle();
            assertNull(page.pendingImport);

            // Back, or a tap outside it, is a cancel too.
            deliver(activity, tap(activity, page, IMPORT_ROW), file);
            shownPreview().cancel();
            settle();
            assertNull(page.pendingImport);

            assertEquals(before, store());
            assertTrue(Settings.OPEN_LINKS_EXTERNALLY.savedValue());
            assertNull("a cancelled import said something", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test
    public void aCancelledPickerChangesNothingAndSaysNothing() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            for (String row : new String[]{EXPORT_ROW, IMPORT_ROW}) {
                shadowOf(activity).receiveResult(tap(activity, page, row).intent, Activity.RESULT_CANCELED, null);
                settle();
            }
            assertNull(ShadowToast.getTextOfLatestToast());
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            assertTrue(page.findPreference(IMPORT_ROW).isEnabled());
        }
    }

    /**
     * The picker is an activity of its own, and Android may rebuild Facebook's behind it, the
     * settings dialog and its page with it. The answer still reaches the rebuilt page: Android
     * finds it by the name the framework gave the page that asked, which the rebuilt one keeps.
     */
    @Test
    public void thePickersAnswerReachesThePageRebuiltBehindIt() throws Exception {
        String file = fileWith(Settings.DOWNLOAD_REELS, false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment asked = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            ShadowActivity.IntentForResult started = tap(controller.get(), asked, IMPORT_ROW);

            controller.recreate();
            ShadowLooper.idleMainLooper();
            Activity rebuilt = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(dialogOf(rebuilt));
            assertNotSame("the page wasn't rebuilt", asked, page);

            deliver(rebuilt, started, file);
            AlertDialog preview = shownPreview();
            assertEquals("1 switch will change.", String.valueOf(shadowOf(preview).getMessage()));
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertFalse(Settings.DOWNLOAD_REELS.savedValue());
            assertEquals("Settings imported. 1 switch changed.", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test
    public void anExportAnswerReachesThePageRebuiltBehindIt() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment asked = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            ShadowActivity.IntentForResult started = tap(controller.get(), asked, EXPORT_ROW);
            controller.recreate();
            ShadowLooper.idleMainLooper();

            Uri uri = Uri.parse("content://settings-test/rebuilt-export.json");
            ByteArrayOutputStream written = new ByteArrayOutputStream();
            shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerOutputStream(uri, written);
            shadowOf(controller.get()).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
            settle();
            assertEquals("Settings exported.", ShadowToast.getTextOfLatestToast());
            assertEquals(SettingsBackup.create(), new String(written.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    /**
     * A preview on screen when the activity is rebuilt comes back on the rebuilt page, still
     * unanswered, and the old one goes with the old window.
     */
    @Test
    public void thePreviewComesBackWhenThePageIsRebuilt() throws Exception {
        String file = fileWith(Settings.HIDE_SUGGESTED_POSTS, false, Settings.HIDE_PROMOTED_POSTS, false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
            deliver(controller.get(), tap(controller.get(), page, IMPORT_ROW), file);
            AlertDialog first = shownPreview();
            String message = String.valueOf(shadowOf(first).getMessage());
            assertEquals("2 switches will change.", message);

            controller.recreate();
            ShadowLooper.idleMainLooper();
            assertFalse("the old preview stayed up over a window that's gone", first.isShowing());
            AlertDialog again = shownPreview();
            assertNotSame(first, again);
            assertEquals(message, String.valueOf(shadowOf(again).getMessage()));
            assertTrue("the rebuild answered the preview", Settings.HIDE_SUGGESTED_POSTS.savedValue());

            again.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertFalse(Settings.HIDE_SUGGESTED_POSTS.savedValue());
            assertFalse(Settings.HIDE_PROMOTED_POSTS.savedValue());
            assertNull(SettingsL10nTest.pageOf(dialogOf(controller.get())).pendingImport);
        }
    }

    /**
     * Malformed, oversized, duplicate-name, wrong-type and newer-version files each say why and
     * change nothing: no preview, no write, the switches as they were.
     */
    @Test
    public void aRefusedFileSaysWhyAndChangesNothing() throws Exception {
        String good = SettingsBackup.create();
        String key = Settings.HIDE_SPONSORED_POSTS.key;
        JSONObject wrongType = new JSONObject(good);
        wrongType.getJSONObject("settings").put(key, "false");
        Map<String, SettingsBackup.Reason> files = new LinkedHashMap<>();
        files.put(good.substring(0, good.length() - 3), SettingsBackup.Reason.DAMAGED);
        files.put(good + repeat(' ', SettingsBackup.MAX_BYTES), SettingsBackup.Reason.SIZE);
        files.put(good.replace("\"" + key + "\": true", "\"" + key + "\": false, \"" + key + "\": false"),
                SettingsBackup.Reason.DUPLICATE);
        files.put(wrongType.toString(), SettingsBackup.Reason.VALUE);
        files.put(new JSONObject(good).put("schema", 2).toString(), SettingsBackup.Reason.SCHEMA);
        files.put(new JSONObject(good).put("format", "something else").toString(), SettingsBackup.Reason.FORMAT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            Map<String, ?> before = store();
            for (Map.Entry<String, SettingsBackup.Reason> entry : files.entrySet()) {
                ShadowToast.reset();
                ShadowAlertDialog.reset();
                Counts counts = new Counts();
                withStore(counting(Setting.preferences.preferences, counts, () -> false, false), () -> {
                    deliver(activity, tap(activity, page, IMPORT_ROW), entry.getKey());
                    return null;
                });
                assertEquals(entry.getValue() + " said", SettingsBackupPreference.refusal(entry.getValue()),
                        ShadowToast.getTextOfLatestToast());
                assertNull(entry.getValue() + " showed a preview", ShadowAlertDialog.getLatestAlertDialog());
                assertNull(page.pendingImport);
                assertEquals(entry.getValue() + " opened an editor", 0, counts.edits.get());
                assertEquals(entry.getValue() + " changed the store", before, store());
                assertTrue(page.findPreference(IMPORT_ROW).isEnabled());
            }
            assertTrue(Settings.HIDE_SPONSORED_POSTS.savedValue());
        }
    }

    /**
     * While a file is read, both rows are out of reach and the one acting says what it's doing,
     * which is also what a screen reader hears as its state. A second picker can't be opened.
     */
    @Test
    public void theRowsAreOutOfReachWhileAFileIsRead() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            Preference export = page.findPreference(EXPORT_ROW);
            Preference importRow = page.findPreference(IMPORT_ROW);
            String exportSummary = String.valueOf(export.getSummary());
            String importSummary = String.valueOf(importRow.getSummary());

            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch reading = new CountDownLatch(1);
            byte[] bytes = SettingsBackup.create().getBytes(StandardCharsets.UTF_8);
            InputStream slow = new InputStream() {
                private final InputStream inner = new ByteArrayInputStream(bytes);

                @Override public int read() throws java.io.IOException {
                    reading.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return inner.read();
                }
            };
            Uri uri = Uri.parse("content://settings-test/slow.json");
            shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerInputStream(uri, slow);
            ShadowActivity.IntentForResult started = tap(activity, page, IMPORT_ROW);
            shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
            try {
                assertTrue("the read never started", reading.await(5, TimeUnit.SECONDS));
                assertFalse(export.isEnabled());
                assertFalse(importRow.isEnabled());
                assertEquals("Reading the settings file", String.valueOf(importRow.getSummary()));
                assertEquals("a row that isn't acting changed its summary", exportSummary, String.valueOf(export.getSummary()));

                click(export);
                assertNull("a tap during a read opened a picker", shadowOf(activity).getNextStartedActivityForResult());
            } finally {
                release.countDown();
            }
            settle();
            assertTrue(export.isEnabled());
            assertTrue(importRow.isEnabled());
            assertEquals(importSummary, String.valueOf(importRow.getSummary()));
            shownPreview().cancel();
            settle();
        }
    }

    /** A full worker queue runs nothing, says so, and leaves the rows usable for another try. */
    @Test
    public void aFullWorkerQueueLeavesTheRowsUsable() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(activity));
            byte[] bytes = fileWith(Settings.HIDE_SPONSORED_POSTS, false).getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            Uri uri = Uri.parse("content://settings-test/queued.json");
            shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerInputStream(uri, input);
            ShadowActivity.IntentForResult started = tap(activity, page, IMPORT_ROW);

            CountDownLatch release = new CountDownLatch(1);
            // The tasks filling the queue, until each has run: the queue is full until they have.
            AtomicInteger filling = new AtomicInteger();
            try {
                while (true) {
                    filling.incrementAndGet();
                    boolean accepted = Utils.runOnBackgroundThread(() -> {
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } finally {
                            filling.decrementAndGet();
                        }
                    });
                    if (!accepted) {
                        filling.decrementAndGet();
                        break;
                    }
                    assertTrue("the worker queue never filled", filling.get() < 1000);
                }
                shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
                ShadowLooper.idleMainLooper();
                assertEquals("Couldn't start that. Try again in a moment.", ShadowToast.getTextOfLatestToast());
                assertEquals("the file was read anyway", bytes.length, input.available());
                assertTrue(page.findPreference(IMPORT_ROW).isEnabled());
                assertTrue(page.findPreference(EXPORT_ROW).isEnabled());
            } finally {
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (filling.get() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
            }
            assertEquals("the tasks filling the queue never finished", 0, filling.get());
            settle();
            // And it works once the queue has room.
            deliver(activity, tap(activity, page, IMPORT_ROW), fileWith(Settings.HIDE_SPONSORED_POSTS, false));
            shownPreview().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            assertFalse(Settings.HIDE_SPONSORED_POSTS.savedValue());
        }
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /** Taps a row and hands back the picker it opened. */
    private static ShadowActivity.IntentForResult tap(Activity activity, HushfacebookPreferenceFragment page, String row) {
        click(page.findPreference(row));
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(row + " opened no picker", started);
        return started;
    }

    private static void click(Preference row) {
        assertNotNull("no such row", row);
        row.getOnPreferenceClickListener().onPreferenceClick(row);
    }

    /** Answers a picker with a file holding [text], and waits for what that sets off. */
    private static void deliver(Activity activity, ShadowActivity.IntentForResult started, String text) throws Exception {
        Uri uri = Uri.parse("content://settings-test/" + System.nanoTime() + ".json");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        shadowOf(RuntimeEnvironment.getApplication().getContentResolver())
                .registerInputStreamSupplier(uri, () -> new ByteArrayInputStream(bytes));
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(uri));
        settle();
    }

    private static AlertDialog shownPreview() {
        AlertDialog preview = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no preview", preview);
        assertTrue("the preview isn't showing", preview.isShowing());
        return preview;
    }

    private static SettingsDialog dialogOf(Activity activity) {
        SettingsDialog dialog = (SettingsDialog) activity.getFragmentManager().findFragmentByTag("hushfacebook_settings");
        assertNotNull("the settings didn't come back", dialog);
        return dialog;
    }

    private static void settle() throws Exception {
        for (int round = 0; round < 3; round++) {
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
        }
    }

    private static SettingsBackup.Reason reasonFor(String text) {
        try {
            SettingsBackup.parse(text);
        } catch (SettingsBackup.Rejected refused) {
            return refused.reason;
        }
        throw new AssertionError("accepted: " + printable(text));
    }

    private static SettingsBackup.Reason readReason(InputStream input) {
        try {
            SettingsBackup.read(input);
        } catch (SettingsBackup.Rejected refused) {
            return refused.reason;
        }
        throw new AssertionError("read without a refusal");
    }

    /** A file naming only these switches, as [setting, value] pairs. */
    private static String fileWith(Object... pairs) throws Exception {
        JSONObject switches = new JSONObject();
        for (int index = 0; index < pairs.length; index += 2) {
            switches.put(((BooleanSetting) pairs[index]).key, pairs[index + 1]);
        }
        return new JSONObject().put("format", SettingsBackup.FORMAT).put("schema", SettingsBackup.SCHEMA)
                .put("settings", switches).toString();
    }

    private static Map<String, ?> store() {
        return new HashMap<>(Setting.preferences.preferences.getAll());
    }

    private static Set<String> keys(List<? extends Setting<?>> settings) {
        Set<String> keys = new TreeSet<>();
        for (Setting<?> setting : settings) keys.add(setting.key);
        return keys;
    }

    private static Set<String> names(JSONObject object) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> iterator = object.keys(); iterator.hasNext(); ) names.add(iterator.next());
        return names;
    }

    private static List<Setting<?>> declaredSettings(Class<?> owner) throws IllegalAccessException {
        List<Setting<?>> found = new ArrayList<>();
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Setting.class.isAssignableFrom(field.getType())) {
                found.add((Setting<?>) field.get(null));
            }
        }
        return found;
    }

    private static String repeat(char character, int count) {
        char[] run = new char[count];
        Arrays.fill(run, character);
        return new String(run);
    }

    private static String printable(String text) {
        String shown = text.length() > 80 ? text.substring(0, 80) + "..." : text;
        return shown.replace("\0", "\\0");
    }

    private static final class Counts {
        final AtomicInteger edits = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger applies = new AtomicInteger();
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    /** Runs [body] with Setting's store swapped for [store], and puts the real one back after. */
    private static <T> T withStore(SharedPreferences store, Body<T> body) throws Exception {
        Field field = SharedPrefCategory.class.getDeclaredField("preferences");
        field.setAccessible(true);
        Object original = field.get(Setting.preferences);
        field.set(Setting.preferences, store);
        try {
            return body.run();
        } finally {
            field.set(Setting.preferences, original);
        }
    }

    /**
     * [target], counting editors and their commits. While [fail] answers true, a commit reports
     * failure: after landing, or, with [landThenLose], the first lands and the rest never do.
     */
    private static SharedPreferences counting(SharedPreferences target, Counts counts, BooleanSupplier fail,
                                              boolean landThenLose) {
        return (SharedPreferences) Proxy.newProxyInstance(target.getClass().getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(target, args);
                    counts.edits.incrementAndGet();
                    SharedPreferences.Editor editor = target.edit();
                    return Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, call, values) -> {
                                if (call.getName().equals("apply")) {
                                    counts.applies.incrementAndGet();
                                    return call.invoke(editor, values);
                                }
                                if (call.getName().equals("commit")) {
                                    int commit = counts.commits.incrementAndGet();
                                    if (landThenLose && commit > 1 && fail.getAsBoolean()) return false;
                                    Object landed = call.invoke(editor, values);
                                    return !fail.getAsBoolean() && (Boolean) landed;
                                }
                                Object result = call.invoke(editor, values);
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
    }
}
