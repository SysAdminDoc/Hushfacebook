/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.SwitchPreference;
import android.util.TypedValue;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * What a screen reader and a finger get from the settings screen: headings to jump between,
 * switches that say they're switches and whether they're on, a double tap that does what a tap
 * does, text that wraps in full at any size, and nothing to tap smaller than 48 dp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SettingsAccessibilityTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();

    @Before
    public void everyPatchIn() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        HushfacebookPreferenceFragment.failNextInitialization = null;
        PauseForTests.resume();
        Settings.HIDE_SPONSORED_POSTS.resetToDefault();
        controller.close();
    }

    @Test
    public void everySectionTitleIsAHeadingAndNothingElseIs() {
        int headings = 0;
        for (View row : rows(SettingsL10nTest.show(controller.get()))) {
            boolean section = item(row) instanceof PreferenceCategory;
            assertEquals(item(row) + " heading", section, node(row).isHeading());
            if (section) headings++;
        }
        assertTrue("no section on the screen", headings >= 5);
    }

    @Test
    public void aSwitchRowSaysItIsASwitchAndWhetherItIsOn() {
        View row = rowFor(SettingsL10nTest.show(controller.get()), Settings.HIDE_SPONSORED_POSTS.key);
        AccessibilityNodeInfo info = node(row);
        assertEquals(Switch.class.getName(), String.valueOf(info.getClassName()));
        assertTrue(info.isCheckable());
        assertTrue("the switch is on by default", info.isChecked());
        assertTrue("a double tap would do nothing", info.getActionList().contains(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
        assertTrue(info.isClickable());
        // The row's own delegate replaces the list's, so it has to keep what the list says of a
        // row: which item of the list it is.
        assertNotNull("the list's item info is gone", info.getCollectionItemInfo());
    }

    /**
     * The click event is what a screen reader answers a double tap with, so it has to carry the
     * role and the state the tap left, both ways.
     */
    @Test
    public void theClickEventCarriesTheStateTheTapLeft() {
        View row = rowFor(SettingsL10nTest.show(controller.get()), Settings.HIDE_SPONSORED_POSTS.key);
        SwitchPreference toggle = (SwitchPreference) item(row);
        for (boolean on : new boolean[]{true, false, true}) {
            toggle.setChecked(on);
            android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
            row.onInitializeAccessibilityEvent(event);
            assertEquals(Switch.class.getName(), String.valueOf(event.getClassName()));
            assertEquals("the event says " + event.isChecked() + " for a switch that's " + on, on, event.isChecked());
        }
    }

    /** The recovery page's rows wrap too: its title is the longest line on it in most languages. */
    @Test
    public void theRecoveryPageWrapsItsWholeTitle() {
        HushfacebookPreferenceFragment.failNextInitialization = new IllegalStateException("injected");
        List<View> rows = rows(SettingsL10nTest.show(controller.get()));
        assertEquals("the recovery page has a message and two actions", 3, rows.size());
        for (View row : rows) {
            TextView title = row.findViewById(android.R.id.title);
            assertEquals(item(row).getKey() + " title lines", Integer.MAX_VALUE, title.getMaxLines());
        }
    }

    /** A double tap goes through the list as a tap does, and the row then reads the new state. */
    @Test
    public void aDoubleTapTurnsTheSwitchAndTheRowSaysSo() {
        SettingsDialog dialog = SettingsL10nTest.show(controller.get());
        View row = rowFor(dialog, Settings.HIDE_SPONSORED_POSTS.key);
        assertTrue(Settings.HIDE_SPONSORED_POSTS.get());

        assertTrue("the list took no click", row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        ShadowLooper.idleMainLooper();
        assertFalse("the double tap didn't turn the switch off", Settings.HIDE_SPONSORED_POSTS.get());
        assertFalse(node(rowFor(dialog, Settings.HIDE_SPONSORED_POSTS.key)).isChecked());
    }

    @Test
    public void aRowATapActsOnIsAButtonAndARowOfTextIsNot() {
        SettingsDialog dialog = SettingsL10nTest.show(controller.get());
        for (View row : rows(dialog)) {
            Preference preference = item(row);
            AccessibilityNodeInfo info = node(row);
            if (preference instanceof PreferenceCategory || preference instanceof SwitchPreference) continue;
            if (preference.isSelectable()) {
                assertEquals(preference.getTitle() + " role", Button.class.getName(), String.valueOf(info.getClassName()));
                assertTrue(preference.getTitle() + " can't be double tapped", info.getActionList().contains(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
            } else {
                assertNotEquals(preference.getTitle() + " is only text", Button.class.getName(),
                        String.valueOf(info.getClassName()));
            }
        }
    }

    @Test
    public void thePausedCardIsAButtonThatTurnsHushfacebookBackOn() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        SettingsDialog dialog = SettingsL10nTest.show(controller.get());
        View card = rows(dialog).get(0);
        assertEquals(Button.class.getName(), String.valueOf(node(card).getClassName()));
        assertTrue(card.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        ShadowLooper.idleMainLooper();
        assertTrue(String.valueOf(item(card).getSummary()), String.valueOf(item(card).getSummary())
                .contains("turns back on"));
    }

    /**
     * Android draws a row's title on one line and cuts its summary at ten lines, which at 200%
     * text cut the ends off the longest summaries on this screen.
     */
    @Test
    public void everyRowWrapsItsWholeText() {
        for (View row : rows(SettingsL10nTest.show(controller.get()))) {
            TextView title = row.findViewById(android.R.id.title);
            assertNotNull(item(row) + " has no title view", title);
            assertEquals(item(row) + " title lines", Integer.MAX_VALUE, title.getMaxLines());
            TextView summary = row.findViewById(android.R.id.summary);
            if (summary != null && summary.getVisibility() == View.VISIBLE) {
                assertEquals(item(row) + " summary lines", Integer.MAX_VALUE, summary.getMaxLines());
            }
        }
    }

    @Test
    public void nothingToTapIsSmallerThan48dp() {
        SettingsDialog dialog = SettingsL10nTest.show(controller.get());
        int floor = dp(48);
        for (View row : rows(dialog)) {
            if (!node(row).isClickable()) continue;
            assertTrue(item(row).getTitle() + " is " + row.getMinimumHeight() + " px tall at least",
                    row.getMinimumHeight() >= floor);
        }
        TextView back = SettingsL10nTest.backOf(dialog);
        assertTrue("the back arrow's width floor is " + back.getMinWidth(), back.getMinWidth() >= floor);
        assertTrue("the back arrow's height floor is " + back.getMinHeight(), back.getMinHeight() >= floor);
        back.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertTrue("the back arrow measures " + back.getMeasuredWidth() + " by " + back.getMeasuredHeight(),
                back.getMeasuredWidth() >= floor && back.getMeasuredHeight() >= floor);
    }

    @Test
    public void theTitleIsAHeadingAndBackIsANamedButton() {
        SettingsDialog dialog = SettingsL10nTest.show(controller.get());
        TextView back = SettingsL10nTest.backOf(dialog);
        AccessibilityNodeInfo info = node(back);
        assertEquals(Button.class.getName(), String.valueOf(info.getClassName()));
        assertEquals("Back", String.valueOf(info.getContentDescription()));
        assertTrue(info.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
        assertEquals(SettingsDialog.BACK_ARROW, String.valueOf(back.getText()));

        View title = ((android.view.ViewGroup) back.getParent()).getChildAt(1);
        assertEquals("Hushfacebook", String.valueOf(((TextView) title).getText()));
        assertTrue("the screen's title isn't a heading", node(title).isHeading());
    }

    @Test
    @Config(qualifiers = "ar-rXB-ldrtl")
    public void inARightToLeftLanguageTheWayBackPointsRight() {
        TextView back = SettingsL10nTest.backOf(SettingsL10nTest.show(controller.get()));
        assertEquals(SettingsDialog.BACK_ARROW_RIGHT_TO_LEFT, String.valueOf(back.getText()));
    }

    /** Every row the list draws, laid out tall enough that none is left off. */
    private static List<View> rows(SettingsDialog dialog) {
        ListView list = dialog.getView().findViewById(android.R.id.list);
        assertNotNull("no list in the dialog", list);
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(40000, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1080, 40000);
        List<View> rows = new ArrayList<>();
        for (int index = 0; index < list.getChildCount(); index++) rows.add(list.getChildAt(index));
        assertEquals("the list left rows off", list.getAdapter().getCount(), rows.size());
        return rows;
    }

    private static View rowFor(SettingsDialog dialog, String key) {
        for (View row : rows(dialog)) {
            if (key.equals(item(row).getKey())) return row;
        }
        throw new AssertionError("no row keyed " + key);
    }

    private static Preference item(View row) {
        ListView list = (ListView) row.getParent();
        return (Preference) list.getItemAtPosition(list.getPositionForView(row));
    }

    private static AccessibilityNodeInfo node(View view) {
        return view.createAccessibilityNodeInfo();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                controller.get().getResources().getDisplayMetrics()));
    }
}
