package app.lumina;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class LocalServer extends Thread {
    private final Context ctx; private final PhotoIndex store; private final GoogleSync google; private final int port;
    private volatile boolean running = true; private ServerSocket server;
    LocalServer(Context ctx, PhotoIndex store, GoogleSync google, int port) {
        this.ctx = ctx.getApplicationContext(); this.store = store; this.google = google; this.port = port;
        setName("lumina-http"); setDaemon(true);
    }
    @Override public void run() {
        try (ServerSocket ss = new ServerSocket(port)) {
            server = ss;
            while (running) { Socket sock = ss.accept(); new Thread(() -> handle(sock), "lumina-conn").start(); }
        } catch (Exception ignored) {}
    }
    void shutdown() { running = false; try { if (server != null) server.close(); } catch (Exception ignored) {} }
    private void handle(Socket sock) {
        try (Socket s = sock; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
            Request req = Request.parse(in); if (req == null) return; respond(req, out);
        } catch (Exception ignored) {}
    }
    private void respond(Request req, OutputStream out) throws Exception {
        String path = req.path;
        try {
            if (path.equals("/") || path.equals("/index.html")) { writeAsset(out, "www/index.html", "text/html; charset=utf-8"); return; }
            if (path.startsWith("/static/")) { String rel = path.substring("/static/".length()); writeAsset(out, "www/" + rel, mimeOf(rel)); return; }
            if (path.equals("/api/state")) { json(out, withAccounts(store.state(req.query.get("q"), req.query.get("type"), "1".equals(req.query.get("trash"))))); return; }
            if (path.equals("/api/accounts")) { json(out, google.status()); return; }
            if (path.equals("/api/auth/google/start")) { redirect(out, google.authUrl()); return; }
            if (path.equals("/api/auth/google/callback")) {
                String err = req.query.get("error");
                if (err != null) { redirect(out, "/?auth_error=" + Uri.encode(err)); return; }
                google.exchange(req.query.get("code")); redirect(out, "/?connected=google"); return;
            }
            if (path.startsWith("/api/thumbs/") || path.startsWith("/api/files/")) {
                File f = store.fileFor(path.substring(path.lastIndexOf('/') + 1)); writeFile(out, f, mimeOf(f.getName())); return;
            }
            if ("POST".equals(req.method)) {
                JSONObject body = req.json();
                switch (path) {
                    case "/api/scan" -> json(out, withAccounts(store.scan()));
                    case "/api/sort" -> json(out, withAccounts(store.sort()));
                    case "/api/delete" -> json(out, withAccounts(store.delete(body.optJSONArray("ids"), body.has("type") ? body.optString("type") : null)));
                    case "/api/restore" -> json(out, withAccounts(store.restore(body.optJSONArray("ids"))));
                    case "/api/empty-trash" -> json(out, withAccounts(store.emptyTrash()));
                    case "/api/refine", "/api/library", "/api/seed" -> json(out, withAccounts(store.state("", null, false)));
                    case "/api/accounts/google" -> { google.saveApp(body.optString("client_id"), body.optString("client_secret")); json(out, google.status()); }
                    case "/api/accounts/google/disconnect" -> json(out, google.disconnect());
                    case "/api/sync/google/pull" -> json(out, google.pull(store, body.optInt("limit", 40)));
                    case "/api/sync/google/push" -> json(out, google.push(store, body.optJSONArray("ids"), body.has("type") ? body.optString("type") : null, body.optInt("limit", 30)));
                    case "/api/accounts/samsung" -> json(out, google.status());
                    case "/api/sync/samsung" -> json(out, withAccounts(store.importGallery()));
                    case "/api/upload" -> json(out, handleUpload(req));
                    default -> error(out, 404, "not found");
                }
                return;
            }
            if ("PATCH".equals(req.method) && path.startsWith("/api/photos/")) {
                json(out, store.setType(path.substring("/api/photos/".length()), req.json().optString("type"))); return;
            }
            error(out, 404, "not found");
        } catch (Exception e) { error(out, 400, e.getMessage() == null ? "error" : e.getMessage()); }
    }
    private JSONObject handleUpload(Request req) throws Exception {
        JSONArray uploaded = new JSONArray();
        if (req.body != null && req.body.length > 0 && req.filename != null) uploaded.put(store.ingestBytes(req.filename, req.body).optString("id"));
        JSONObject state = store.state("", null, false); state.put("uploaded", uploaded); return withAccounts(state);
    }
    private JSONObject withAccounts(JSONObject state) throws Exception { state.put("accounts", google.status()); return state; }
    private void writeAsset(OutputStream out, String name, String mime) throws IOException {
        AssetManager am = ctx.getAssets(); try (InputStream in = am.open(name)) { writeRaw(out, 200, mime, Io.readAll(in)); }
    }
    private void writeFile(OutputStream out, File f, String mime) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) { writeRaw(out, 200, mime, Io.readAll(in)); }
    }
    private void json(OutputStream out, JSONObject obj) throws IOException {
        writeRaw(out, 200, "application/json; charset=utf-8", obj.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void error(OutputStream out, int code, String msg) throws Exception {
        writeRaw(out, code, "application/json; charset=utf-8", new JSONObject().put("error", msg).toString().getBytes(StandardCharsets.UTF_8));
    }
    private void redirect(OutputStream out, String loc) throws IOException {
        out.write(("HTTP/1.1 302 Found\r\nLocation: " + loc + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
    }
    private void writeRaw(OutputStream out, int code, String mime, byte[] data) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + mime + "\r\nContent-Length: " + data.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII)); out.write(data); out.flush();
    }
    private static String mimeOf(String name) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(name);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime != null) return mime;
        if (name.endsWith(".js")) return "text/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".html")) return "text/html";
        return "application/octet-stream";
    }
    static final class Request {
        String method, path, filename; Map<String, String> query = new HashMap<>(); byte[] body = new byte[0];
        JSONObject json() { try { return body.length == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8)); } catch (Exception e) { return new JSONObject(); } }
        static Request parse(InputStream in) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(); int prev = 0, cur;
            while ((cur = in.read()) != -1) { bos.write(cur); if (prev == '\n' && cur == '\r') { int n = in.read(); if (n == '\n') break; bos.write(n); } prev = cur; }
            String header = bos.toString(StandardCharsets.US_ASCII); String[] lines = header.split("\r\n");
            if (lines.length == 0 || lines[0].trim().isEmpty()) return null;
            String[] start = lines[0].split(" "); Request r = new Request(); r.method = start[0];
            String uri = start.length > 1 ? start[1] : "/"; int q = uri.indexOf('?');
            r.path = q >= 0 ? uri.substring(0, q) : uri;
            if (q >= 0) for (String part : uri.substring(q + 1).split("&")) { int eq = part.indexOf('='); if (eq > 0) r.query.put(urldec(part.substring(0, eq)), urldec(part.substring(eq + 1))); }
            int length = 0; String contentType = "";
            for (String line : lines) {
                String low = line.toLowerCase(Locale.US);
                if (low.startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                if (low.startsWith("content-type:")) contentType = line.substring(13).trim();
            }
            if (length > 0) {
                r.body = Io.readN(in, length);
                if (contentType.contains("multipart/form-data")) {
                    String head = new String(r.body, 0, Math.min(r.body.length, 800), StandardCharsets.ISO_8859_1);
                    int i = head.indexOf("filename=\""); r.filename = i < 0 ? "upload.jpg" : head.substring(i + 10, Math.max(i + 10, head.indexOf('"', i + 10)));
                    String asText = new String(r.body, StandardCharsets.ISO_8859_1);
                    int idx = asText.indexOf("\r\n\r\n"); int st = idx < 0 ? 0 : idx + 4; int end = asText.lastIndexOf("\r\n--"); if (end <= st) end = r.body.length;
                    byte[] outb = new byte[end - st]; System.arraycopy(r.body, st, outb, 0, outb.length); r.body = outb;
                }
            }
            return r;
        }
        private static String urldec(String s) { try { return URLDecoder.decode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; } }
    }
}
