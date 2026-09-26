/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;

import app.morphe.extension.shared.SettingsContextRule;

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
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SettingsEntryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final SettingsEntry.OpenWhenResumed watcher = new SettingsEntry.OpenWhenResumed();

    @Before public void watch() {
        RuntimeEnvironment.getApplication().registerActivityLifecycleCallbacks(watcher);
    }

    @After public void stopWatching() {
        RuntimeEnvironment.getApplication().unregisterActivityLifecycleCallbacks(watcher);
        HushfacebookPreferenceFragment.failNextInitialization = null;
    }

    /**
     * The launcher shortcut is labelled in the phone's language when it's first published, and a
     * phone that changes language gets it relabelled: one found under the old label is pushed
     * again. Kept as it was, it stayed in its first language for good.
     */
    @Test public void theShortcutFollowsThePhonesLanguage() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        org.junit.Assert.assertEquals("Hushfacebook settings", longLabel(manager));

        RuntimeEnvironment.setQualifiers("+de");
        SettingsEntry.publishShortcutNow(context);
        org.junit.Assert.assertEquals(app.morphe.extension.shared.L10nTablesForTests.of("de").get("Hushfacebook settings"),
                longLabel(manager));
        org.junit.Assert.assertEquals("one shortcut, relabelled, not two", 1, manager.getDynamicShortcuts().size());
    }

    /**
     * Facebook sets its own language after the application starts, so the label published then
     * is in the phone's. On a German phone with Facebook in English it stayed German next to an
     * English screen. The next Facebook screen to resume labels it again in Facebook's language.
     */
    @Test public void theShortcutIsLabelledAgainOnceFacebookHasSetItsLanguage() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        org.junit.Assert.assertEquals("Hushfacebook settings", longLabel(manager));

        RuntimeEnvironment.setQualifiers("+de");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
            org.junit.Assert.assertEquals(app.morphe.extension.shared.L10nTablesForTests.of("de").get("Hushfacebook settings"),
                    longLabel(manager));
            org.junit.Assert.assertEquals(1, manager.getDynamicShortcuts().size());
        }
    }

    private static String longLabel(android.content.pm.ShortcutManager manager) {
        for (android.content.pm.ShortcutInfo shortcut : manager.getDynamicShortcuts()) {
            if (SettingsEntry.SHORTCUT_ID.equals(shortcut.getId())) return String.valueOf(shortcut.getLongLabel());
        }
        return null;
    }

    /**
     * Facebook pushes its own shortcuts at rank 0, and the platform ranks the newest push first, so
     * the Hushfacebook one ended up last. A launcher that shows three or four of them, or two next
     * to a notification, cut it off (#2). Facebook's push still goes through, and then the
     * Hushfacebook shortcut goes back in front.
     */
    @Test public void facebooksOwnPushLeavesTheHushfacebookShortcutFirst() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        demote(context, manager, 3, "Hushfacebook settings");

        SettingsEntry.pushDynamicShortcut(manager, facebookShortcut(context, "notifications"));
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();

        assertNotNull("Facebook's own push was lost", shortcut(manager, "notifications"));
        org.junit.Assert.assertEquals("the Hushfacebook shortcut stayed behind Facebook's",
                0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
    }

    /** An update of Facebook's shortcuts can rank them again too, and its answer is Facebook's. */
    @Test public void facebooksUpdateKeepsItsAnswerAndTheHushfacebookShortcutFirst() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        manager.pushDynamicShortcut(facebookShortcut(context, "friends"));
        demote(context, manager, 1, "Hushfacebook settings");

        boolean updated = SettingsEntry.updateShortcuts(manager,
                java.util.Collections.singletonList(facebookShortcut(context, "friends")));
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();

        assertTrue("Facebook's update answered false", updated);
        org.junit.Assert.assertEquals(0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
    }

    /** A call that replaces every dynamic shortcut took the Hushfacebook one with it. */
    @Test public void facebookReplacingItsShortcutsPublishesTheHushfacebookOneAgain() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);

        boolean set = SettingsEntry.setDynamicShortcuts(manager,
                java.util.Collections.singletonList(facebookShortcut(context, "reels")));
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();

        assertTrue("Facebook's replacement answered false", set);
        assertNotNull("Facebook's own shortcut was lost", shortcut(manager, "reels"));
        android.content.pm.ShortcutInfo ours = shortcut(manager, SettingsEntry.SHORTCUT_ID);
        assertNotNull("the Hushfacebook shortcut stayed gone", ours);
        org.junit.Assert.assertEquals(0, ours.getRank());
        org.junit.Assert.assertEquals("Hushfacebook settings", String.valueOf(ours.getLongLabel()));
    }

    /** Each start checks the place as well as the label, so a shortcut left behind comes back first. */
    @Test public void theNextStartPutsAShortcutLeftBehindBackInFront() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        demote(context, manager, 2, "Hushfacebook settings");

        SettingsEntry.publishShortcutNow(context);

        org.junit.Assert.assertEquals(0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
        org.junit.Assert.assertEquals(1, manager.getDynamicShortcuts().size());
    }

    /**
     * The process Facebook pushes from may not have Facebook's language yet, so moving the shortcut
     * back keeps the label it has. Relabelled in the phone's language there, it flipped between
     * the two languages each time Facebook pushed.
     */
    @Test public void movingTheShortcutBackKeepsItsLabel() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        String german = app.morphe.extension.shared.L10nTablesForTests.of("de").get("Hushfacebook settings");
        demote(context, manager, 3, german);

        SettingsEntry.keepFirstNow(context);

        org.junit.Assert.assertEquals(german, longLabel(manager));
        org.junit.Assert.assertEquals(0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
    }

    /** What the platform leaves after Facebook's pushes rank ahead of the Hushfacebook shortcut. */
    private static void demote(android.content.Context context, android.content.pm.ShortcutManager manager,
                               int rank, String label) {
        manager.pushDynamicShortcut(new android.content.pm.ShortcutInfo.Builder(context, SettingsEntry.SHORTCUT_ID)
                .setShortLabel("Hushfacebook")
                .setLongLabel(label)
                .setIntent(new Intent(Intent.ACTION_VIEW))
                .setRank(rank)
                .build());
        org.junit.Assert.assertEquals(rank, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
    }

    private static android.content.pm.ShortcutInfo facebookShortcut(android.content.Context context, String id) {
        return new android.content.pm.ShortcutInfo.Builder(context, id)
                .setShortLabel(id)
                .setIntent(new Intent(Intent.ACTION_VIEW))
                .setRank(0)
                .build();
    }

    private static android.content.pm.ShortcutInfo shortcut(android.content.pm.ShortcutManager manager, String id) {
        for (android.content.pm.ShortcutInfo shortcut : manager.getDynamicShortcuts()) {
            if (id.equals(shortcut.getId())) return shortcut;
        }
        return null;
    }

    /**
     * Signed out, the shortcut's screen lands on the login screen, and Facebook replaces that with
     * its logged-out screen a moment later. Android resumes the replacement before it destroys the
     * login screen, so the replacement is already in front when the request comes back, and it
     * never resumes again to pick it up. On a phone that left the request pending until it expired.
     */
    @Test public void theScreenFollowsItsHostToAReplacementAlreadyInFront() {
        ActivityController<Activity> login = openedOverNewActivity();

        ActivityController<Activity> loggedOut = replace(login);

        assertNotNull("the screen was lost when its host went away", dialogOver(loggedOut.get()));
    }

    @Test public void aScreenThePersonClosedStaysClosedWhenItsHostGoesAway() {
        ActivityController<Activity> login = openedOverNewActivity();
        SettingsEntry.onClosedByUser();

        ActivityController<Activity> loggedOut = replace(login);

        assertNull("a screen the person closed came back", dialogOver(loggedOut.get()));
    }

    /**
     * The three ways a person leaves the screen (the title bar's arrow, the Back key and Back on the
     * recovery page) have to tell the entry so, or the screen follows its host to the next Facebook
     * screen as if nobody had closed it. The request is still inside its window here, so a close
     * the entry didn't hear about would reopen it.
     */
    @Test public void backOnTheRecoveryPageKeepsTheScreenClosedWhenItsHostGoesAway() {
        HushfacebookPreferenceFragment.failNextInitialization = new IllegalStateException("injected");
        ActivityController<Activity> login = openedOverNewActivity();
        android.preference.Preference back = pageOver(login.get()).findPreference("morphe_settings_error_back");
        assertNotNull("no recovery page", back);

        back.getOnPreferenceClickListener().onPreferenceClick(back);
        ShadowLooper.idleMainLooper();
        assertNull("Back left the screen open", dialogOver(login.get()));
        ActivityController<Activity> loggedOut = replace(login);

        assertNull("the screen came back after the person left it with Back", dialogOver(loggedOut.get()));
    }

    @Test public void theBackKeyKeepsTheScreenClosedWhenItsHostGoesAway() {
        ActivityController<Activity> login = openedOverNewActivity();

        ((SettingsDialog) dialogOver(login.get())).getDialog().onBackPressed();
        ShadowLooper.idleMainLooper();
        assertNull("the Back key left the screen open", dialogOver(login.get()));
        ActivityController<Activity> loggedOut = replace(login);

        assertNull("the screen came back after the person closed it with the Back key", dialogOver(loggedOut.get()));
    }

    /** The arrow dismisses rather than cancels, so it has to tell the entry itself. */
    @Test public void theTitleBarArrowKeepsTheScreenClosedWhenItsHostGoesAway() {
        ActivityController<Activity> login = openedOverNewActivity();
        SettingsDialog dialog = (SettingsDialog) dialogOver(login.get());
        ArrayList<View> labelled = new ArrayList<>();
        dialog.getView().findViewsWithText(labelled, "Back", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        View arrow = null;
        for (View view : labelled) {
            if (view instanceof TextView && "←".contentEquals(((TextView) view).getText())) arrow = view;
        }
        assertNotNull("no arrow in the title bar among " + labelled, arrow);

        assertTrue("the arrow didn't take the tap", arrow.performClick());
        ShadowLooper.idleMainLooper();
        assertNull("the arrow left the screen open", dialogOver(login.get()));
        ActivityController<Activity> loggedOut = replace(login);

        assertNull("the screen came back after the person closed it with the arrow", dialogOver(loggedOut.get()));
    }

    @SuppressWarnings("deprecation")
    private static HushfacebookPreferenceFragment pageOver(Activity activity) {
        SettingsDialog dialog = (SettingsDialog) dialogOver(activity);
        assertNotNull("no screen over " + activity, dialog);
        return (HushfacebookPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
    }

    /** An activity started by the launcher shortcut, with the screen open over it. */
    private static ActivityController<Activity> openedOverNewActivity() {
        Intent shortcut = new Intent().putExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, true);
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class, shortcut).create();
        SettingsEntry.onActivityCreate(activity.get());
        activity.start().resume();
        ShadowLooper.idleMainLooper();
        assertNotNull("the screen did not open over the shortcut's activity", dialogOver(activity.get()));
        return activity;
    }

    /** The order Android uses when an activity starts the next one and finishes. */
    private static ActivityController<Activity> replace(ActivityController<Activity> current) {
        current.pause();
        ActivityController<Activity> next = Robolectric.buildActivity(Activity.class).create().start().resume();
        current.stop().destroy();
        ShadowLooper.idleMainLooper();
        return next;
    }

    private static Object dialogOver(Activity activity) {
        return activity.getFragmentManager().findFragmentByTag("hushfacebook_settings");
    }
}
