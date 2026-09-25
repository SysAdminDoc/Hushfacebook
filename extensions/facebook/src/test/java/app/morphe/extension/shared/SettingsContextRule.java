/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared;

import android.content.Context;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.SharedPrefCategory;

import java.lang.reflect.Field;

import org.junit.rules.ExternalResource;
import org.robolectric.RuntimeEnvironment;

/**
 * Installs the application context before each test, and again after it.
 *
 * <p>{@code Setting}'s static initialiser reads {@link Utils#getContext()}. A test class that
 * touches the settings registry without one poisons {@code BaseSettings} for every later class
 * in the same Robolectric sandbox, and nothing in the failure says which class did it: the
 * classes that follow report {@code NoClassDefFoundError} and the class at fault passes. Whether
 * a given class got away with it therefore depended on an earlier class having set a context,
 * which is to say on the order Gradle happened to scan the tree in. Adding three test classes on
 * 2026-09-08 changed that order and 419 of 736 tests failed at once.
 *
 * <p>Declare it as a {@code @Rule} in any test class that reads or writes a {@code Setting}:
 *
 * <pre>{@code @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();}</pre>
 *
 * <p>An {@code @Before} method that calls {@code Utils.setContext} itself does the same job. What
 * neither of them is, is a call in {@code @After}: by the time that runs the registry has already
 * been touched. ColdStartHooksTest declares neither on purpose.
 */
public final class SettingsContextRule extends ExternalResource {
    @Override
    protected void before() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // Robolectric hands every test method a fresh application and preference cache, but
        // Setting's store is a static captured when its class first loaded in this sandbox,
        // which is whichever class ran first. A fragment resolves its own store from the
        // activity, so after the first class the two are different objects and a value
        // written through one is invisible through the other: that was the [23] recovery
        // flake. Point the static one at this test's store.
        try {
            Field store = SharedPrefCategory.class.getDeclaredField("preferences");
            store.setAccessible(true);
            store.set(Setting.preferences, RuntimeEnvironment.getApplication()
                    .getSharedPreferences(Setting.preferences.name, Context.MODE_PRIVATE));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not re-point Setting's store", exception);
        }
        // The restart debt is process-wide by design, and the test JVM is one process: a
        // restart-gated toggle in one class would pin the row on every page captured after it.
        app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment
                .restartPending.clear();
        // The debt is measured against the value each setting first ran with, which is just as
        // process-wide: a test that ran with safe mode on left the next test owing a restart for
        // a switch it never touched.
        try {
            Field running = app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment
                    .class.getDeclaredField("runningValues");
            running.setAccessible(true);
            ((java.util.Map<?, ?>) running.get(null)).clear();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not clear the running values", exception);
        }
    }

    /**
     * Runs [body] the way a hook runs when Facebook calls it before its application's onCreate:
     * with no context set. The context comes back afterwards, whatever [body] does.
     */
    public static void withoutContext(Runnable body) {
        Context saved = Utils.context;
        boolean ready = Utils.settingsReady;
        Utils.context = null;
        Utils.settingsReady = false;
        try {
            body.run();
        } finally {
            Utils.context = saved;
            Utils.settingsReady = ready;
        }
    }

    /**
     * Runs [body] the way a hook runs inside setContext's window: the context is set, and whether
     * this process runs paused isn't decided yet. The flag comes back afterwards.
     */
    public static void beforeThePauseIsDecided(Runnable body) {
        boolean ready = Utils.settingsReady;
        Utils.settingsReady = false;
        try {
            body.run();
        } finally {
            Utils.settingsReady = ready;
        }
    }

    @Override
    protected void after() {
        // The sandbox is shared with whatever runs next, and a test is free to swap the context
        // for one of its own.
        Utils.setContext(RuntimeEnvironment.getApplication());
    }
}
