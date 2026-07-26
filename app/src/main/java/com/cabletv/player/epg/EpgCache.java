package com.cabletv.player.epg;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EpgCache {
    private static final String TAG = "EpgCache";
    private static final String CACHE_DIR = "epg_cache";
    private static final String CACHE_FILE = "epg_programs.json";
    private static final int CACHE_VERSION = 1;

    private final File mCacheDir;
    private final File mCacheFile;
    private final Gson mGson;

    public EpgCache(Context context) {
        mCacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!mCacheDir.exists()) {
            mCacheDir.mkdirs();
        }
        mCacheFile = new File(mCacheDir, CACHE_FILE);
        mGson = new GsonBuilder().setPrettyPrinting().create();
    }

    public void savePrograms(Map<String, List<EpgManager.Program>> programsByChannel) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", CACHE_VERSION);
            root.addProperty("timestamp", System.currentTimeMillis());

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

            try (FileWriter writer = new FileWriter(mCacheFile)) {
                mGson.toJson(root, writer);
                Log.d(TAG, "✓ EPG cache saved to " + mCacheFile.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.e(TAG, "Error saving EPG cache: " + e.getMessage(), e);
        }
    }

    public Map<String, List<EpgManager.Program>> loadPrograms() {
        if (!mCacheFile.exists()) {
            Log.d(TAG, "EPG cache file does not exist");
            return null;
        }

        try {
            JsonObject root = mGson.fromJson(new FileReader(mCacheFile), JsonObject.class);
            if (root == null) {
                Log.d(TAG, "Failed to parse EPG cache");
                return null;
            }

            int version = root.getAsJsonPrimitive("version").getAsInt();
            if (version != CACHE_VERSION) {
                Log.d(TAG, "EPG cache version mismatch, ignoring");
                return null;
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
        }
    }

    public void clearCache() {
        if (mCacheFile.exists()) {
            if (mCacheFile.delete()) {
                Log.d(TAG, "✓ EPG cache cleared");
            } else {
                Log.w(TAG, "Failed to delete EPG cache file");
            }
        }
    }

    public long getCacheTimestamp() {
        if (!mCacheFile.exists()) {
            return 0;
        }
        try {
            JsonObject root = mGson.fromJson(new FileReader(mCacheFile), JsonObject.class);
            if (root != null && root.has("timestamp")) {
                return root.getAsJsonPrimitive("timestamp").getAsLong();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading cache timestamp: " + e.getMessage());
        }
        return 0;
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
        if (!mCacheFile.exists()) {
            Log.d(TAG, "EPG cache file does not exist");
            return false;
        }

        try {
            JsonObject root = mGson.fromJson(new FileReader(mCacheFile), JsonObject.class);
            if (root == null || !root.has("timestamp")) {
                return false;
            }

            long cacheTimestamp = root.getAsJsonPrimitive("timestamp").getAsLong();
            long currentDate = getCurrentDateTimestamp();
            long cacheDate = getDateFromTimestamp(cacheTimestamp);

            boolean isSameDay = cacheDate == currentDate;
            Log.d(TAG, "Cache date check - Same day: " + isSameDay);

            return isSameDay;
        } catch (Exception e) {
            Log.e(TAG, "Error checking cache date: " + e.getMessage());
            return false;
        }
    }

    private long getCurrentDateTimestamp() {
        return getDateFromTimestamp(System.currentTimeMillis());
    }

    private long getDateFromTimestamp(long timestamp) {
        return (timestamp / (24 * 3600 * 1000)) * (24 * 3600 * 1000);
    }

    public boolean cacheFileExists() {
        return mCacheFile.exists();
    }
}
