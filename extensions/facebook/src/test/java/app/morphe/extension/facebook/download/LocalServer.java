/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A one-connection-at-a-time HTTP server on the loopback address, for the download tests.
 *
 * <p>Hand written because the JDK's own server isn't on Android's unit-test classpath, and because
 * the cases need what a real server won't do on request: announce one length and send another,
 * or send a body with no length at all.
 */
final class LocalServer implements Closeable {

    private static final class Route {
        final int code;
        final String type;
        final byte[] body;
        final long announced;
        final String location;
        /** A body made as it goes out: its whole length, or -1 when [body] is the whole answer. */
        final long generated;
        final long trickleAfter;
        final CountDownLatch release;
        /** Past [trickleAfter], send nothing until released rather than a trickle. */
        final boolean hold;

        Route(int code, String type, byte[] body, long announced, String location) {
            this(code, type, body, announced, location, -1, Long.MAX_VALUE, null);
        }

        Route(int code, String type, byte[] body, long announced, String location, long generated,
                long trickleAfter, CountDownLatch release) {
            this(code, type, body, announced, location, generated, trickleAfter, release, false);
        }

        Route(int code, String type, byte[] body, long announced, String location, long generated,
                long trickleAfter, CountDownLatch release, boolean hold) {
            this.code = code;
            this.type = type;
            this.body = body;
            this.announced = announced;
            this.location = location;
            this.generated = generated;
            this.trickleAfter = trickleAfter;
            this.release = release;
            this.hold = hold;
        }
    }

    /** How much a trickle sends at a time, and how often. */
    private static final int TRICKLE_BYTES = 64 * 1024;
    private static final long TRICKLE_EVERY_MS = 200;

    private final ServerSocket socket;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final Thread thread;

    LocalServer() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        thread = new Thread(this::serve, "local-server");
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    String origin() {
        return "http://127.0.0.1:" + port();
    }

    /** Answer [path] with [body] as [type]; [announced] is the length to claim, or -1 for none. */
    void serve(String path, int code, String type, byte[] body, long announced) {
        routes.put(path, new Route(code, type, body, announced, null));
    }

    void serve(String path, String type, byte[] body) {
        serve(path, 200, type, body, body.length);
    }

    /**
     * Answer [path] with [length] bytes, all announced, that start with [head] and go on with a
     * made-up pattern. After [trickleAfter] bytes only 64 KiB goes out every 200 ms until
     * [release] is counted down, then the rest at once. The body is made as it's sent, so a
     * 100 MiB answer needs no 100 MiB array, and a save of it stays running while a test looks.
     */
    void serveGenerated(String path, String type, byte[] head, long length, long trickleAfter, CountDownLatch release) {
        routes.put(path, new Route(200, type, head, length, null, length, trickleAfter, release));
    }

    /**
     * Like {@link #serveGenerated}, but after [holdAfter] bytes it sends nothing at all until
     * [release] is counted down, so the client's read waits on the network.
     */
    void serveHeld(String path, String type, byte[] head, long length, long holdAfter, CountDownLatch release) {
        routes.put(path, new Route(200, type, head, length, null, length, holdAfter, release, true));
    }

    void redirect(String path, String location) {
        routes.put(path, new Route(302, null, new byte[0], 0, location));
    }

    int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                client.setSoTimeout(10_000);
                answer(client);
            } catch (IOException ignored) {
                // A client that stopped reading, or the server closing.
            }
        }
    }

    private void answer(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
        String request = in.readLine();
        if (request == null) return;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            // Headers are not needed.
        }
        String[] parts = request.split(" ");
        String path = parts.length > 1 ? parts[1] : "/";
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();

        Route route = routes.get(path);
        if (route == null) route = new Route(404, "text/plain", "missing".getBytes(StandardCharsets.UTF_8), 7, null);

        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(route.code).append(" X\r\n");
        head.append("Connection: close\r\n");
        if (route.type != null) head.append("Content-Type: ").append(route.type).append("\r\n");
        if (route.location != null) head.append("Location: ").append(route.location).append("\r\n");
        if (route.announced >= 0) head.append("Content-Length: ").append(route.announced).append("\r\n");
        head.append("\r\n");

        OutputStream out = client.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(route.body);
        if (route.generated >= 0) sendGenerated(out, route);
        out.flush();
    }

    /** The rest of a generated body. A client that goes away ends it with an IOException. */
    private static void sendGenerated(OutputStream out, Route route) throws IOException {
        byte[] chunk = new byte[TRICKLE_BYTES];
        long sent = route.body.length;
        while (sent < route.generated) {
            boolean trickling = sent >= route.trickleAfter && route.release != null && route.release.getCount() > 0;
            if (trickling && route.hold) {
                out.flush();
                try {
                    route.release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            int size = (int) Math.min(chunk.length, route.generated - sent);
            for (int i = 0; i < size; i++) chunk[i] = (byte) ((sent + i) * 13);
            out.write(chunk, 0, size);
            sent += size;
            if (trickling) {
                out.flush();
                try {
                    route.release.await(TRICKLE_EVERY_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
