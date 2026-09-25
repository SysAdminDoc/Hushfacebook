/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Takes Facebook's tracking keys out of a link, offline.
 *
 * <p>Only the query changes, and only by losing whole {@code key=value} pairs whose key is on one
 * of the lists below. Everything else stays as it was written: the other pairs in their order,
 * a key given twice, how each value is encoded, the fragment, and a host in any script. So a
 * link that carries no tracking key comes back as the same string, and cleaning a clean link
 * changes nothing.
 *
 * <p>A link that isn't {@code http} or {@code https}, or that can't be read, comes back as it
 * came. Nothing here goes online.
 */
public final class LinkCleaner {

    private LinkCleaner() {}

    /*
     * Reviewed 2026-09-25 against ClearURLs, AdGuard's and uBO's URL tracking filters, Brave's and
     * Firefox's lists and DandelionSprout's, with every key tried live or in Facebook 580's code
     * by someone looking for a reason to keep it. Each one here only labels where a click came
     * from; the page it opens is picked by the path or by keys that stay (story_fbid, id, v, fbid,
     * set, comment_id, reply_comment_id). Keys that turned out to pick something stay off the
     * lists: ref on facebook.com/msg/ carries a Messenger payload, eid picks an event on old
     * event.php links, aref is part of an email link, and tracking, referral_code and referrer
     * are read by routes that need them. So are href, u and h, and av, eav and hash on /download/.
     */

    /** Taken out on any host: Facebook adds them to links that leave it. */
    private static final Set<String> EVERY_HOST = keys("fbclid", "mibextid",
            "fb_action_ids", "fb_action_types", "fb_ref", "fb_source",
            "action_object_map", "action_type_map", "action_ref_map");

    /** Taken out of Facebook's own links only. */
    private static final Set<String> FACEBOOK_ONLY = keys("sfnsn", "extid", "rdid", "share_url",
            "__tn__", "__so__", "__rv__", "_ft_", "refsrc", "fref", "refid", "pnref", "ls_ref",
            "hc_ref", "hc_location", "dti", "acontext", "action_history", "video_source",
            "comment_tracking", "m_entstream_source", "idorvanity", "paipv", "wtsid",
            "_rdr", "_rdc", "rdr", "rdc", "referral_story_type", "gd_impression_id",
            "external_ref", "store_visit_source");

    /**
     * Pairs taken out of Facebook's own links only with exactly this value. The app adds
     * ref=share to some stories' links, and ref with another value can carry what a link opens.
     */
    private static final Set<String> FACEBOOK_PAIRS = keys("ref=share");

    /** Keys Facebook numbers, such as {@code __cft__[0]}, taken out of its own links. */
    private static final Pattern FACEBOOK_NUMBERED = Pattern.compile("__(?:cft|xts)__\\[\\d+\\]");

    /** Facebook's hosts, each with its subdomains. */
    private static final String[] FACEBOOK_HOSTS = {"facebook.com", "fb.com", "fb.watch", "fb.me"};

    /**
     * Injected where Facebook builds a link it hands out when someone shares. Answers the link
     * without its tracking keys, or as it came while the switch is off, Hushfacebook is paused or
     * the settings aren't ready yet. Never throws.
     */
    public static String sanitizeShared(String url) {
        HookStatus.invoked(FamilyNames.SANITIZE_SHARING_LINKS);
        try {
            if (!Utils.settingsReady() || !Settings.SANITIZE_SHARING_LINKS.get()) return url;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SANITIZE_SHARING_LINKS, "switch read", t);
            return url;
        }
        return clean(url);
    }

    /**
     * {@code url} without the tracking keys, or {@code url} itself when it has none, isn't a web
     * link, or can't be read. Never throws.
     */
    public static String clean(String url) {
        if (url == null) return null;
        try {
            return cleaned(url);
        } catch (Throwable t) {
            // A link this can't take apart goes out as it came.
            return url;
        }
    }

    private static String cleaned(String url) {
        int colon = url.indexOf(':');
        if (colon <= 0) return url;
        String scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return url;

        // The fragment starts at the first '#', and a '?' after it is part of the fragment.
        int fragment = url.indexOf('#');
        int end = fragment < 0 ? url.length() : fragment;
        int query = url.indexOf('?');
        if (query < 0 || query > end) return url;

        boolean facebook = isFacebookHost(host(url, colon + 1, query));
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String pair : url.substring(query + 1, end).split("&", -1)) {
            if (isTracking(keyOf(pair), facebook) || (facebook && FACEBOOK_PAIRS.contains(pair))) {
                removed = true;
            } else {
                kept.add(pair);
            }
        }
        if (!removed) return url;

        String rest = String.join("&", kept);
        return url.substring(0, rest.isEmpty() ? query : query + 1) + rest + url.substring(end);
    }

    private static boolean isTracking(String key, boolean facebook) {
        if (EVERY_HOST.contains(key)) return true;
        return facebook && (FACEBOOK_ONLY.contains(key) || FACEBOOK_NUMBERED.matcher(key).matches());
    }

    /** The key of a {@code key=value} pair, decoded, or as written when it doesn't decode. */
    private static String keyOf(String pair) {
        int equals = pair.indexOf('=');
        String key = equals < 0 ? pair : pair.substring(0, equals);
        if (key.indexOf('%') < 0) return key;
        try {
            return URLDecoder.decode(key.replace("+", "%2B"), "UTF-8");
        } catch (Exception malformed) {
            return key;
        }
    }

    /**
     * The host between {@code //} and the path, the query or the port, lower-cased, or
     * {@code null} when the link has no authority.
     */
    private static String host(String url, int from, int query) {
        if (!url.startsWith("//", from)) return null;
        int start = from + 2;
        int stop = query;
        int slash = url.indexOf('/', start);
        if (slash >= 0 && slash < stop) stop = slash;
        String authority = url.substring(start, stop);
        String host = authority.substring(authority.lastIndexOf('@') + 1);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close < 0 ? host : host.substring(0, close + 1);
        } else {
            int port = host.indexOf(':');
            if (port >= 0) host = host.substring(0, port);
        }
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.toLowerCase(Locale.ROOT);
    }

    private static boolean isFacebookHost(String host) {
        if (host == null) return false;
        for (String domain : FACEBOOK_HOSTS) {
            // The dot keeps "notfacebook.com" from matching "facebook.com".
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    private static Set<String> keys(String... keys) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(keys)));
    }
}
