package com.dsh.llamahub;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Shared, file-backed conversation store used by both the history list and the chat screen. */
public final class HistoryStore {

    private static final Object LOCK = new Object();
    private static final int MAX_ENTRIES = 100;

    private HistoryStore() {
    }

    public static File file(Context c) {
        return new File(c.getFilesDir(), "history.json");
    }

    public static List<JSONObject> load(Context c) {
        synchronized (LOCK) {
            return loadLocked(c);
        }
    }

    private static List<JSONObject> loadLocked(Context c) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        File f = file(c);
        if (!f.isFile()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(new String(readAll(f), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(o);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void save(Context c, List<JSONObject> list) {
        synchronized (LOCK) {
            writeLocked(c, list);
        }
    }

    public static void upsert(Context c, JSONObject entry) {
        synchronized (LOCK) {
            List<JSONObject> list = loadLocked(c);
            String id = entry.optString("id");
            for (int i = list.size() - 1; i >= 0; i--) {
                if (id.equals(list.get(i).optString("id"))) {
                    list.remove(i);
                }
            }
            list.add(0, entry);
            while (list.size() > MAX_ENTRIES) {
                list.remove(list.size() - 1);
            }
            writeLocked(c, list);
        }
    }

    public static void clear(Context c) {
        synchronized (LOCK) {
            File f = file(c);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    public static JSONObject find(Context c, String id) {
        synchronized (LOCK) {
            for (JSONObject o : loadLocked(c)) {
                if (id != null && id.equals(o.optString("id"))) {
                    return o;
                }
            }
            return null;
        }
    }

    private static void writeLocked(Context c, List<JSONObject> list) {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : list) {
                arr.put(o);
            }
            File f = file(c);
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(arr.toString().getBytes("UTF-8"));
                fos.flush();
            } finally {
                fos.close();
            }
            if (f.exists() && !f.delete()) {
                // ignore
            }
            if (!tmp.renameTo(f)) {
                // ignore
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        FileInputStream fis = new FileInputStream(f);
        try {
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        } finally {
            fis.close();
        }
        return buf;
    }
}
