package app.lumina;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class GoogleSync {
    private static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String USERINFO = "https://www.googleapis.com/oauth2/v2/userinfo";
    private static final String PHOTOS = "https://photoslibrary.googleapis.com/v1";
    private static final String SCOPES = "openid email profile https://www.googleapis.com/auth/photoslibrary.readonly https://www.googleapis.com/auth/photoslibrary.appendonly";
    private final SharedPreferences prefs;

    GoogleSync(Context ctx) { prefs = ctx.getSharedPreferences("lumina", Context.MODE_PRIVATE); }
    String redirectUri() { return "http://127.0.0.1:8787/api/auth/google/callback"; }
    void saveApp(String id, String secret) { prefs.edit().putString("client_id", id).putString("client_secret", secret).apply(); }
    String clientId() { return prefs.getString("client_id", ""); }
    String clientSecret() { return prefs.getString("client_secret", ""); }

    JSONObject status() throws Exception {
        JSONObject g = new JSONObject();
        g.put("configured", !clientId().isEmpty() && !clientSecret().isEmpty());
        g.put("connected", !prefs.getString("access_token", "").isEmpty() || !prefs.getString("refresh_token", "").isEmpty());
        g.put("email", prefs.getString("email", ""));
        g.put("name", prefs.getString("name", ""));
        JSONObject s = new JSONObject();
        s.put("connected", false); s.put("folder", ""); s.put("public_api", false);
        s.put("note", "On this phone, Import from Samsung Gallery reads the device Gallery. Cloud Samsung Photos has no public API — if Gallery backs up to Google Photos, sign in with Google.");
        JSONObject out = new JSONObject();
        out.put("google", g); out.put("samsung", s);
        out.put("redirect_uri", redirectUri());
        out.put("apk_url", "https://github.com/guccichine/lumina/releases/latest");
        out.put("repo_url", "https://github.com/guccichine/lumina");
        return out;
    }

    String authUrl() throws Exception {
        if (clientId().isEmpty()) throw new IllegalStateException("Add a Google OAuth client ID first");
        return AUTH + "?client_id=" + enc(clientId()) + "&redirect_uri=" + enc(redirectUri())
                + "&response_type=code&access_type=offline&prompt=consent&include_granted_scopes=true&scope=" + enc(SCOPES);
    }

    JSONObject exchange(String code) throws Exception {
        String body = "code=" + enc(code) + "&client_id=" + enc(clientId()) + "&client_secret=" + enc(clientSecret())
                + "&redirect_uri=" + enc(redirectUri()) + "&grant_type=authorization_code";
        JSONObject tok = new JSONObject(http("POST", TOKEN, body.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded", null));
        prefs.edit().putString("access_token", tok.optString("access_token"))
                .putString("refresh_token", tok.optString("refresh_token", prefs.getString("refresh_token", "")))
                .putLong("obtained_at", System.currentTimeMillis())
                .putInt("expires_in", tok.optInt("expires_in", 3600)).apply();
        try {
            JSONObject info = new JSONObject(http("GET", USERINFO, null, null, accessToken()));
            prefs.edit().putString("email", info.optString("email")).putString("name", info.optString("name")).apply();
        } catch (Exception ignored) {}
        return status();
    }

    JSONObject disconnect() throws Exception {
        prefs.edit().remove("access_token").remove("refresh_token").remove("email").remove("name").apply();
        return status();
    }

    JSONObject pull(PhotoIndex store, int limit) throws Exception {
        String token = accessToken(); int pulled = 0; String page = ""; int remaining = Math.min(Math.max(limit, 1), 80);
        while (remaining > 0) {
            String url = PHOTOS + "/mediaItems?pageSize=" + Math.min(50, remaining) + (page.isEmpty() ? "" : "&pageToken=" + enc(page));
            JSONObject data = new JSONObject(http("GET", url, null, null, token));
            JSONArray items = data.optJSONArray("mediaItems");
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length() && remaining > 0; i++) {
                JSONObject item = items.getJSONObject(i);
                if (!item.optString("mimeType").startsWith("image/")) continue;
                String name = item.optString("filename", item.optString("id") + ".jpg");
                String base = item.optString("baseUrl"); if (base.isEmpty()) continue;
                store.ingestBytes(name, httpBytes("GET", base + "=d", null, null, token));
                pulled++; remaining--;
            }
            page = data.optString("nextPageToken"); if (page.isEmpty()) break;
        }
        JSONObject out = store.scan(); out.put("pulled", pulled); out.put("accounts", status()); return out;
    }

    JSONObject push(PhotoIndex store, JSONArray ids, String type, int limit) throws Exception {
        String token = accessToken(); String albumId = ensureAlbum(token);
        JSONArray photos = store.state("", type, false).getJSONArray("photos");
        int pushed = 0; int max = Math.min(Math.max(limit, 1), 40);
        for (int i = 0; i < photos.length() && pushed < max; i++) {
            JSONObject p = photos.getJSONObject(i);
            if (ids != null && ids.length() > 0 && !contains(ids, p.optString("id"))) continue;
            File file = store.fileFor(p.getString("id"));
            byte[] raw; try (FileInputStream in = new FileInputStream(file)) { raw = Io.readAll(in); }
            String uploadToken = uploadBytes(token, raw, mime(p.optString("ext")), p.optString("filename"));
            batchCreate(token, uploadToken, p.optString("filename"), albumId); pushed++;
        }
        JSONObject out = store.state("", null, false); out.put("pushed", pushed); out.put("accounts", status()); return out;
    }

    private String ensureAlbum(String token) throws Exception {
        JSONObject body = new JSONObject().put("album", new JSONObject().put("title", "Lumina"));
        return new JSONObject(http("POST", PHOTOS + "/albums", body.toString().getBytes(StandardCharsets.UTF_8), "application/json", token)).optString("id");
    }
    private String uploadBytes(String token, byte[] raw, String mime, String filename) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(PHOTOS + "/uploads").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setRequestProperty("X-Goog-Upload-Protocol", "raw");
        c.setRequestProperty("X-Goog-Upload-Content-Type", mime);
        c.setRequestProperty("X-Goog-Upload-File-Name", filename);
        try (OutputStream os = c.getOutputStream()) { os.write(raw); }
        return new String(readAll(c.getInputStream()), StandardCharsets.UTF_8).trim();
    }
    private void batchCreate(String token, String uploadToken, String filename, String albumId) throws Exception {
        JSONObject item = new JSONObject().put("description", "Uploaded from Lumina")
                .put("simpleMediaItem", new JSONObject().put("fileName", filename).put("uploadToken", uploadToken));
        JSONObject body = new JSONObject().put("newMediaItems", new JSONArray().put(item));
        if (!albumId.isEmpty()) body.put("albumId", albumId);
        http("POST", PHOTOS + "/mediaItems:batchCreate", body.toString().getBytes(StandardCharsets.UTF_8), "application/json", token);
    }
    private String accessToken() throws Exception {
        String token = prefs.getString("access_token", "");
        long obtained = prefs.getLong("obtained_at", 0); int expires = prefs.getInt("expires_in", 3600);
        if (!token.isEmpty() && System.currentTimeMillis() < obtained + (expires - 60) * 1000L) return token;
        String refresh = prefs.getString("refresh_token", "");
        if (refresh.isEmpty()) { if (!token.isEmpty()) return token; throw new IllegalStateException("Not signed into Google Photos"); }
        String body = "client_id=" + enc(clientId()) + "&client_secret=" + enc(clientSecret()) + "&refresh_token=" + enc(refresh) + "&grant_type=refresh_token";
        JSONObject tok = new JSONObject(http("POST", TOKEN, body.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded", null));
        prefs.edit().putString("access_token", tok.optString("access_token")).putLong("obtained_at", System.currentTimeMillis()).putInt("expires_in", tok.optInt("expires_in", 3600)).apply();
        return tok.getString("access_token");
    }
    private static boolean contains(JSONArray ids, String id) { for (int i = 0; i < ids.length(); i++) if (id.equals(ids.optString(i))) return true; return false; }
    private static String mime(String ext) {
        return switch (ext) { case "png" -> "image/png"; case "gif" -> "image/gif"; case "webp" -> "image/webp"; default -> "image/jpeg"; };
    }
    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }
    private static String http(String method, String url, byte[] body, String contentType, String bearer) throws Exception {
        return new String(httpBytes(method, url, body, contentType, bearer), StandardCharsets.UTF_8);
    }
    private static byte[] httpBytes(String method, String url, byte[] body, String contentType, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(30000); c.setReadTimeout(120000);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (contentType != null) c.setRequestProperty("Content-Type", contentType);
        if (body != null) { c.setDoOutput(true); try (OutputStream os = c.getOutputStream()) { os.write(body); } }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] data = readAll(in);
        if (code >= 400) throw new IllegalStateException("Google Photos error " + code + ": " + new String(data, StandardCharsets.UTF_8));
        return data;
    }
    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n); return bos.toByteArray();
    }
}
