/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * The hook state a report carries per family: how often Facebook reached a hook, what its
 * lookups found, and which lookups found nothing or more than one candidate.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class HookStatusTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    @After
    public void clear() {
        HookStatus.clear();
    }

    @Test
    public void aLineCountsRunsFindsAmbiguitiesAndMisses() {
        HookStatus.invoked("Reels");
        HookStatus.invoked("Reels");
        HookStatus.invoked("Reels");
        HookStatus.bound("Reels", "section item list");
        HookStatus.ambiguous("Reels", "field", "X.abc", "source", 2);
        HookStatus.missingMember("Reels", "field", "X.def", "items");

        // The miss that 'missing' counts is the one named, though the ambiguous lookup came first.
        assertEquals(Arrays.asList(
                "Reels: invoked 3, 1 found, 1 ambiguous, 1 missing. First missing: field X.def#items"),
                HookStatus.report());
        assertTrue("an ambiguous lookup is a finding a report has to carry", HookStatus.anyMissing());
    }

    /** With nothing missing, the lookup the line names is an ambiguous one, and it says so. */
    @Test
    public void aFamilyWhoseOnlyFindingIsAmbiguousDoesNotCallItMissing() {
        HookStatus.invoked("Download any reel");
        HookStatus.ambiguous("Download any reel", "field", "X.params", "com.facebook.video.engine.api.VideoDataSource", 2);

        assertEquals(Arrays.asList("Download any reel: invoked 1, 0 found, 1 ambiguous, 0 missing. First ambiguous: "
                        + "a single field X.params#com.facebook.video.engine.api.VideoDataSource (found 2)"),
                HookStatus.report());
    }

    /** A family that only ran has a line too, and a line with nothing wrong in it is no finding. */
    @Test
    public void aFamilyThatOnlyRanIsListedAndIsNoFinding() {
        HookStatus.invoked("Browser");
        assertEquals(Arrays.asList("Browser: invoked 1, 0 found, 0 missing"), HookStatus.report());
        assertEquals(false, HookStatus.anyMissing());
    }

    @Test
    public void anAmbiguityIsRecordedOnceAndKeptApartFromAMiss() {
        for (int i = 0; i < 5; i++) HookStatus.ambiguous("Reels", "field", "X.abc", "source", 2);
        HookStatus.missingMember("Reels", "field", "X.abc", "source");

        List<String> details = HookStatus.missing("Reels");
        assertEquals(details.toString(), 2, details.size());
        assertEquals("a single field X.abc#source (found 2)", details.get(0));
        assertEquals("field X.abc#source", details.get(1));
    }

    @Test
    public void runsCountedBeforeAClearComeBackAddedToTheOnesSince() {
        HookStatus.invoked("Feed");
        HookStatus.invoked("Feed");
        HookStatus.Snapshot before = HookStatus.snapshotAndClear();
        HookStatus.invoked("Feed");

        HookStatus.restore(before);
        assertEquals(Arrays.asList("Feed: invoked 3, 0 found, 0 missing"), HookStatus.report());
    }

    /** Hooks call it before their own guard, so nothing it is handed may throw into the host. */
    @Test
    public void countingARunNeverThrows() {
        HookStatus.invoked(null);
        HookStatus.invoked("");
        assertEquals(Arrays.asList(": invoked 1, 0 found, 0 missing"), HookStatus.report());
    }
}
