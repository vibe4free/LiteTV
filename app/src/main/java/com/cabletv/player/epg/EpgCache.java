package com.cabletv.player.epg;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk EPG cache. All methods do blocking file IO and must be called off the main thread.
 */
public class EpgCache {
    private static final String TAG = "EpgCache";
    private static final String CACHE_DIR = "epg_cache";
    private static final String CACHE_FILE = "epg_programs.json";
    private static final String META_FILE = "epg_meta.json";
    private static final int CACHE_VERSION = 1;

    private final File mCacheDir;
    private final File mCacheFile;
    private final File mMetaFile;
    private final Gson mGson;

    /** Save timestamp, kept in memory so freshness checks never re-read the cache. */
    private volatile long mCachedTimestamp = 0;

    public EpgCache(Context context) {
        mCacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!mCacheDir.exists() && !mCacheDir.mkdirs()) {
            Log.w(TAG, "Cannot create EPG cache dir: " + mCacheDir);
        }
        mCacheFile = new File(mCacheDir, CACHE_FILE);
        mMetaFile = new File(mCacheDir, META_FILE);
        // Compact output: this file is only ever read back by the app and can reach several MB.
        mGson = new Gson();
    }

    public void savePrograms(Map<String, List<EpgManager.Program>> programsByChannel) {
        long timestamp = System.currentTimeMillis();
        File tmp = new File(mCacheDir, CACHE_FILE + ".tmp");
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", CACHE_VERSION);
            root.addProperty("timestamp", timestamp);

            JsonObject channelsObj = new JsonObject();
            for (Map.Entry<String, List<EpgManager.Program>> entry : programsByChannel.entrySet()) {
                JsonArray programsArray = new JsonArray();
                for (EpgManager.Program prog : entry.getValue()) {
                    JsonObject progObj = new JsonObject();
                    progObj.addProperty("title", prog.title);
                    progObj.addProperty("startTime", prog.startTime);
                    progObj.addProperty("endTime", prog.endTime);
                    programsArray.add(progObj);
                }
                channelsObj.add(entry.getKey(), programsArray);
            }
            root.add("channels", channelsObj);

            // Write to a temp file first so a crash or a kill never leaves a truncated cache.
            Writer writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"), 32 * 1024);
            try {
                mGson.toJson(root, writer);
                writer.flush();
            } finally {
                closeQuietly(writer);
            }

            if (mCacheFile.exists() && !mCacheFile.delete()) {
                Log.w(TAG, "Cannot replace existing EPG cache");
            }
            if (!tmp.renameTo(mCacheFile)) {
                throw new IOException("Cannot move EPG cache into place");
            }
            writeMeta(timestamp);
            mCachedTimestamp = timestamp;
            Log.d(TAG, "✓ EPG cache saved to " + mCacheFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Error saving EPG cache: " + e.getMessage(), e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    public Map<String, List<EpgManager.Program>> loadPrograms() {
        if (!mCacheFile.exists()) {
            Log.d(TAG, "EPG cache file does not exist");
            return null;
        }

        Reader reader = null;
        try {
            reader = openReader(mCacheFile);
            JsonObject root = mGson.fromJson(reader, JsonObject.class);
            if (root == null) {
                Log.d(TAG, "Failed to parse EPG cache");
                return null;
            }

            int version = root.getAsJsonPrimitive("version").getAsInt();
            if (version != CACHE_VERSION) {
                Log.d(TAG, "EPG cache version mismatch, ignoring");
                return null;
            }
            if (root.has("timestamp")) {
                mCachedTimestamp = root.getAsJsonPrimitive("timestamp").getAsLong();
            }

            Map<String, List<EpgManager.Program>> programsByChannel = new HashMap<>();
            JsonObject channelsObj = root.getAsJsonObject("channels");

            for (String channelId : channelsObj.keySet()) {
                JsonArray programsArray = channelsObj.getAsJsonArray(channelId);
                List<EpgManager.Program> programs = new ArrayList<>();

                for (int i = 0; i < programsArray.size(); i++) {
                    JsonObject progObj = programsArray.get(i).getAsJsonObject();
                    String title = progObj.getAsJsonPrimitive("title").getAsString();
                    long startTime = progObj.getAsJsonPrimitive("startTime").getAsLong();
                    long endTime = progObj.getAsJsonPrimitive("endTime").getAsLong();
                    programs.add(new EpgManager.Program(title, startTime, endTime));
                }

                programsByChannel.put(channelId, programs);
            }

            Log.d(TAG, "✓ EPG cache loaded from " + mCacheFile.getAbsolutePath() +
                    " (" + programsByChannel.size() + " channels)");
            return programsByChannel;
        } catch (Exception e) {
            Log.e(TAG, "Error loading EPG cache: " + e.getMessage(), e);
            return null;
        } finally {
            closeQuietly(reader);
        }
    }

    public void clearCache() {
        mCachedTimestamp = 0;
        if (mCacheFile.exists()) {
            if (mCacheFile.delete()) {
                Log.d(TAG, "✓ EPG cache cleared");
            } else {
                Log.w(TAG, "Failed to delete EPG cache file");
            }
        }
        if (mMetaFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mMetaFile.delete();
        }
    }

    public long getCacheTimestamp() {
        long known = mCachedTimestamp;
        if (known > 0) {
            return known;
        }
        if (!mCacheFile.exists()) {
            return 0;
        }
        long timestamp = readMetaTimestamp();
        if (timestamp <= 0) {
            // Cache written before the sidecar existed: the file's mtime is close enough.
            timestamp = mCacheFile.lastModified();
        }
        mCachedTimestamp = timestamp;
        return timestamp;
    }

    public boolean isCacheValid(int validityHours) {
        long cacheTimestamp = getCacheTimestamp();
        if (cacheTimestamp == 0) {
            return false;
        }
        long ageHours = (System.currentTimeMillis() - cacheTimestamp) / (1000 * 60 * 60);
        return ageHours < validityHours;
    }

    public boolean isCacheTodayVersion() {
        long cacheTimestamp = getCacheTimestamp();
        if (cacheTimestamp == 0) {
            Log.d(TAG, "No EPG cache timestamp available");
            return false;
        }
        boolean isSameDay = isSameLocalDay(cacheTimestamp, System.currentTimeMillis());
        Log.d(TAG, "Cache date check - Same day: " + isSameDay);
        return isSameDay;
    }

    public boolean cacheFileExists() {
        return mCacheFile.exists();
    }

    private void writeMeta(long timestamp) {
        Writer writer = null;
        try {
            JsonObject meta = new JsonObject();
            meta.addProperty("version", CACHE_VERSION);
            meta.addProperty("timestamp", timestamp);
            writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(mMetaFile), "UTF-8"));
            mGson.toJson(meta, writer);
            writer.flush();
        } catch (Exception e) {
            Log.w(TAG, "Cannot write EPG cache metadata: " + e.getMessage());
        } finally {
            closeQuietly(writer);
        }
    }

    private long readMetaTimestamp() {
        if (!mMetaFile.exists()) {
            return 0;
        }
        Reader reader = null;
        try {
            reader = openReader(mMetaFile);
            JsonObject meta = mGson.fromJson(reader, JsonObject.class);
            if (meta != null && meta.has("timestamp")
                    && meta.has("version")
                    && meta.getAsJsonPrimitive("version").getAsInt() == CACHE_VERSION) {
                return meta.getAsJsonPrimitive("timestamp").getAsLong();
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot read EPG cache metadata: " + e.getMessage());
        } finally {
            closeQuietly(reader);
        }
        return 0;
    }

    private static Reader openReader(File file) throws IOException {
        return new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"), 32 * 1024);
    }

    /** Day boundaries follow the device's timezone, not UTC. */
    private static boolean isSameLocalDay(long first, long second) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(first);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(second);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
