/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Forked from MorpheApp/morphe-patches (GPL-3.0), by way of
 * icysymmetra/tiktok-patches-for-morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Imported carrying no notice of its own. Morphe hard forked ReVanced, so parts of
 * this file may originate there.
 */
package app.morphe.extension.shared.settings.preference;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.AtomicFile;
import android.database.Cursor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.RejectedExecutionException;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticEvent;
import app.morphe.extension.shared.diagnostics.DiagnosticRedactor;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;

/** Bounded structured event storage and latest sanitized crash storage. */
public final class LogBufferManager {
    /**
     * What clearing says, so a bundle can set its own. Null means the text below, read from
     * {@link L10n} in the phone's language.
     */
    public static CharSequence clearedMessage;
    public static CharSequence nothingToClearMessage;
    public static CharSequence restoredMessage;
    public static CharSequence nothingToRestoreMessage;
    public static CharSequence restoreFailedMessage;

    /**
     * The other sentences the export path says. Each is null until a bundle sets it, and until
     * then the text below is read from {@link L10n}. The saved-file sentence carries the path as
     * %1$s.
     */
    public static CharSequence nothingToExportMessage;
    public static CharSequence copiedMessage;
    public static CharSequence exportFailedMessage;
    public static CharSequence noContextMessage;
    public static CharSequence alreadySavingMessage;
    public static CharSequence savedToMessage;
    public static CharSequence couldNotStartMessage;

    private static String say(CharSequence set, String fallback) {
        return set == null ? fallback : set.toString();
    }

    private static final int BUFFER_MAX_CHARS = 250_000;
    private static final int BUFFER_MAX_SIZE = 10_000;
    private static final int CLIPBOARD_MAX_CHARS = 60_000;
    private static final int CRASH_MAX_BYTES = 64_000;
    private static final String CRASH_FILE = "morphe_java_crash_report_v1.txt";
    private static final String NPTH_CRASH_FILE = "morphe_npth_crash_report_v1.txt";

    private static final Deque<DiagnosticEvent> logBuffer = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger logBufferCharSize = new AtomicInteger();
    private static final Object CRASH_FILE_LOCK = new Object();
    private static final Object CLEAR_UNDO_LOCK = new Object();
    private static final AtomicBoolean FILE_EXPORT_RUNNING = new AtomicBoolean();
    private static ClearSnapshot lastClear;

    /** Everything the clear row removes, kept in memory until its next tap. */
    private static final class ClearSnapshot {
        final List<DiagnosticEvent> events;
        final app.morphe.extension.shared.diagnostics.HookStatus.Snapshot hooks;
        final app.morphe.extension.shared.diagnostics.FeedFilterCounters.Snapshot feedFilter;
        final String javaCrash;
        final String nativeCrash;

        ClearSnapshot(
                List<DiagnosticEvent> events,
                app.morphe.extension.shared.diagnostics.HookStatus.Snapshot hooks,
                app.morphe.extension.shared.diagnostics.FeedFilterCounters.Snapshot feedFilter,
                String javaCrash,
                String nativeCrash
        ) {
            this.events = events;
            this.hooks = hooks;
            this.feedFilter = feedFilter;
            this.javaCrash = javaCrash;
            this.nativeCrash = nativeCrash;
        }

        boolean isEmpty() {
            return events.isEmpty() && hooks.isEmpty() && feedFilter.isEmpty()
                    && javaCrash.isEmpty() && nativeCrash.isEmpty();
        }
    }

    public enum UndoResult {
        RESTORED,
        NOTHING_TO_RESTORE,
        FAILED
    }

    private LogBufferManager() {
    }

    public static void appendToLogBuffer(String message) {
        appendEvent(app.morphe.extension.shared.diagnostics.DiagnosticCategory.OTHER,
                "Legacy", "INFO", message);
    }

    public static void appendEvent(
            app.morphe.extension.shared.diagnostics.DiagnosticCategory category,
            String source,
            String level,
            String message
    ) {
        DiagnosticEvent event = new DiagnosticEvent(
                category,
                System.currentTimeMillis(),
                Thread.currentThread().getName(),
                safe(source),
                safe(level),
                safe(message)
        );
        int eventSize = event.format().length();
        synchronized (CLEAR_UNDO_LOCK) {
            logBuffer.addLast(event);
            int newSize = logBufferCharSize.addAndGet(eventSize);

            while (newSize > BUFFER_MAX_CHARS || logBuffer.size() > BUFFER_MAX_SIZE) {
                DiagnosticEvent removed = logBuffer.pollFirst();
                if (removed == null) return;
                newSize = logBufferCharSize.addAndGet(-removed.format().length());
            }
        }
    }

    public static void exportToClipboard() {
        try {
            String exportText = clipboardText(CLIPBOARD_MAX_CHARS);
            if (exportText.isEmpty()) {
                Utils.showToastShort(say(nothingToExportMessage, L10n.t("No matching diagnostics found.")));
                return;
            }
            Utils.setClipboard(exportText);
            Utils.showToastShort(say(copiedMessage, L10n.t("Diagnostic report copied to the clipboard.")));
        } catch (Exception ex) {
            // The exception's own text stays in the log. It can carry a path or a signed URL,
            // and a reader on a phone cannot act on it from a toast.
            Utils.showToastLong(say(exportFailedMessage, L10n.t("The diagnostic report couldn't be saved. Try again.")));
            Logger.printException(() -> "Failed to export diagnostics", ex);
        }
    }

    public static void exportToFile() {
        Context context = Utils.getContext();
        if (context == null) {
            Utils.showToastLong(say(noContextMessage, L10n.t("The diagnostic report couldn't be saved yet. Try again in a moment.")));
            return;
        }
        Context application = context.getApplicationContext();
        final Context app = application == null ? context : application;
        if (!FILE_EXPORT_RUNNING.compareAndSet(false, true)) {
            Utils.showToastShort(say(alreadySavingMessage, L10n.t("A diagnostic report is already being saved.")));
            return;
        }
        try {
            Utils.submitOnBackgroundThread(() -> {
                try {
                    String exportText = buildExportText();
                    if (exportText.isEmpty()) {
                        Utils.showToastShort(say(nothingToExportMessage, L10n.t("No matching diagnostics found.")));
                    } else {
                        String saved = writeToFile(app, exportText);
                        Utils.showToastLong(String.format(say(savedToMessage, L10n.t("Full report saved to %1$s")), L10n.isolate(saved)));
                    }
                } catch (Exception ex) {
                    Utils.showToastLong(say(exportFailedMessage, L10n.t("The diagnostic report couldn't be saved. Try again.")));
                    Logger.printException(() -> "Failed to save diagnostics", ex);
                } finally {
                    FILE_EXPORT_RUNNING.set(false);
                }
                return null;
            });
        } catch (RejectedExecutionException error) {
            FILE_EXPORT_RUNNING.set(false);
            Logger.printException(() -> "Could not start diagnostic export", error);
            Utils.showToastLong(say(couldNotStartMessage, L10n.t("Couldn't start the report export. Try again shortly.")));
        }
    }

    static String writeToFile(Context context, String exportText) throws Exception {
        if (context == null) throw new IOException("Application context unavailable");
        String fileName = "morphe-diagnostics-" + fileTimestamp() + "-"
                + Long.toHexString(System.nanoTime()) + ".txt";
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Morphe");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri pendingUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (pendingUri == null) throw new IOException("Could not create report file");
        try {
            try (OutputStream output = resolver.openOutputStream(pendingUri, "w")) {
                writeText(output, exportText);
            }
            String savedName = providerDisplayName(resolver, pendingUri);
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(pendingUri, values, null, null) != 1) {
                throw new IOException("Could not publish report file");
            }
            return Environment.DIRECTORY_DOWNLOADS + "/Morphe/" + savedName;
        } catch (Exception error) {
            deleteIncomplete(resolver, pendingUri, error);
            throw error;
        }
    }

    private static String providerDisplayName(ContentResolver resolver, Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("Could not read saved report name");
            int column = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            if (column < 0) throw new IOException("Saved report name is unavailable");
            String name = cursor.getString(column);
            if (name == null || name.isEmpty()) throw new IOException("Saved report name is empty");
            return name;
        }
    }

    private static void deleteIncomplete(ContentResolver resolver, Uri uri, Exception failure) {
        try {
            if (resolver.delete(uri, null, null) != 1) {
                failure.addSuppressed(new IOException("Could not remove incomplete report"));
            }
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static void writeText(OutputStream output, String report) throws IOException {
        if (output == null) throw new IOException("Could not open report file");
        try (OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            writer.write(report);
        }
    }

    /**
     * A section of the exported report supplied from outside this library.
     *
     * <p>The Feature Gate Lab lives in the Facebook extension, and this library cannot name it,
     * so the extension registers what it wants said. A section with no lines is left out.
     */
    public interface ReportSection {
        /** The bracketed heading, without the brackets. */
        String title();

        /** One line per fact, or an empty list to say nothing. */
        List<String> lines();
    }

    private static final List<ReportSection> REPORT_SECTIONS = new CopyOnWriteArrayList<>();

    /** Adds a section after Hook status; a section registered twice is kept once. */
    public static void registerReportSection(ReportSection section) {
        if (section == null || REPORT_SECTIONS.contains(section)) return;
        REPORT_SECTIONS.add(section);
    }

    static void clearReportSectionsForTests() {
        REPORT_SECTIONS.clear();
    }

    public static String buildExportText() {
        Export export = buildExport();
        return export == null ? "" : export.head + eventsSection(export.events, null);
    }

    /**
     * What Copy quick report puts on the clipboard: the whole report when it fits in
     * [maxChars]. Past that the oldest events go first, so the header and every fixed section
     * arrive whole. Which build, which switches, what stayed in and whether the process ran
     * paused are what a pasted report is read for, and they come before the events. Only a
     * report whose fixed part doesn't fit beside even the shortest note, which takes a long crash
     * trace, is cut, and then at its end.
     */
    static String clipboardText(int maxChars) {
        Export export = buildExport();
        if (export == null) return "";
        String whole = export.head + eventsSection(export.events, null);
        if (whole.length() <= maxChars) return whole;

        // Room is kept for the longest the note can be, with every event left out.
        int total = export.events.size();
        int budget = maxChars - export.head.length()
                - eventsSection(Collections.<String>emptyList(), droppedNote(total)).length();
        if (budget >= 0) {
            int from = total;
            int used = 0;
            while (from > 0) {
                int cost = export.events.get(from - 1).length() + (used > 0 ? 1 : 0);
                if (used + cost > budget) break;
                used += cost;
                from--;
            }
            return export.head + eventsSection(export.events.subList(from, total), droppedNote(from));
        }

        // The events' heading doesn't fit beside the head, but a line saying they were left out
        // may. The head stays whole whenever it does.
        String allLeftOut = "\nclipboard_note: " + total + " events left out; use Save full report for everything\n";
        if (total > 0 && export.head.length() + allLeftOut.length() <= maxChars) return export.head + allLeftOut;

        // Only a head too long to fit beside that line is cut, at its end, and never between the
        // two halves of a character.
        String cut = "\nclipboard_note: cut at " + maxChars + " characters; use Save full report for everything\n";
        int end = Math.max(0, Math.min(export.head.length(), maxChars - cut.length()));
        if (end > 0 && Character.isHighSurrogate(export.head.charAt(end - 1))) end--;
        return export.head.substring(0, end) + cut;
    }

    private static String droppedNote(int dropped) {
        return "clipboard_note: " + dropped + " older events left out; use Save full report for everything";
    }

    /** The [SELECTED EVENTS] section, with [note] above its column heading, or "" with neither. */
    private static String eventsSection(List<String> events, String note) {
        if (events.isEmpty() && note == null) return "";
        StringBuilder section = new StringBuilder("\n\n[SELECTED EVENTS]\n");
        if (note != null) section.append(note).append('\n');
        section.append("category | timestamp | thread | source | level | message\n");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) section.append('\n');
            section.append(events.get(i));
        }
        return section.toString();
    }

    /** The report before its events, and the events oldest first, each one redacted. */
    private static final class Export {
        final String head;
        final List<String> events;

        Export(String head, List<String> events) {
            this.head = head;
            this.events = events;
        }
    }

    /** The report in its two parts, or null when nothing in it is worth sending. */
    private static Export buildExport() {
        Set<String> selected = LogExportFilterPreference.parse(BaseSettings.DEBUG_LOG_FILTERS.get());
        boolean includeAll = selected.isEmpty() || selected.contains("all");
        String crash = DiagnosticRedactor.redact(readCrashReport(Utils.getContext()));
        String npthCrash = DiagnosticRedactor.redact(readNpthCrashReport(Utils.getContext()));

        List<DiagnosticEvent> snapshot = new ArrayList<>(logBuffer);
        List<String> events = new ArrayList<>();
        for (DiagnosticEvent event : snapshot) {
            if (!includeAll && !selected.contains(event.category.value)) continue;
            events.add(DiagnosticRedactor.redact(event.format()));
        }

        // Hook misses are buffered under PATCH_ERRORS, so the table follows the same choice
        // the reader made in "Included diagnostics" rather than printing regardless. It goes
        // through the redactor for the same reason every other section does: the next name put
        // in it may not be a literal.
        // Paused, every family below that reads a setting is bound but takes Facebook's own path,
        // and a reader of the table has to be told so or it reads as a healthy build that does
        // nothing. A family that reads no setting keeps working and is left unmarked.
        boolean paused = HushfacebookPause.isPaused();
        StringBuilder hooks = new StringBuilder();
        if (includeAll || selected.contains(
                app.morphe.extension.shared.diagnostics.DiagnosticCategory.PATCH_ERRORS.value)) {
            for (String line : app.morphe.extension.shared.diagnostics.HookStatus.report(
                    paused ? " (paused)" : null)) {
                if (hooks.length() > 0) hooks.append('\n');
                hooks.append(DiagnosticRedactor.redact(line));
            }
        }

        // A family exists from the first layout pass, so an all-bound table must not make a
        // report non-empty: "No matching Morphe diagnostics found" would never be said again.
        // A table with a miss in it is different. Those events are the oldest in the buffer and
        // are the first evicted, so on a badly broken build the table is exactly what would be
        // dropped, and it is the thing the report exists to carry.
        boolean worthReporting = paused || !crash.isEmpty() || !npthCrash.isEmpty() || !events.isEmpty()
                || (hooks.length() > 0
                        && app.morphe.extension.shared.diagnostics.HookStatus.anyMissing());
        if (!worthReporting) return null;

        StringBuilder report = new StringBuilder();
        report.append("MORPHE DIAGNOSTIC REPORT\n")
                .append("schema: 1\n")
                .append("generated_utc: ").append(utcNow()).append('\n')
                .append("app: ").append(Utils.getContext().getPackageName())
                .append(' ').append(Utils.getAppVersionName())
                .append(" (").append(Utils.getAppVersionCode()).append(")\n")
                .append("abi: ").append(abiLine()).append('\n')
                .append("morphe: ").append(Utils.getPatchesReleaseVersion()).append('\n');
        if (paused) {
            report.append("hushfacebook: paused (")
                    .append(HushfacebookPause.reason().name().toLowerCase(java.util.Locale.ROOT))
                    .append("), every hook a setting controls takes Facebook's own path, and what was set "
                            + "when patching stays in\n");
        }

        if (!crash.isEmpty()) {
            report.append("\n[LATEST JAVA CRASH]\n").append(crash);
        }
        if (!npthCrash.isEmpty()) {
            report.append("\n[LATEST NATIVE CRASH SIGNAL]\n").append(npthCrash);
        }
        if (hooks.length() > 0) {
            report.append("\n[HOOK STATUS]\n").append(hooks).append('\n');
        }
        // The same choice as Hook status: what the bundle has been told to do is a fact about
        // the patches, and it is what a report from a phone with an override that changed
        // nothing has been missing. Not part of worthReporting, for the reason above.
        if (includeAll || selected.contains(
                app.morphe.extension.shared.diagnostics.DiagnosticCategory.PATCH_ERRORS.value)) {
            for (ReportSection section : REPORT_SECTIONS) {
                List<String> lines;
                try {
                    lines = section.lines();
                } catch (Throwable failure) {
                    lines = Collections.singletonList("could not be read: " + failure);
                }
                if (lines == null || lines.isEmpty()) continue;
                report.append("\n[").append(section.title()).append("]\n");
                for (String line : lines) {
                    report.append(DiagnosticRedactor.redact(line)).append('\n');
                }
            }
        }
        // Outside worthReporting for the same reason as the last exit below: a counter that has
        // only ever counted is not a finding, and a report made non-empty by one would mean
        // nobody is ever told there is nothing to send.
        String feedFilter = feedFilterLines(includeAll, selected);
        if (!feedFilter.isEmpty()) {
            report.append("\n[FEED FILTER]\n").append(feedFilter).append('\n');
        }
        // Deliberately not part of worthReporting above. Every process has a last exit, most of
        // them ordinary, so counting it would mean no report was ever empty and "No matching
        // Morphe diagnostics found" would never be said again.
        String lastExit = lastExitLine(includeAll, selected);
        if (!lastExit.isEmpty()) {
            report.append("\n[LAST EXIT]\n").append(lastExit).append('\n');
        }
        return new Export(report.toString(), events);
    }

    /**
     * The ABI the host's native code was installed for, whether this process is 64-bit, and what
     * the device supports. An arm64 build running under translation on an x86_64 emulator and the
     * same build on a phone fail differently, and a native crash means something else on each.
     * Every part answers "unknown" rather than stopping the report.
     */
    static String abiLine() {
        String app;
        try {
            Context context = Utils.getContext();
            String nativeDir = context == null ? null : context.getApplicationInfo().nativeLibraryDir;
            app = nativeDir == null ? "unknown" : new File(nativeDir).getName();
        } catch (Throwable unreadable) {
            app = "unknown";
        }
        String process;
        try {
            process = android.os.Process.is64Bit() ? "64-bit" : "32-bit";
        } catch (Throwable unreadable) {
            process = "unknown";
        }
        String device;
        try {
            device = android.text.TextUtils.join(",", Build.SUPPORTED_ABIS);
        } catch (Throwable unreadable) {
            device = "unknown";
        }
        return "app " + app + ", process " + process + ", device " + device;
    }

    /**
     * One line per feed filter route the app has reached, whatever the logging switch says.
     *
     * <p>It follows the reader's own choice in "Included diagnostics" like every other section,
     * under the feed category, and goes through the redactor because the next source name put in
     * it may not be a literal.
     */
    private static String feedFilterLines(boolean includeAll, Set<String> selected) {
        if (!includeAll && !selected.contains(
                app.morphe.extension.shared.diagnostics.DiagnosticCategory
                        .FEED_AND_NAVIGATION.value)) {
            return "";
        }
        StringBuilder lines = new StringBuilder();
        for (String line : app.morphe.extension.shared.diagnostics.FeedFilterCounters.report()) {
            if (lines.length() > 0) lines.append('\n');
            lines.append(DiagnosticRedactor.redact(line));
        }
        return lines.toString();
    }

    /**
     * Why the process went away last time. A Java crash handler sees none of the ways the system
     * ends an app: Android 17 kills one that goes over a RAM-proportional limit and records it as
     * a description like "MemoryLimiter:AnonSwap", and an ANR or a low-memory kill leaves nothing
     * behind either. One line turns an unexplained restart into something a maintainer can act on.
     *
     * <p>It follows the same filter as the hook table, because it is the same kind of evidence.
     */
    private static String lastExitLine(boolean includeAll, Set<String> selected) {
        if (!includeAll && !selected.contains(
                app.morphe.extension.shared.diagnostics.DiagnosticCategory.PATCH_ERRORS.value)) {
            return "";
        }
        try {
            Context context = Utils.getContext();
            if (context == null) return "";
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return "";
            // Pid 0 means any process, and Facebook runs several. The most recent record is
            // routinely a background helper the system reaped, which says nothing about why the
            // app went away, so the main process is picked out by name.
            String packageName = context.getPackageName();
            List<ApplicationExitInfo> history =
                    manager.getHistoricalProcessExitReasons(packageName, 0, 16);
            if (history == null || history.isEmpty()) return "";
            ApplicationExitInfo exit = null;
            for (ApplicationExitInfo candidate : history) {
                if (packageName.equals(candidate.getProcessName())) {
                    exit = candidate;
                    break;
                }
            }
            if (exit == null) return "";
            StringBuilder line = new StringBuilder();
            line.append("reason: ").append(exitReasonName(exit.getReason()))
                    .append("\nstatus: ").append(exit.getStatus())
                    .append("\nimportance: ").append(exit.getImportance())
                    .append("\nat: ").append(utcOf(exit.getTimestamp()));
            String description = exit.getDescription();
            if (description != null && !description.isEmpty()) {
                line.append("\ndescription: ").append(description);
            }
            return DiagnosticRedactor.redact(line.toString());
        } catch (Throwable unavailable) {
            // A build that cannot answer this is not a build that should fail to export.
            return "";
        }
    }

    private static String exitReasonName(int reason) {
        switch (reason) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            case 0: return "UNKNOWN";
            default: return "REASON " + reason;
        }
    }

    /** The same stamp the report header carries, so one report does not hold two formats. */
    private static String utcOf(long epochMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochMillis));
    }

    public static String snapshotForCrash(int maxChars) {
        List<DiagnosticEvent> snapshot = new ArrayList<>(logBuffer);
        StringBuilder result = new StringBuilder();
        for (int index = snapshot.size() - 1; index >= 0; index--) {
            String line = snapshot.get(index).format();
            if (result.length() + line.length() + 1 > maxChars) break;
            if (result.length() > 0) result.insert(0, '\n');
            result.insert(0, line);
        }
        return result.toString();
    }

    public static void persistCrashReport(Context context, String report) throws Exception {
        persistCrashReport(context, CRASH_FILE, report);
    }

    public static void persistNpthCrashReport(Context context, String report) throws Exception {
        persistCrashReport(context, NPTH_CRASH_FILE, report);
    }

    /** What a report that had to be cut ends with, so a reader knows the end is missing. */
    static final String CRASH_TRUNCATED_MARKER = "\n[report truncated]\n";

    private static void persistCrashReport(Context context, String fileName, String report) throws Exception {
        synchronized (CLEAR_UNDO_LOCK) {
            persistCrashReportLocked(context, fileName, report);
        }
    }

    /** Writes while the diagnostic generation lock is already held. */
    private static void persistCrashReportLocked(Context context, String fileName, String report)
            throws Exception {
        byte[] bytes = safe(report).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CRASH_MAX_BYTES) {
            // The header's own claim goes with the cut. The marker at the end said the report
            // was cut while its first lines still said it was complete.
            bytes = safe(report).replaceFirst("(?m)^complete: true$", "complete: false")
                    .getBytes(StandardCharsets.UTF_8);
        }
        int length = Math.min(bytes.length, CRASH_MAX_BYTES);
        if (length < bytes.length) {
            // Cut on a character boundary, with room for a marker at the end: the report opens
            // with "complete: true" and a stack trace can outrun the ceiling on its own, so a
            // file cut in the middle of a byte sequence and claiming to be whole misled the
            // reader twice. The block appended last is the one that goes, and the marker says
            // so; a cut inside a multi-byte character read back as a replacement character.
            byte[] marker = CRASH_TRUNCATED_MARKER.getBytes(StandardCharsets.UTF_8);
            length = Math.max(0, CRASH_MAX_BYTES - marker.length);
            while (length > 0 && (bytes[length] & 0xC0) == 0x80) length--;
            byte[] cut = new byte[length + marker.length];
            System.arraycopy(bytes, 0, cut, 0, length);
            System.arraycopy(marker, 0, cut, length, marker.length);
            bytes = cut;
            length = cut.length;
        }
        AtomicFile atomicFile = new AtomicFile(new File(context.getFilesDir(), fileName));

        synchronized (CRASH_FILE_LOCK) {
            FileOutputStream output = null;
            try {
                output = atomicFile.startWrite();
                output.write(bytes, 0, length);
                output.flush();
                output.getFD().sync();
                atomicFile.finishWrite(output);
            } catch (Exception exception) {
                if (output != null) atomicFile.failWrite(output);
                throw exception;
            }
        }
    }

    public static String readCrashReport(Context context) {
        return readCrashReport(context, CRASH_FILE);
    }

    public static String readNpthCrashReport(Context context) {
        return readCrashReport(context, NPTH_CRASH_FILE);
    }

    private static String readCrashReport(Context context, String fileName) {
        if (context == null) return "";
        AtomicFile atomicFile = new AtomicFile(new File(context.getFilesDir(), fileName));

        synchronized (CRASH_FILE_LOCK) {
            // Read through the AtomicFile the report was written with. A process that died in
            // the middle of a write left the last whole report in the .bak file, which openRead()
            // puts back before it opens anything; reading the base file directly read the half
            // written one, or nothing at all, as though no crash had been saved.
            try (FileInputStream input = atomicFile.openRead()) {
                long length = atomicFile.getBaseFile().length();
                if (length <= 0 || length > CRASH_MAX_BYTES) return "";
                byte[] data = new byte[(int) length];
                int offset = 0;
                while (offset < data.length) {
                    int read = input.read(data, offset, data.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                return new String(data, 0, offset, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    public static void clearLogBuffer() {
        boolean restorable;
        synchronized (CLEAR_UNDO_LOCK) {
            Context context = Utils.getContext();
            ClearSnapshot removed = new ClearSnapshot(
                    new ArrayList<>(logBuffer),
                    app.morphe.extension.shared.diagnostics.HookStatus.snapshotAndClear(),
                    app.morphe.extension.shared.diagnostics.FeedFilterCounters.snapshotAndClear(),
                    readCrashReport(context),
                    readNpthCrashReport(context)
            );
            clearLogBufferData();
            clearCrashReports(context);
            lastClear = removed.isEmpty() ? null : removed;
            restorable = lastClear != null;
        }
        Utils.showToastShort(restorable
                ? say(clearedMessage, L10n.t("Diagnostic data cleared. Tap again to put it back."))
                : say(nothingToClearMessage, L10n.t("There is no diagnostic data to clear.")));
    }

    /** True while the clear row's next tap can restore what its previous tap removed. */
    public static boolean canUndoClear() {
        synchronized (CLEAR_UNDO_LOCK) {
            return lastClear != null;
        }
    }

    /** Restores the most recent clear while retaining events and hook findings recorded since. */
    public static UndoResult undoClear() {
        UndoResult result;
        synchronized (CLEAR_UNDO_LOCK) {
            ClearSnapshot saved = lastClear;
            if (saved == null) {
                result = UndoResult.NOTHING_TO_RESTORE;
            } else {
                try {
                    Context context = Utils.getContext();
                    restoreCrashReportIfMissing(context, CRASH_FILE, saved.javaCrash);
                    restoreCrashReportIfMissing(context, NPTH_CRASH_FILE, saved.nativeCrash);
                    restoreLogBufferData(saved.events);
                    app.morphe.extension.shared.diagnostics.HookStatus.restore(saved.hooks);
                    app.morphe.extension.shared.diagnostics.FeedFilterCounters.restore(saved.feedFilter);
                    lastClear = null;
                    result = UndoResult.RESTORED;
                } catch (Exception error) {
                    Logger.printException(() -> "Failed to restore cleared diagnostics", error);
                    result = UndoResult.FAILED;
                }
            }
        }

        if (result == UndoResult.RESTORED) {
            Utils.showToastShort(say(restoredMessage, L10n.t("Diagnostic data put back.")));
        } else if (result == UndoResult.NOTHING_TO_RESTORE) {
            Utils.showToastShort(say(nothingToRestoreMessage,
                    L10n.t("There is no diagnostic data to put back.")));
        } else {
            Utils.showToastLong(say(restoreFailedMessage,
                    L10n.t("Couldn't put back the diagnostic data. Try again.")));
        }
        return result;
    }

    private static void clearLogBufferData() {
        while (!logBuffer.isEmpty()) {
            DiagnosticEvent removed = logBuffer.pollFirst();
            if (removed != null) logBufferCharSize.addAndGet(-removed.format().length());
        }
    }

    private static void restoreLogBufferData(List<DiagnosticEvent> saved) {
        // Older saved events go in front of anything recorded after the clear. If the combined
        // buffer is too large, the usual oldest-first bound applies.
        for (int index = saved.size() - 1; index >= 0; index--) {
            DiagnosticEvent event = saved.get(index);
            logBuffer.addFirst(event);
            logBufferCharSize.addAndGet(event.format().length());
        }
        while (logBufferCharSize.get() > BUFFER_MAX_CHARS || logBuffer.size() > BUFFER_MAX_SIZE) {
            DiagnosticEvent removed = logBuffer.pollFirst();
            if (removed == null) break;
            logBufferCharSize.addAndGet(-removed.format().length());
        }
    }

    private static void restoreCrashReportIfMissing(
            Context context,
            String fileName,
            String report
    ) throws Exception {
        if (report.isEmpty()) return;
        if (context == null) throw new IOException("Application context unavailable");
        // A newer crash is better evidence than the one that was cleared. Undo fills only the
        // empty slot so it cannot erase a crash recorded after the first tap.
        synchronized (CRASH_FILE_LOCK) {
            if (!readCrashReport(context, fileName).isEmpty()) return;
            persistCrashReportLocked(context, fileName, report);
        }
    }

    private static void clearCrashReports(Context context) {
        if (context == null) return;
        synchronized (CRASH_FILE_LOCK) {
            new AtomicFile(new File(context.getFilesDir(), CRASH_FILE)).delete();
            new AtomicFile(new File(context.getFilesDir(), NPTH_CRASH_FILE)).delete();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    /** The stamp every Hushfacebook export is named with. UTC, so two exports sort together. */
    public static String fileTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
