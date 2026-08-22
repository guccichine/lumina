package app.lumina;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;

final class PhotoIndex {
    private final Context ctx; private final File root; private JSONObject index;
    PhotoIndex(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.root = new File(this.ctx.getExternalFilesDir(null), "photos");
        root.mkdirs(); load();
    }
    File root() { return root; }
    synchronized JSONObject state(String query, String type, boolean trash) throws Exception {
        JSONObject photos = index.optJSONObject("photos"); if (photos == null) photos = new JSONObject();
        JSONArray list = new JSONArray(); JSONObject counts = new JSONObject();
        for (String t : Classifier.TYPES) counts.put(t, 0);
        int trashCount = 0; Iterator<String> keys = photos.keys();
        while (keys.hasNext()) {
            JSONObject p = photos.getJSONObject(keys.next());
            if (p.optBoolean("trashed")) { trashCount++; if (trash && matches(p, query, null)) list.put(p); continue; }
            String t = p.optString("type", "other"); counts.put(t, counts.optInt(t) + 1);
            if (!trash && matches(p, query, type)) list.put(p);
        }
        JSONArray albums = new JSONArray();
        for (String t : Classifier.TYPES) {
            JSONObject a = new JSONObject(); a.put("id", t); a.put("label", Classifier.label(t));
            a.put("count", counts.optInt(t)); a.put("cover", cover(photos, t)); albums.put(a);
        }
        int total = 0; for (String t : Classifier.TYPES) total += counts.optInt(t);
        JSONObject out = new JSONObject();
        out.put("library", root.getAbsolutePath()); out.put("organized", true); out.put("total", total);
        out.put("trash_count", trashCount); out.put("albums", albums); out.put("photos", list);
        out.put("query", query == null ? "" : query); out.put("type", type); return out;
    }
    synchronized JSONObject scan() throws Exception { walk(root); save(); return state("", null, false); }
    synchronized JSONObject sort() throws Exception {
        JSONObject photos = index.getJSONObject("photos"); Iterator<String> keys = photos.keys(); int moved = 0;
        while (keys.hasNext()) { JSONObject p = photos.getJSONObject(keys.next()); if (!p.optBoolean("trashed") && moveToType(p)) moved++; }
        save(); JSONObject out = state("", null, false); out.put("moved", moved); return out;
    }
    synchronized JSONObject delete(JSONArray ids, String type) throws Exception {
        JSONObject photos = index.getJSONObject("photos"); JSONArray deleted = new JSONArray();
        File trash = new File(root, ".lumina/trash"); trash.mkdirs();
        Iterator<String> keys = photos.keys();
        while (keys.hasNext()) {
            String id = keys.next(); JSONObject p = photos.getJSONObject(id); boolean hit = false;
            if (ids != null) for (int i = 0; i < ids.length(); i++) if (id.equals(ids.optString(i))) hit = true;
            else if (type != null && type.equals(p.optString("type")) && !p.optBoolean("trashed")) hit = true;
            if (!hit || p.optBoolean("trashed")) continue;
            File src = new File(root, p.optString("rel_path")); File dest = unique(new File(trash, p.optString("filename")));
            if (src.exists()) src.renameTo(dest);
            p.put("trashed", true); p.put("rel_path", ".lumina/trash/" + dest.getName()); deleted.put(id);
        }
        save(); JSONObject out = state("", null, false); out.put("deleted", deleted); return out;
    }
    synchronized JSONObject restore(JSONArray ids) throws Exception {
        JSONObject photos = index.getJSONObject("photos"); JSONArray restored = new JSONArray();
        for (int i = 0; i < ids.length(); i++) {
            JSONObject p = photos.optJSONObject(ids.optString(i)); if (p == null || !p.optBoolean("trashed")) continue;
            File src = new File(root, p.optString("rel_path")); p.put("trashed", false); moveToType(p);
            File dest = new File(root, p.optString("rel_path")); dest.getParentFile().mkdirs();
            if (src.exists()) src.renameTo(dest); restored.put(ids.optString(i));
        }
        save(); JSONObject out = state("", null, true); out.put("restored", restored); return out;
    }
    synchronized JSONObject emptyTrash() throws Exception {
        JSONObject photos = index.getJSONObject("photos"); JSONArray removed = new JSONArray(); JSONObject keep = new JSONObject();
        Iterator<String> keys = photos.keys();
        while (keys.hasNext()) {
            String id = keys.next(); JSONObject p = photos.getJSONObject(id);
            if (p.optBoolean("trashed")) { File f = new File(root, p.optString("rel_path")); if (f.exists()) f.delete(); removed.put(id); }
            else keep.put(id, p);
        }
        index.put("photos", keep); save(); JSONObject out = state("", null, true); out.put("removed", removed); return out;
    }
    synchronized JSONObject ingestBytes(String filename, byte[] data) throws Exception {
        File inbox = new File(root, "_Inbox"); inbox.mkdirs();
        File dest = unique(new File(inbox, safeName(filename)));
        try (FileOutputStream out = new FileOutputStream(dest)) { out.write(data); }
        JSONObject photo = ingest(dest); moveToType(photo); save(); return photo;
    }
    synchronized JSONObject setType(String id, String type) throws Exception {
        JSONObject p = index.getJSONObject("photos").getJSONObject(id);
        p.put("type", type); p.put("source", "manual"); p.put("confidence", 1); moveToType(p); save(); return p;
    }
    synchronized File fileFor(String id) throws Exception {
        return new File(root, index.getJSONObject("photos").getJSONObject(id).getString("rel_path"));
    }
    synchronized JSONObject importGallery() throws Exception {
        ContentResolver cr = ctx.getContentResolver(); Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME}; int imported = 0;
        try (Cursor c = cr.query(uri, proj, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c == null) return state("", null, false);
            while (c.moveToNext() && imported < 200) {
                long id = c.getLong(0); String name = c.getString(1); if (name == null) name = id + ".jpg";
                if (hasFilename(name)) continue;
                Uri item = Uri.withAppendedPath(uri, Long.toString(id));
                try (InputStream in = cr.openInputStream(item)) {
                    if (in == null) continue; ingestBytes(name, Io.readAll(in)); imported++;
                } catch (Exception ignored) {}
            }
        }
        JSONObject out = scan(); out.put("imported", imported); return out;
    }
    private void walk(File dir) throws Exception {
        File[] files = dir.listFiles(); if (files == null) return;
        for (File f : files) {
            if (f.getName().equals(".lumina")) continue;
            if (f.isDirectory()) walk(f);
            else if (isImage(f.getName())) {
                String rel = rel(f);
                if (!findByRel(rel)) { JSONObject p = ingest(f); String parent = f.getParentFile().getName();
                    if ("_Inbox".equals(parent) || f.getParentFile().equals(root)) moveToType(p); }
            }
        }
    }
    private JSONObject ingest(File file) throws Exception {
        String rel = rel(file); Classifier.Result r = Classifier.classify(file.getName(), rel, 0, 0);
        String id = sha(rel).substring(0, 12); JSONObject p = new JSONObject();
        p.put("id", id); p.put("rel_path", rel); p.put("filename", file.getName()); p.put("type", r.type);
        p.put("confidence", r.confidence); p.put("reasons", new JSONArray().put(r.reason));
        p.put("width", 0); p.put("height", 0); p.put("taken_at", ""); p.put("mtime", file.lastModified() / 1000);
        p.put("size", file.length()); p.put("camera", ""); p.put("ext", ext(file.getName())); p.put("source", "heuristic");
        index.getJSONObject("photos").put(id, p); return p;
    }
    private boolean moveToType(JSONObject p) {
        try {
            File src = new File(root, p.getString("rel_path")); File destDir = new File(root, Classifier.label(p.optString("type", "other"))); destDir.mkdirs();
            File dest = unique(new File(destDir, p.getString("filename")));
            if (src.getCanonicalPath().equals(dest.getCanonicalPath())) return false;
            if (src.exists()) src.renameTo(dest); p.put("rel_path", rel(dest)); p.put("filename", dest.getName()); return true;
        } catch (Exception e) { return false; }
    }
    private boolean matches(JSONObject p, String query, String type) {
        if (type != null && !type.equals(p.optString("type"))) return false;
        if (query == null || query.trim().isEmpty()) return true;
        String blob = (p.optString("filename") + " " + p.optString("type") + " " + p.optString("rel_path") + " " + Classifier.label(p.optString("type"))).toLowerCase(Locale.US);
        for (String tok : query.toLowerCase(Locale.US).split("\\s+")) if (!blob.contains(tok)) return false; return true;
    }
    private String cover(JSONObject photos, String type) throws Exception {
        Iterator<String> keys = photos.keys();
        while (keys.hasNext()) { JSONObject p = photos.getJSONObject(keys.next()); if (!p.optBoolean("trashed") && type.equals(p.optString("type"))) return p.optString("id"); }
        return null;
    }
    private boolean findByRel(String rel) throws Exception {
        JSONObject photos = index.getJSONObject("photos"); Iterator<String> keys = photos.keys();
        while (keys.hasNext()) if (rel.equals(photos.getJSONObject(keys.next()).optString("rel_path"))) return true; return false;
    }
    private boolean hasFilename(String name) throws Exception {
        JSONObject photos = index.getJSONObject("photos"); Iterator<String> keys = photos.keys();
        while (keys.hasNext()) if (name.equals(photos.getJSONObject(keys.next()).optString("filename"))) return true; return false;
    }
    private void load() {
        try { File f = new File(root, ".lumina/index.json");
            index = f.exists() ? new JSONObject(read(f)) : new JSONObject().put("photos", new JSONObject()).put("organized", true);
        } catch (Exception e) { index = new JSONObject(); try { index.put("photos", new JSONObject()); } catch (Exception ignored) {} }
    }
    private void save() throws Exception { File dir = new File(root, ".lumina"); dir.mkdirs(); write(new File(dir, "index.json"), index.toString(2)); }
    private String rel(File f) { return root.toURI().relativize(f.toURI()).getPath(); }
    private static File unique(File dest) {
        if (!dest.exists()) return dest; String stem = dest.getName(), ext = ""; int dot = stem.lastIndexOf('.');
        if (dot > 0) { ext = stem.substring(dot); stem = stem.substring(0, dot); }
        for (int i = 2; i < 400; i++) { File c = new File(dest.getParentFile(), stem + "_" + i + ext); if (!c.exists()) return c; } return dest;
    }
    static boolean isImage(String name) { String n = name.toLowerCase(Locale.US); return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".heic") || n.endsWith(".bmp"); }
    static String safeName(String name) { int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')); return slash >= 0 ? name.substring(slash + 1) : name; }
    static String ext(String name) { int dot = name.lastIndexOf('.'); return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : ""; }
    static String sha(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)); StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b)); return sb.toString();
    }
    static String read(File f) throws Exception { try (FileInputStream in = new FileInputStream(f)) { return new String(Io.readAll(in), StandardCharsets.UTF_8); } }
    static void write(File f, String s) throws Exception { try (FileOutputStream out = new FileOutputStream(f)) { out.write(s.getBytes(StandardCharsets.UTF_8)); } }
}
