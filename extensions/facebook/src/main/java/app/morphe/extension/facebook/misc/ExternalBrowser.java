/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/ExternalBrowser.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Facebook "[General] Open links in external browser" patch.
 *
 * <p>Facebook opens each tapped link in its own browser. The patch calls this class from the
 * {@code onCreate} and {@code onNewIntent} of that browser, after the superclass call and before
 * the browser is built. This class then builds an {@link Intent#ACTION_VIEW} on the URL and gives
 * it to the system.
 *
 * <p>The in-app browser has an <i>Open with</i> menu action that builds the same intent. That
 * action is not usable from here. It runs from a dispatcher that needs the chrome and the state
 * objects of the browser, and these objects exist only after the browser is built. To use it, the
 * browser must start and then close. The user sees a flash, and a dead entry stays on the back
 * stack.
 *
 * <p>The URL that arrives is not the URL that the user tapped. Facebook wraps each outbound link
 * in its link shim. See {@link #unwrapLinkShim}.
 *
 * <p>An {@code ACTION_VIEW} on an ordinary URL cannot come back into Facebook and make a loop.
 * Each {@code http} and {@code https} intent filter of Facebook is limited to a host that Facebook
 * owns.
 *
 * <p>{@link #redirect} returns {@code false} when the link must stay in the in-app browser. The
 * patch then continues into the original code of Facebook. This covers the domains of Facebook,
 * the schemes that are not web schemes, and a device that has no browser. A link that no other app
 * can open must still open in the app.
 */
public final class ExternalBrowser {

    private ExternalBrowser() {}

    /** The source every event of this hook carries in the diagnostic report. */
    private static final String SOURCE = "ExternalBrowser";

    /**
     * The hosts that stay in the in-app browser.
     *
     * <p>Login, checkout and the web pages of Facebook need the JavaScript bridges and the autofill
     * of the in-app browser. No other browser has them.
     */
    private static final String[] INTERNAL_HOSTS = {
        "facebook.com",
        "fb.com",
        "messenger.com",
        "meta.com",
    };

    /**
     * Gives the URL of {@code intent} to the system browser and closes the in-app browser.
     *
     * @param activity the in-app browser activity. It closes only when the link goes out.
     * @param intent   the intent that started the activity. The URL is the data of that intent,
     *                 and not an extra.
     * @return {@code true} when the link went out and the caller must stop. {@code false} when the
     *         caller must continue and open the in-app browser as before.
     */
    public static boolean redirect(Activity activity, Intent intent) {
        HookStatus.invoked(FamilyNames.EXTERNAL_BROWSER);
        if (activity == null || intent == null) return false;
        if (!switchedOn()) return false;

        Uri uri = intent.getData();
        if (uri == null || !isWebUrl(uri)) return false;

        Uri target = unwrapLinkShim(uri);
        if (isInternalHost(target.getHost())) return false;
        // Facebook adds fbclid to the destination inside the shim, so the site it opens can tell
        // Facebook the link was followed. It goes, and nothing else in the link changes.
        target = Uri.parse(LinkCleaner.clean(target.toString()));

        try {
            Intent view = new Intent(Intent.ACTION_VIEW, target);
            // The in-app browser closes, thus the link needs a task of its own to live in.
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(view);
        } catch (Throwable t) {
            // ActivityNotFoundException: the device has no browser, or the browser is off.
            // SecurityException: the target that answered refused the launch. In both cases, leave
            // the link to Facebook and do not lose it. Only the class is reported: the exception's
            // own text quotes the intent, and the intent carries the link.
            final String kind = t.getClass().getSimpleName();
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> "No external browser took the link (" + kind + "). It stays in the in-app browser.", null);
            return false;
        }

        activity.finish();
        return true;
    }

    /**
     * The true destination of a Facebook link-shim URL, or {@code uri} when it is not a shim.
     *
     * <p>Facebook does not give the browser the link that the user tapped. It gives it
     * {@code https://lm.facebook.com/l.php?u=<the true URL>&h=<signature>}. This is the click
     * tracker of Facebook, and it is on a host that Facebook owns.
     *
     * <p>As a result each outbound link looks internal, and each one stays in the in-app browser.
     * This was the fault that the device test of 2026-09-19 found. The {@code /flx/warn/}
     * interstitial of Facebook has the same shape.
     *
     * <p>The destination goes out, and the shim does not. Thus the browser makes one request and
     * not two, and Facebook does not learn that the link opened.
     */
    private static Uri unwrapLinkShim(Uri uri) {
        // A shim is always on a host of Facebook. Each other URL is already the destination.
        if (uri.isOpaque() || !isInternalHost(uri.getHost())) return uri;

        String wrapped = uri.getQueryParameter("u");
        if (wrapped == null) return uri;

        // A "u" that is not an absolute web URL belongs to some other page. Keep the URL as it is.
        Uri target = Uri.parse(wrapped);
        return isWebUrl(target) ? target : uri;
    }

    /**
     * The Hushfacebook switch. Off, unreadable, or asked before the settings are ready, the link
     * stays in the app.
     */
    private static boolean switchedOn() {
        try {
            return Utils.settingsReady() && Settings.OPEN_LINKS_EXTERNALLY.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.EXTERNAL_BROWSER, "switch read", t);
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> "Could not read the external browser switch", t);
            return false;
        }
    }

    /** Whether {@code uri} is an {@code http} or an {@code https} URL. */
    private static boolean isWebUrl(Uri uri) {
        String scheme = uri.getScheme();
        return "http".equals(scheme) || "https".equals(scheme);
    }

    /** Whether {@code host} is a domain of Facebook, or a subdomain of one. */
    private static boolean isInternalHost(String host) {
        if (host == null) return false;

        String lower = host.toLowerCase();
        for (String domain : INTERNAL_HOSTS) {
            // The dot keeps "notfacebook.com" from a match with "facebook.com".
            if (lower.equals(domain) || lower.endsWith("." + domain)) return true;
        }

        return false;
    }
}
