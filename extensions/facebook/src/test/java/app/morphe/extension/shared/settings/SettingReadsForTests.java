/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings;

/**
 * Breaks a switch's reads for a test in another package, which stands in for anything that can
 * throw inside a hook once it has counted the call. {@link Setting} keeps its value protected, and
 * the hooks that read a switch live in the Facebook packages.
 */
public final class SettingReadsForTests {
    private SettingReadsForTests() {
    }

    /** Every read of [setting] answers null until {@link #mend} runs, so a hook unboxing it throws. */
    public static void breakReads(BooleanSetting setting) {
        setting.value = null;
    }

    /** Reads [setting] back from its store. */
    public static void mend(BooleanSetting setting) {
        setting.load();
    }
}
