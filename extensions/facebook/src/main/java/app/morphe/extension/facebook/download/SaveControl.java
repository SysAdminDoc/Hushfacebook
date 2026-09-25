/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.L10n;

/**
 * A running save as the person saving sees it: a notification that shows how far it has got and
 * has a Cancel button, for as long as the save runs.
 *
 * <p>A save used to say "Saving..." when it started and nothing more until it ended, so a large
 * video looked stuck and nothing could stop one. The notification goes when the save ends, and the
 * toast that says how it ended stays as it was.
 *
 * <p>Cancel is a broadcast to a receiver registered in Facebook's process, not a component added to
 * its manifest, since a patch that adds one changes what other apps can reach. From Android 13 the
 * receiver isn't exported. Below that a receiver registered at run time always is, so every Cancel
 * carries a token this process made up, and a broadcast without it is ignored.
 *
 * <p>With Facebook's notifications off, or this channel switched off, a save runs as it did before:
 * a toast at each end and no way to cancel.
 */
final class SaveControl {

    private SaveControl() {}

    static final String ACTION_CANCEL = "app.morphe.extension.facebook.CANCEL_SAVE";
    static final String EXTRA_ID = "app.morphe.extension.facebook.SAVE_ID";
    static final String EXTRA_TOKEN = "app.morphe.extension.facebook.SAVE_TOKEN";
    static final String CHANNEL = "hushfacebook_saves";
    /** Every notification here is posted under this tag, so its id can't replace one of Facebook's. */
    static final String TAG = "hushfacebook-save";

    /** Unguessable, and new in every process. */
    private static final String TOKEN = UUID.randomUUID().toString();

    /** The shortest gap between two updates of a notification. The system drops faster ones anyway. */
    private static final long UPDATE_GAP_NANOS = 500_000_000L;

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Map<Integer, Save> RUNNING = new ConcurrentHashMap<>();
    /** The application the receiver is registered on: one per process, and one per test. */
    private static Context listeningOn;

    /** Starts watching a save, and shows its notification when one can be shown. Never throws. */
    static Save begin(Context application, boolean video) {
        NotificationManager manager = notifications(application);
        Save save = new Save(application, NEXT_ID.getAndIncrement(), video, manager);
        RUNNING.put(save.id, save);
        if (save.manager != null) listen(application);
        save.show(-1, 0, -1);
        return save;
    }

    /**
     * Takes down the notifications of saves that aren't running in this process, and answers how
     * many went. Android leaves a notification up when it ends the process that posted it, so a
     * save it stopped half way stayed on screen, ongoing, with a Cancel nothing answered, until a
     * later process happened to reuse its number.
     */
    static int removeStale(Context application) {
        try {
            NotificationManager manager = application.getSystemService(NotificationManager.class);
            if (manager == null) return 0;
            int removed = 0;
            for (StatusBarNotification shown : manager.getActiveNotifications()) {
                if (!TAG.equals(shown.getTag()) || RUNNING.containsKey(shown.getId())) continue;
                manager.cancel(TAG, shown.getId());
                removed++;
            }
            return removed;
        } catch (Throwable t) {
            MediaDownload.failure(() -> "could not take down a stopped save's notification", t);
            return 0;
        }
    }

    /** Cancels the save numbered [id]. Answers whether one was running. */
    static boolean cancel(int id) {
        Save save = RUNNING.get(id);
        if (save == null) return false;
        save.cancel();
        return true;
    }

    /** The Cancel broadcast for save [id], as the notification's button sends it. For tests. */
    static Intent cancelIntent(Context application, int id) {
        return new Intent(ACTION_CANCEL)
            .setPackage(application.getPackageName())
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TOKEN, TOKEN);
    }

    /** Facebook's notification service, when this channel may post, else {@code null}. */
    private static NotificationManager notifications(Context application) {
        try {
            NotificationManager manager = application.getSystemService(NotificationManager.class);
            if (manager == null || !manager.areNotificationsEnabled()) return null;

            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                // The person switched this channel off in Facebook's notification settings.
                return null;
            }
            String name = L10n.t(application, "Hushfacebook saves");
            if (channel == null || !name.contentEquals(channel.getName())) {
                // Creating it again renames it in the phone's current language and changes
                // nothing the person set for it.
                NotificationChannel named = new NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW);
                named.setDescription(L10n.t(application,
                    "How far a photo or video you're saving has got, with a button to cancel it"));
                named.setShowBadge(false);
                manager.createNotificationChannel(named);
            }
            return manager;
        } catch (Throwable t) {
            MediaDownload.failure(() -> "could not reach the notification service", t);
            return null;
        }
    }

    private static synchronized void listen(Context application) {
        if (listeningOn == application) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if (intent == null || !TOKEN.equals(intent.getStringExtra(EXTRA_TOKEN))) return;
                        int id = intent.getIntExtra(EXTRA_ID, -1);
                        if (cancel(id)) MediaDownload.info(() -> "save " + id + " cancelled from its notification");
                    } catch (Throwable t) {
                        MediaDownload.failure(() -> "could not cancel a save", t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_CANCEL);
            if (Build.VERSION.SDK_INT >= 33) {
                application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                application.registerReceiver(receiver, filter);
            }
            listeningOn = application;
        } catch (Throwable t) {
            MediaDownload.failure(() -> "could not listen for Cancel", t);
        }
    }

    /**
     * "4.2 MB of 100 MB", "12 MB so far", or {@code null} before any byte. The sentence is in
     * the phone's language and the numbers are written its way.
     */
    static String progressText(long done, long total) {
        if (done <= 0) return null;
        return total > 0
            ? L10n.f("%1$s of %2$s", megabytes(done), megabytes(total))
            : L10n.f("%1$s so far", megabytes(done));
    }

    private static String megabytes(long bytes) {
        double megabytes = bytes / (1024.0 * 1024.0);
        Locale locale = L10n.locale();
        return megabytes < 10
            ? String.format(locale, "%.1f MB", megabytes)
            : String.format(locale, "%d MB", Math.round(megabytes));
    }

    /** One save: how far it has got, whether it was cancelled, and its notification. */
    static final class Save implements Downloader.Progress {
        final int id;
        final NotificationManager manager;
        private final Context application;
        private final boolean video;
        private final PendingIntent cancel;

        private volatile boolean cancelled;
        /** Closes the connection the save is reading, handed over by Downloader. */
        private volatile Runnable closeReading;
        /** When the notification last changed; set by the first show, which begin() makes. */
        private long shownAt;
        private int shownPercent = -2;
        private boolean ended;

        Save(Context application, int id, boolean video, NotificationManager manager) {
            this.application = application;
            this.id = id;
            this.video = video;
            PendingIntent button = null;
            if (manager != null) {
                try {
                    // Its own request code, so two saves' buttons are two intents, not one updated.
                    button = PendingIntent.getBroadcast(application, id, cancelIntent(application, id),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                } catch (Throwable t) {
                    MediaDownload.failure(() -> "could not make the Cancel button", t);
                }
            }
            this.cancel = button;
            this.manager = button == null ? null : manager;
        }

        @Override
        public void transferred(long done, long total) {
            if (manager == null) return;
            int percent = total > 0 ? (int) Math.min(100L, done * 100L / total) : -1;
            if (System.nanoTime() - shownAt < UPDATE_GAP_NANOS) return;
            if (percent >= 0 && percent == shownPercent) return;
            show(percent, done, total);
        }

        @Override
        public void reading(Runnable close) {
            closeReading = close;
            // A cancel that came before this connection existed ends it now.
            if (cancelled) close(close);
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        /**
         * Stops the save. On Android, closing its connection ends a read that's waiting at once.
         * Checked with app_process on the API 36 emulator, 2026-09-25, against a server that sent
         * the headers and then nothing: over plain HTTP disconnect() returned in 0 ms and the read
         * threw "Socket closed" 6 ms later. Over HTTPS through Conscrypt (TLS 1.3, the path every
         * save takes) disconnect() ended the read within 1 ms in eight of nine runs and 12.5 ms in
         * the ninth. Closing the response stream from another thread instead threw "Unbalanced
         * enter/exit" and left the read waiting out its whole 20 s timeout, which is why the
         * closer is disconnect() (see Downloader). The notification goes straight away either
         * way, so the person sees the cancel take.
         */
        void cancel() {
            cancelled = true;
            Runnable close = closeReading;
            if (close != null) close(close);
            end();
        }

        /** The save is over, however it ended, and its notification goes. */
        void end() {
            synchronized (this) {
                ended = true;
            }
            RUNNING.remove(id);
            if (manager == null) return;
            try {
                manager.cancel(TAG, id);
            } catch (Throwable t) {
                MediaDownload.failure(() -> "could not remove the save's notification", t);
            }
        }

        synchronized void show(int percent, long done, long total) {
            if (manager == null || ended) return;
            shownAt = System.nanoTime();
            shownPercent = percent;
            try {
                Notification.Builder builder = new Notification.Builder(application, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(video
                        ? L10n.t(application, "Saving a video")
                        : L10n.t(application, "Saving a photo"))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setCategory(Notification.CATEGORY_PROGRESS)
                    .setProgress(100, Math.max(0, percent), percent < 0)
                    // The catalog's Cancel, in the language of the title above it.
                    .addAction(new Notification.Action.Builder((Icon) null,
                        L10n.t(application, "Cancel"), cancel).build());
                String text = progressText(done, total);
                if (text != null) builder.setContentText(text);
                manager.notify(TAG, id, builder.build());
            } catch (Throwable t) {
                MediaDownload.failure(() -> "could not show the save's progress", t);
            }
        }

        /**
         * Closing the connection is what ends a read waiting on the network. It's done off the
         * thread that asked, since a Cancel arrives on the one that draws Facebook.
         */
        private static void close(Runnable close) {
            Thread closer = new Thread(() -> {
                try {
                    close.run();
                } catch (Throwable ignored) {
                    // The read ends on its own at the timeout, and the flag stops it then.
                }
            }, "hushfacebook-save-cancel");
            closer.setDaemon(true);
            closer.start();
        }
    }
}
