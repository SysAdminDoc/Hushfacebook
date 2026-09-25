/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/MediaDownload.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.download;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Saves the picture or the video that the app is showing, without asking the app to do it.
 *
 * <p>Facebook ships a save feature and refuses to run it for most content. The refusal is not
 * about the address: the story or the reel is on the screen, so the app holds an address that it
 * can fetch. This takes that address and fetches it.
 *
 * <p>For a story that is the point of the whole thing. Facebook's own save runs a check for
 * licensed music first. On a story that has any, it shows a warning and then saves nothing,
 * whatever the user answers (issue #110). The patch replaces the body of that handler, so the
 * check never runs and the video arrives complete, with its sound.
 *
 * <h2>What the patch hands over</h2>
 *
 * <p>The object that holds the media, and nothing else. Each entry point is called with the object
 * of the item that the user tapped, so the file saved is always the item on the screen. There is
 * deliberately <b>no static holding the last address seen</b>. Facebook prepares the next reels
 * while the current one plays, and a measurement counted six of them built in about fifteen
 * seconds of scrolling. Anything remembered rather than passed in saves the wrong video, and
 * still reports success.
 *
 * <p>Every step goes to the diagnostic report under Downloads, and to logcat as
 * {@code morphe: MediaDownload}. Addresses are logged as kind of file and quality only.
 */
public final class MediaDownload {

    private MediaDownload() {}

    /** The source every save event carries in the diagnostic report. */
    static final String SOURCE = "MediaDownload";

    /** A step of a save, always kept: a save is rare and each one is worth a line. */
    static void info(Logger.LogMessage message) {
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, message);
    }

    /** A save that ended without a file, or a step that failed. */
    static void failure(Logger.LogMessage message, Throwable cause) {
        Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, message, cause);
    }

    /** A guard against a rapid tap, not a work queue. */
    private static final int MAX_IN_FLIGHT = 3;

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    /** Saves still running. A test waits on this for the ones it started through a hook. */
    static int savesInFlight() {
        return IN_FLIGHT.get();
    }

    /**
     * Save the media of the story that is open.
     *
     * <p>[host] is the story card. Its address is read by value, because the fields that hold it
     * are renamed on every release of the app while the addresses in them keep their shape.
     *
     * @return whether a download started. {@code false} lets the caller fall back to the app.
     */
    public static boolean saveStory(Context context, Object host) {
        HookStatus.invoked(FamilyNames.STORY_DOWNLOAD);
        try {
            // Off, or with no context to read the switch with, Facebook's own save runs, as it
            // would unpatched.
            if (!Utils.hasContext() || !Settings.DOWNLOAD_STORIES.get()) return false;

            List<String> urls = collectStoryUrls(host);

            // The card holds one video address, and it is 360p. The player of the same video can
            // hold a better one. So the save tries the recorded source of the player first.
            PlayerSources.Source source = PlayerSources.find(host);
            if (source != null) {
                addIfUsable(urls, source.hdUrl);
                if (beginDash(context, "the story video", source.manifest, urls)) return true;
            }

            return begin(context, urls);
        } catch (Throwable t) {
            // Throwable and not Exception. A renamed field surfaces as NoSuchFieldError, and a
            // reflective call on a changed class surfaces as a LinkageError. Neither is an
            // Exception, and either one that reaches Facebook's click handler ends the app.
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, "story save", t);
            failure(() -> "the story save could not start", t);
            return false;
        }
    }

    /**
     * Save the video that the player is streaming.
     *
     * <p>[hdField] and [sdField] are the real names of the two fields of the source that hold a
     * single file address. [manifestField] is the real name of the field that holds the DASH
     * manifest. The patch reads those names out of the app while patching, so this file names no
     * field of its own and neither does the patch. The save tries the manifest first, because it
     * can list a better track than the two single files.
     *
     * <p>Asking by name matters here in a way that it does not for a story. The source carries a
     * third address of the same type, and it holds the subtitles. So "the first address on the
     * object" is a real way to save the wrong thing.
     *
     * @return whether a download started. {@code false} lets the caller fall back to the app.
     */
    public static boolean saveVideo(
        Context context,
        Object host,
        String hdField,
        String sdField,
        String manifestField
    ) {
        try {
            List<String> urls = collectVideoUrls(host, hdField, sdField);

            String manifest = RenditionPicker.fieldValue(host, manifestField);
            if (beginDash(context, "the reel", manifest, urls)) return true;

            return begin(context, urls);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel save", t);
            failure(() -> "the video save could not start", t);
            return false;
        }
    }

    // ---------------------------------------------------------------- internals

    /** The named fields first, then whatever else the object can reach. */
    private static List<String> collectVideoUrls(Object host, String hdField, String sdField) {
        List<String> urls = new ArrayList<>();

        addIfUsable(urls, RenditionPicker.fieldValue(host, hdField));
        addIfUsable(urls, RenditionPicker.fieldValue(host, sdField));

        // Either the object is not the source itself, or the release moved the fields. Ranking
        // by value still answers, and it is the reason this is not only a pair of reads.
        if (urls.isEmpty()) urls.addAll(RenditionPicker.harvest(host, 1));

        return urls;
    }

    /**
     * Every address the story card can reach.
     *
     * <p>{@code getMedia} is one of the few names on this class that Facebook keeps, so it is
     * worth trying before the walk. When it answers, the walk starts from the media rather than
     * from the card, which keeps it away from everything else the card holds.
     */
    private static List<String> collectStoryUrls(Object host) {
        Object media = call(host, "getMedia");
        Object from = media != null ? media : host;

        return RenditionPicker.harvest(from, media != null ? 1 : 2);
    }

    private static void addIfUsable(List<String> urls, String url) {
        if (url != null && RenditionPicker.isHttpUrl(url)) urls.add(url);
    }

    private static Object call(Object host, String method) {
        if (host == null) return null;

        try {
            return host.getClass().getMethod(method).invoke(host);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Pick the best address of those found and fetch it.
     *
     * <p>This ranks the item both ways and keeps the better answer. It does not read the type of
     * the item from the app. The app records that type in an enum whose constants move between
     * releases, and one constant mistaken for another saves the wrong file without a word.
     */
    private static boolean begin(Context context, List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            failure(() -> "nothing to save: the item carried no address", null);
            return false;
        }

        // The walk collects any address the object can reach, a link in a caption included. Only
        // Meta's media servers are candidates, so a foreign address can't outrank a real one.
        final int found = urls.size();
        urls = metaOnly(urls);
        if (urls.isEmpty()) {
            failure(() -> "nothing to save: none of the " + found + " addresses was on Meta's media servers", null);
            return false;
        }

        String video = RenditionPicker.bestOf(urls, true);
        String image = RenditionPicker.bestOf(urls, false);

        boolean isVideo = video != null;
        String chosen = isVideo ? video : image;

        if (chosen == null) {
            final int candidates = urls.size();
            failure(() -> "nothing to save: none of the " + candidates + " addresses was a file", null);
            return false;
        }

        Context safe = ready(context);
        if (safe == null) return false;

        // Every candidate, so a saved file that is smaller than expected can be told apart from
        // a ranking that chose badly. Names and sizes only: a whole address is a signed, working
        // handle to the user's content, and the log can be read by anything else on the device.
        StringBuilder all = new StringBuilder();
        for (String url : urls) {
            if (all.length() > 0) all.append(", ");
            all.append(describe(url));
        }

        final int candidates = urls.size();
        info(() -> "saving " + (isVideo ? "video" : "image")
            + " " + describe(chosen)
            + " from " + candidates + " candidate(s): " + all);

        Downloader.Kind kind = isVideo ? Downloader.Kind.VIDEO : Downloader.Kind.IMAGE;
        start(safe, isVideo, writer -> saveFile(safe, chosen, kind, writer));
        return true;
    }

    /** One checked file, fetched into the cache and then published. */
    private static Downloader.Result saveFile(Context application, String url, Downloader.Kind kind,
            MediaStoreWriter writer) {
        java.io.File folder = DashSave.workFolder(application);
        if (folder == null) return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "no cache folder");
        return Downloader.save(url, kind, folder, writer, policyFor(application), Downloader.MAX_BYTES);
    }

    /** The policy every save a test drives uses in place of the real one. Never set on a phone. */
    static volatile MediaUrlPolicy policyForTests;

    /**
     * Meta's address rules, with the lookup fence up only while the socket goes straight to the
     * answer: no proxy for the address and no VPN on the network. See {@link MediaUrlPolicy}.
     */
    static MediaUrlPolicy policyFor(Context application) {
        MediaUrlPolicy forced = policyForTests;
        if (forced != null) return forced;
        return new MediaUrlPolicy(MediaUrlPolicy.DNS, url -> !MediaUrlPolicy.proxied(url) && !onVpn(application));
    }

    /**
     * Whether the network a save would use runs through a VPN, a fake-IP proxy client's among
     * them. Facebook holds ACCESS_NETWORK_STATE. No answer keeps the fence up.
     */
    private static boolean onVpn(Context context) {
        try {
            android.net.ConnectivityManager manager =
                (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            android.net.Network active = manager.getActiveNetwork();
            android.net.NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
            return capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<String> metaOnly(List<String> urls) {
        List<String> kept = new ArrayList<>();
        for (String url : urls) {
            if (MediaUrlPolicy.shapeRefusal(url) == null) kept.add(url);
        }
        return kept;
    }

    /**
     * Save the best video track and audio track of a DASH manifest, if the video track is larger
     * than all single-file addresses of the item.
     *
     * <p>The manifest can list tracks that no single file has. A story card and its player hold
     * only 360p files, but the manifest lists tracks up to 1080p. The device joins the two tracks
     * into one file. If this fails, the save gets the best single file, so the user still gets a
     * file.
     *
     * @return whether a download started. {@code false} lets the caller save a single file.
     */
    private static boolean beginDash(Context context, String label, String manifest, List<String> urls) {
        List<DashManifest.Track> tracks = new ArrayList<>();
        for (DashManifest.Track track : DashManifest.parse(manifest)) {
            if (MediaUrlPolicy.shapeRefusal(track.url) == null) tracks.add(track);
        }
        urls = metaOnly(urls);
        DashManifest.Track video = DashManifest.bestVideo(tracks, DashSave.canWriteAv1());

        if (video == null) {
            if (manifest != null) {
                info(() -> "the manifest of " + label + " has no track to save: " + tracks);
            }
            return false;
        }

        String fallback = RenditionPicker.bestOf(urls, true);
        int fallbackQuality = fallback == null ? 0 : RenditionPicker.qualityOf(fallback);

        if (video.shortSide() <= fallbackQuality) return false;

        Context safe = ready(context);
        if (safe == null) return false;

        DashManifest.Track audio = DashManifest.bestAudio(tracks);

        info(() -> "saving " + label + " from its DASH manifest: " + video
            + (audio == null ? ", no sound track" : " + " + audio)
            + ", instead of " + (fallback == null ? "nothing" : describe(fallback)));

        start(safe, true, writer -> {
            Downloader.Result result = DashSave.save(safe, video, audio, writer, policyFor(safe));
            if (result.ok() || fallback == null) return result;

            failure(() -> "the DASH save ended with " + result + ", saving " + describe(fallback), null);
            return saveFile(safe, fallback, Downloader.Kind.VIDEO, writer);
        });
        return true;
    }

    /**
     * The context to save with, or {@code null} when a save cannot start now.
     *
     * <p>Never the Activity. A download outlives the screen that started it, and holding the
     * Activity across it is a leak, and Facebook's own tooling reports it.
     */
    private static Context ready(Context context) {
        if (context == null) return null;

        if (IN_FLIGHT.get() >= MAX_IN_FLIGHT) {
            failure(() -> "too many saves at once", null);
            return null;
        }

        Context application = context.getApplicationContext();
        return application != null ? application : context;
    }

    /** One save on the worker thread. It writes through [writer] and returns the result. */
    interface Job {
        Downloader.Result run(MediaStoreWriter writer);
    }

    /** Runs [job] on its own worker thread, and hands the thread back so a test can wait for it. */
    static Thread start(Context application, boolean video, Job job) {
        IN_FLIGHT.incrementAndGet();
        Feedback.show(application, "Saving...", false);

        Thread worker = new Thread(() -> {
            MediaStoreWriter writer = new MediaStoreWriter(application, video);

            try {
                Downloader.Result result = job.run(writer);
                if (result.ok()) info(() -> "save finished: " + result);
                else failure(() -> "save finished: " + result, null);
                Feedback.show(application, message(result.status, writer.savedLocation()), !result.ok());
            } catch (Throwable t) {
                // Nothing can leave this thread. Facebook installs its own handler for uncaught
                // exceptions and reports them as its own crashes.
                failure(() -> "the save failed", t);
                Feedback.show(application, "Download failed", true);
            } finally {
                IN_FLIGHT.decrementAndGet();
            }
        }, "hushfacebook-save");

        // A thread that ends when the copy ends leaves nothing behind in a process that is not
        // ours. A pool parks a thread there for as long as Facebook runs.
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
        return worker;
    }

    private static String message(Downloader.Status status, String location) {
        switch (status) {
            case OK:
                return "Saved to " + (location == null ? "the gallery" : location);
            case EXPIRED:
                return "Link expired. Reopen the item and try again";
            case REFUSED:
                return "Not saved: that isn't a Facebook photo or video";
            case TOO_LARGE:
                return "Not saved: the file is over 512 MB";
            default:
                return "Download failed";
        }
    }

    /**
     * Enough of an address to tell one candidate from another in a report: its kind of file and
     * its quality, and no more.
     *
     * <p>A whole address is a signed, working handle to the content of the user, and the log can
     * be read by anything else on the device. The file name isn't safe either: on Meta's CDN it
     * carries the object's own id ({@code 475148478_1134540631592283_..._n.jpg}), and the report
     * is pasted into public issues.
     */
    static String describe(String url) {
        int query = url.indexOf('?');
        String withoutQuery = query < 0 ? url : url.substring(0, query);

        int slash = withoutQuery.lastIndexOf('/');
        String file = slash < 0 ? withoutQuery : withoutQuery.substring(slash + 1);
        int dot = file.lastIndexOf('.');
        String extension = dot < 0 ? "" : file.substring(dot + 1).toLowerCase(Locale.US);
        if (!extension.matches("[a-z0-9]{1,5}")) extension = "file";

        return extension + " (" + RenditionPicker.qualityOf(url) + "p)";
    }
}
