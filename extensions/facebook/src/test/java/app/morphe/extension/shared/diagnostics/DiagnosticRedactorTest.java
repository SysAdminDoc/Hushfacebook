/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A person's name has no shape a pattern can find, and the toast that carries it is written to
 * the buffer in whichever language the phone speaks. The bundle marks the name instead, with the
 * isolate pair {@link #label(String)} wraps it in, and the redactor finds the marks.
 */
public class DiagnosticRedactorTest {

    private static final String FSI = "⁨";
    private static final String PDI = "⁩";

    /**
     * What Hushfeed's VideoAuthor.label() wraps a name in, kept here so the redactor's contract is
     * tested without that class: one isolate run, with breaks flattened and any marks inside the
     * name dropped, or the bare words when there is nobody to name.
     */
    private static String label(String name) {
        if (name == null || name.isEmpty()) return "this account";
        StringBuilder flat = new StringBuilder(name.length() + 2).append('⁨');
        for (int at = 0; at < name.length(); at++) {
            char character = name.charAt(at);
            if (character == '⁨' || character == '⁩') continue;
            flat.append(character == '\n' || character == '\r' ? ' ' : character);
        }
        return flat.append('⁩').toString();
    }

    @Test public void theToastLineLosesTheNameAndKeepsTheAction() {
        String line = DiagnosticRedactor.redact("Showing toast: Hid posts from " + FSI + "Dana Q" + PDI);

        assertEquals("Showing toast: Hid posts from [name omitted]", line);
    }

    @Test public void theNameIsFoundByItsMarksInAnyLanguage() {
        String german = DiagnosticRedactor.redact(
                "Showing toast: " + FSI + "Dana Q" + PDI + " wird nicht mehr angezeigt");

        assertFalse("a name in a translated toast survived: " + german, german.contains("Dana Q"));
        assertTrue("the words around the name were lost: " + german,
                german.contains("[name omitted] wird nicht mehr angezeigt"));
    }

    @Test public void aHandleOrAnIdInsideTheMarksGoesWithThem() {
        String handle = DiagnosticRedactor.redact("Hidden " + FSI + "@dana.q" + PDI + " locally");
        String vanity = DiagnosticRedactor.redact("Unfollowed " + FSI + "dana.q.1987" + PDI);

        assertEquals("Hidden [name omitted] locally", handle);
        assertEquals("Unfollowed [name omitted]", vanity);
    }

    @Test public void aNameThatLostItsClosingMarkIsCutToTheEndOfItsLine() {
        String text = DiagnosticRedactor.redact("Hidden " + FSI + "Dana Q locally\nnext line");

        assertEquals("Hidden [name omitted]\nnext line", text);
    }

    /**
     * A display name is whatever the account chose, so it can carry a line break or a copy of
     * the marks themselves. Either one used to split the run and leave the rest of the name in
     * the report. The label takes both out, so what arrives here is one run.
     */
    @Test public void aNameCarryingABreakOrTheMarksThemselvesIsStillOneRun() {
        String broken = label("Dana\nSecretHandle");
        String nested = label("A" + PDI + "B" + FSI + "C");

        assertEquals("Showing toast: Hid [name omitted]",
                DiagnosticRedactor.redact("Showing toast: Hid " + broken));
        assertEquals("Showing toast: Hid [name omitted]",
                DiagnosticRedactor.redact("Showing toast: Hid " + nested));
        assertFalse("half the name survived the break: " + broken,
                DiagnosticRedactor.redact(broken).contains("SecretHandle"));
        assertFalse("the middle of the name survived its own marks: " + nested,
                DiagnosticRedactor.redact(nested).contains("B"));
    }

    /**
     * The control for the pair above. Without the flattening the same two names leak, which is
     * what makes the flattening the thing being tested rather than the pattern.
     */
    @Test public void theSameTwoNamesLeakWhenTheyAreNotFlattenedFirst() {
        String rawBreak = FSI + "Dana\nSecretHandle" + PDI;
        String rawNested = FSI + "A" + PDI + "B" + FSI + "C" + PDI;

        assertTrue(DiagnosticRedactor.redact(rawBreak).contains("SecretHandle"));
        assertTrue(DiagnosticRedactor.redact(rawNested).contains("B"));
    }

    @Test public void anAccountWithNothingToNameIsNotHiddenAtAll() {
        // "this account" names nobody, so hiding it says less than the truth.
        assertEquals("Hid this account", DiagnosticRedactor.redact("Hid " + label(null)));
    }

    @Test public void aHandleOnItsOwnGoesWhileAnEmailAndAnObjectHashStay() {
        String text = DiagnosticRedactor.redact(
                "@dana.q wrote to dana@example.com from Foo@1a2b3c and mentioned @dana_q2");

        assertEquals("[handle omitted] wrote to dana@example.com from Foo@1a2b3c"
                + " and mentioned [handle omitted]", text);
    }

    @Test public void theStoryReferenceLineKeepsThePseudonymAndDropsThePostId() {
        String line = DiagnosticRedactor.redact(
                "Hidden story: author 3f9a2c1b0e7d story_fbid=1022345678901234567");

        assertTrue("the pseudonym is what makes two lines comparable: " + line,
                line.contains("author 3f9a2c1b0e7d"));
        assertFalse("the story id names what was read: " + line,
                line.contains("1022345678901234567"));
    }

    @Test public void facebookHostsAndSessionCookiesGo() {
        String line = DiagnosticRedactor.redact(
                "GET scontent-iad3-1.xx.fbcdn.net/v/t39.30808-6/1.jpg from b-graph.facebook.com"
                        + " cookie c_user=100012345678901; xs=12%3Aabc datr=Zx9");

        assertFalse("a CDN address survived: " + line, line.contains("fbcdn.net"));
        assertFalse("the graph host survived: " + line, line.contains("b-graph"));
        assertFalse("the account id cookie survived: " + line, line.contains("100012345678901"));
        assertFalse("the session cookie survived: " + line, line.contains("12%3Aabc"));
        assertFalse("the browser id cookie survived: " + line, line.contains("Zx9"));
    }

    @Test public void aBareAccountIdGoesAndATimestampStays() {
        String line = DiagnosticRedactor.redact("Hidden 100012345678901 at 1790000000000");

        assertEquals("Hidden [id omitted] at 1790000000000", line);
    }

    /**
     * A CDN file name joins its ids with underscores, and an underscore is a word character, so a
     * rule bounded by word edges never saw them. The middle number is the photo's own id.
     */
    @Test public void idsJoinedByUnderscoresInAFileNameGo() {
        String line = DiagnosticRedactor.redact(
                "saving image 475148478_1134540631592283_1316146539584337463_n.jpg and id1234567890123456x");

        assertEquals("saving image 475148478_[id omitted]_[id omitted]_n.jpg and id[id omitted]x", line);
    }

    /** The mutation control: a line with no name, handle or id is returned as it came. */
    @Test public void aLineWithNothingToHideIsLeftAlone() {
        String line = "Removed 3 of 12 feed units (maxsize=40, boxes=2) for author 3f9a2c1b0e7d";

        assertEquals(line, DiagnosticRedactor.redact(line));
    }
}
