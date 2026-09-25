/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings;

/**
 * Pauses and resumes the process for a test in another package. {@link HushfacebookPause} keeps
 * its test hooks package-private, and the hooks a pause has to reach live in the Facebook packages.
 */
public final class PauseForTests {
    private PauseForTests() {
    }

    public static void pause(HushfacebookPause.Reason why) {
        HushfacebookPause.pauseForTests(why);
    }

    public static void resume() {
        HushfacebookPause.pauseForTests(HushfacebookPause.Reason.NONE);
    }

    /** Runs [probe] inside the next first-of-process pause decision, or nothing when null. */
    public static void whileDeciding(Runnable probe) {
        HushfacebookPause.whileDecidingForTests = probe;
    }
}
