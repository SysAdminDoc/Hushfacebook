/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.shared.diagnostics;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class DiagnosticEvent {
    /**
     * One formatter per thread, kept for the life of the thread.
     *
     * <p>{@link SimpleDateFormat} is not thread safe, so it cannot simply be a static, and
     * building one per call was costing a formatter, a time zone lookup and a pattern parse on
     * every logged line.
     */
    private static final ThreadLocal<SimpleDateFormat> TIMESTAMP = ThreadLocal.withInitial(() -> {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    });

    public final DiagnosticCategory category;
    public final long timestamp;
    public final String thread;
    public final String source;
    public final String level;
    public final String message;

    /**
     * The line this event reads as, built once.
     *
     * <p>It was built on demand, and every build was a fresh {@link SimpleDateFormat}, a time
     * zone lookup and a pattern parse. Demand is more than one: appending an event asks for its
     * length, and evicting one asks again, which every event pays once the buffer is full. The
     * export, the crash snapshot and the clear and undo paths each ask again on top of that.
     * The eviction is the one that runs inside the buffer's lock, so it is paid by whichever
     * thread happened to be logging while every other logging thread waits behind it; the
     * append's own call is deliberately outside the lock. Every event that is built is appended,
     * so there is nothing to defer.
     */
    private final String formatted;

    public DiagnosticEvent(
            DiagnosticCategory category,
            long timestamp,
            String thread,
            String source,
            String level,
            String message
    ) {
        this.category = category;
        this.timestamp = timestamp;
        this.thread = thread;
        this.source = source;
        this.level = level;
        this.message = message;
        this.formatted = category.value + " | " + TIMESTAMP.get().format(new Date(timestamp))
                + " | " + thread + " | " + source + " | " + level + " | " + message;
    }

    public String format() {
        return formatted;
    }
}
