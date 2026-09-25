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

        Route(int code, String type, byte[] body, long announced, String location) {
            this.code = code;
            this.type = type;
            this.body = body;
            this.announced = announced;
            this.location = location;
        }
    }

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
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
