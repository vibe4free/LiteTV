package com.cabletv.player.epg;

import android.content.Context;
import android.util.Log;

import com.cabletv.player.config.AppConfig;
import com.cabletv.player.model.Channel;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class EpgManager {
    private static final String TAG = "EpgManager";
    private final Context mContext;
    private final Map<String, Program> mPrograms = new HashMap<>();

    public static class Program {
        public String title;
        public long startTime;
        public long endTime;

        public Program(String title, long startTime, long endTime) {
            this.title = title;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    public EpgManager(Context context) {
        mContext = context.getApplicationContext();
    }

    public void loadEpg(Channel channel) {
        if (channel == null || channel.tvgId == null || channel.tvgId.isEmpty()) {
            return;
        }

        new Thread(() -> {
            try {
                String epgUrl = AppConfig.getEpgUrl();
                if (epgUrl.isEmpty()) {
                    return;
                }

                String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
                String url = epgUrl.replace("{name}", channel.tvgId).replace("{date}", date);

                String content = fetchUrl(url);
                if (content != null && !content.isEmpty()) {
                    Log.d(TAG, "EPG response length: " + content.length() + ", first 200 chars: " + content.substring(0, Math.min(200, content.length())));
                    parseEpgContent(channel, content);
                    Log.d(TAG, "EPG loaded for channel: " + channel.name);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error loading EPG for " + channel.name, e);
            }
        }).start();
    }

    private void parseEpgContent(Channel channel, String content) {
        try {
            if (content.contains("[") || content.contains("{")) {
                parseJsonEpg(channel, content);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error parsing EPG for " + channel.name, e);
        }
    }

    private void parseJsonEpg(Channel channel, String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            Log.d(TAG, "Parsing EPG JSON for " + channel.name + ", isArray=" + element.isJsonArray() + ", isObject=" + element.isJsonObject());

            if (element.isJsonArray()) {
                JsonArray programs = element.getAsJsonArray();
                Log.d(TAG, "EPG array size: " + programs.size());
                parseArray(channel, programs);
            } else if (element.isJsonObject()) {
                Log.d(TAG, "EPG is JsonObject, checking for epg_data field");
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("epg_data") && obj.get("epg_data").isJsonArray()) {
                    JsonArray programs = obj.getAsJsonArray("epg_data");
                    Log.d(TAG, "Found epg_data array with size: " + programs.size());
                    parseArray(channel, programs);
                } else if (obj.has("data") && obj.get("data").isJsonArray()) {
                    JsonArray programs = obj.getAsJsonArray("data");
                    Log.d(TAG, "Found data array with size: " + programs.size());
                    parseArray(channel, programs);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Failed to parse EPG JSON: " + e.getMessage());
        }
    }

    private void parseArray(Channel channel, JsonArray programs) {
        for (int i = 0; i < Math.min(programs.size(), 5); i++) {
            try {
                JsonObject prog = programs.get(i).getAsJsonObject();
                String title = null;
                long currentTime = System.currentTimeMillis();

                // Try different field names for program title
                if (prog.has("节目名")) {
                    title = prog.get("节目名").getAsString();
                } else if (prog.has("name")) {
                    title = prog.get("name").getAsString();
                } else if (prog.has("title")) {
                    title = prog.get("title").getAsString();
                } else if (i == 0) {
                    Log.d(TAG, "EPG object keys: " + prog.keySet());
                }

                if (title != null && !title.isEmpty()) {
                    Program program = new Program(title, 0, 0);
                    // Only store as current if it's the first item (simplification)
                    if (i == 0) {
                        mPrograms.put(channel.tvgId, program);
                        Log.d(TAG, "EPG program stored for " + channel.name + ": " + title);
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Error parsing program " + i + ": " + e.getMessage());
            }
        }
    }

    public Program getCurrentProgram(Channel channel) {
        if (channel == null) {
            return null;
        }
        return mPrograms.get(channel.tvgId);
    }

    public String getCurrentProgramInfo(Channel channel) {
        Program program = getCurrentProgram(channel);
        if (program != null) {
            return program.title;
        }
        return null;
    }

    public String getNextProgramInfo(Channel channel) {
        return null;
    }

    private String fetchUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                return sb.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching URL: " + urlString, e);
        }
        return null;
    }
}
