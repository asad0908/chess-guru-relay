package com.chessguru.relay;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP/1.1 relay (same rules as relay/relay.js):
 *   GET /health                      -> {"ok":true}
 *   ANY /p/&lt;target&gt;/&lt;path&gt;?q  -> https://&lt;allowlisted host&gt;/&lt;path&gt;?q   (requires X-Relay-Secret)
 * One request per connection (Connection: close). Responses are streamed through and flushed per read,
 * so NDJSON game/event streams arrive immediately. Closing the client connection aborts the upstream request.
 * Headers and bodies are never logged.
 */
public final class RelayServer {
    public static final String VERSION = "1.0.0-android";
    static final Map<String, String> TARGETS = new HashMap<>();
    static {
        TARGETS.put("lichess", "lichess.org");
        TARGETS.put("explorer", "explorer.lichess.ovh");
        TARGETS.put("tablebase", "tablebase.lichess.ovh");
        TARGETS.put("database", "database.lichess.org");
        TARGETS.put("chesscom", "api.chess.com");
    }
    private static final String[] FORWARD_REQ = {"authorization", "content-type", "accept"};
    private static final String[] FORWARD_RES = {"content-type", "retry-after", "x-ratelimit-remaining", "x-ratelimit-reset", "cache-control", "last-modified", "etag"};
    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    public interface Listener {
        void onLog(String line);
    }

    private final int port;
    private final byte[] secret;
    private final Listener listener;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, long[]> failures = new ConcurrentHashMap<>(); // ip -> {count, since}
    private volatile ServerSocket server;
    private volatile boolean running;

    public RelayServer(int port, String secret, Listener listener) {
        if (secret == null || secret.length() < 24) throw new IllegalArgumentException("The secret must be at least 24 characters");
        this.port = port;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.listener = listener;
    }

    public void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
        server = s;
        running = true;
        pool.execute(() -> {
            while (running) {
                try {
                    Socket c = s.accept();
                    pool.execute(() -> handle(c));
                } catch (IOException e) {
                    if (running) log("accept error: " + e.getMessage());
                }
            }
        });
        log("listening on port " + port);
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
        log("stopped");
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }

    private static final class Request {
        String method;
        String target; // path + query
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
    }

    private void handle(Socket client) {
        String ip = client.getInetAddress().getHostAddress();
        try (Socket c = client) {
            c.setSoTimeout(30_000); // for reading the request only
            InputStream in = new BufferedInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();
            Request req = readRequest(in);
            if (req == null) return;
            c.setSoTimeout(0);
            String path = req.target;
            String query = "";
            int qi = path.indexOf('?');
            if (qi >= 0) {
                query = path.substring(qi);
                path = path.substring(0, qi);
            }
            if (path.equals("/health")) {
                sendJson(out, 200, "{\"ok\":true,\"version\":\"" + VERSION + "\",\"targets\":[\"lichess\",\"explorer\",\"tablebase\",\"database\",\"chesscom\"]}");
                return;
            }
            long[] f = failures.get(ip);
            if (f != null && f[0] >= 20 && System.currentTimeMillis() - f[1] < 15 * 60_000L) {
                sendJson(out, 429, "{\"error\":\"Too many failed attempts\"}");
                return;
            }
            String given = req.headers.get("x-relay-secret");
            if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), secret)) {
                if (f == null || System.currentTimeMillis() - f[1] > 15 * 60_000L) failures.put(ip, new long[]{1, System.currentTimeMillis()});
                else f[0]++;
                sendJson(out, 401, "{\"error\":\"Missing or wrong relay secret\"}");
                log(req.method + " " + path + " -> 401 (bad secret from " + ip + ")");
                return;
            }
            if (!path.startsWith("/p/")) {
                sendJson(out, 404, "{\"error\":\"Unknown target\"}");
                return;
            }
            int slash = path.indexOf('/', 3);
            if (slash < 0) {
                sendJson(out, 404, "{\"error\":\"Unknown target\"}");
                return;
            }
            String targetName = path.substring(3, slash);
            String host = TARGETS.get(targetName);
            if (host == null || !targetName.matches("[a-z]+")) {
                sendJson(out, 404, "{\"error\":\"Unknown target\"}");
                return;
            }
            String m = req.method;
            if (!(m.equals("GET") || m.equals("POST") || m.equals("DELETE") || m.equals("HEAD"))) {
                sendJson(out, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            forward(c, in, out, req, host, path.substring(slash) + query, targetName);
        } catch (Exception e) {
            log("error: " + e.getClass().getSimpleName());
        }
    }

    private Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            if (head.size() > MAX_HEADER_BYTES) return null;
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = b == '\r' ? 1 : 0;
        }
        String[] lines = head.toString("ISO-8859-1").split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 3) return null;
        Request r = new Request();
        r.method = first[0].toUpperCase(Locale.ROOT);
        r.target = first[1];
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        String cl = r.headers.get("content-length");
        if (cl != null) {
            int len = Integer.parseInt(cl.trim());
            if (len < 0 || len > MAX_BODY) throw new IOException("body too large");
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n < 0) throw new IOException("short body");
                off += n;
            }
            r.body = body;
        } else if ("chunked".equalsIgnoreCase(r.headers.get("transfer-encoding"))) {
            throw new IOException("chunked request bodies are not supported");
        }
        return r;
    }

    private void forward(Socket client, InputStream clientIn, OutputStream out, Request req, String host, String pathAndQuery, String targetName) throws IOException {
        URL url = new URL("https://" + host + pathAndQuery);
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(req.method);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(0); // long-lived streams
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", "chess-guru-relay/" + VERSION);
        for (String h : FORWARD_REQ) {
            String v = req.headers.get(h);
            if (v != null) conn.setRequestProperty(h, v);
        }
        // Abort the upstream request as soon as the client goes away (also while the stream is idle).
        Thread watcher = new Thread(() -> {
            try {
                while (clientIn.read() >= 0) { /* ignore anything after the request */ }
            } catch (IOException ignored) {
            }
            conn.disconnect();
        });
        watcher.setDaemon(true);
        watcher.start();
        try {
            if (req.method.equals("POST")) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(req.body.length);
                try (OutputStream up = conn.getOutputStream()) {
                    up.write(req.body);
                }
            }
            int status;
            try {
                status = conn.getResponseCode();
            } catch (IOException e) {
                sendJson(out, 502, "{\"error\":\"Upstream unreachable\"}");
                log(req.method + " " + targetName + " -> 502 (" + e.getClass().getSimpleName() + ")");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
            for (String h : FORWARD_RES) {
                String v = conn.getHeaderField(h);
                if (v != null) sb.append(h).append(": ").append(v).append("\r\n");
            }
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            log(req.method + " " + targetName + " " + stripQuery(pathAndQuery) + " -> " + status);
            if (req.method.equals("HEAD")) return;
            InputStream up = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (up == null) return;
            byte[] buf = new byte[16 * 1024];
            try (InputStream upstream = up) {
                int n;
                while ((n = upstream.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (SocketException ignored) {
                // client went away or upstream was disconnected by the watcher
            }
        } finally {
            conn.disconnect();
            try {
                client.shutdownOutput();
            } catch (IOException ignored) {
            }
        }
    }

    private static String stripQuery(String p) {
        int q = p.indexOf('?');
        return q >= 0 ? p.substring(0, q) : p;
    }

    private static void sendJson(OutputStream out, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int s) {
        switch (s) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 502: return "Bad Gateway";
            default: return "Status";
        }
    }
}
