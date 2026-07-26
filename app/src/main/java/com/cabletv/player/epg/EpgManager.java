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

    public enum EpgSourceType {
        AUTO,           // Auto-detect based on URL
        DIYP,          // 51zmt DIYP JSON API
        ZIP,           // ZIP package with XMLTV
        XMLTV          // Direct XMLTV URL
    }

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

        String epgUrl = AppConfig.getEpgUrl();
        if (epgUrl == null || epgUrl.isEmpty()) {
            return;
        }

        // Detect format based on URL
        EpgSourceType type = detectEpgSourceType(epgUrl);
        Log.d(TAG, "Detected EPG source type: " + type + " for URL: " + epgUrl);

        loadEpgByType(channel, epgUrl, type);
    }

    private EpgSourceType detectEpgSourceType(String url) {
        if (url.contains("diyp")) {
            return EpgSourceType.DIYP;
        } else if (url.contains("zip")) {
            return EpgSourceType.ZIP;
        } else if (url.contains("xml")) {
            return EpgSourceType.XMLTV;
        }
        // Default to DIYP for 51zmt API style
        return EpgSourceType.DIYP;
    }

    private void loadEpgByType(Channel channel, String epgUrl, EpgSourceType type) {
        new Thread(() -> {
            try {
                switch (type) {
                    case DIYP:
                        loadDiypEpg(channel, epgUrl);
                        break;
                    case ZIP:
                        loadZipEpg(channel, epgUrl);
                        break;
                    case XMLTV:
                        loadXmltvEpg(channel, epgUrl);
                        break;
                    default:
                        loadDiypEpg(channel, epgUrl);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading EPG for " + channel.name, e);
            }
        }).start();
    }

    private void loadDiypEpg(Channel channel, String baseUrl) {
        try {
            String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            String url = baseUrl.replace("{name}", channel.tvgId).replace("{date}", date);

            String content = fetchUrl(url);
            if (content != null && !content.isEmpty()) {
                Log.d(TAG, "DIYP EPG response length: " + content.length());
                parseJsonEpg(channel, content);
                Log.d(TAG, "DIYP EPG loaded for channel: " + channel.name);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error loading DIYP EPG for " + channel.name, e);
        }
    }

    private void loadZipEpg(Channel channel, String baseUrl) {
        try {
            Log.d(TAG, "ZIP EPG format support prepared (requires additional implementation)");
            // TODO: Implement ZIP package download and extraction
            // Format: Download zip from URL, extract XMLTV files, parse by channel
        } catch (Exception e) {
            Log.w(TAG, "Error loading ZIP EPG for " + channel.name, e);
        }
    }

    private void loadXmltvEpg(Channel channel, String xmltvUrl) {
        try {
            Log.d(TAG, "XMLTV EPG format support prepared (requires additional implementation)");
            // TODO: Implement XMLTV parsing
            // Format: Direct XML parsing with channel name matching
        } catch (Exception e) {
            Log.w(TAG, "Error loading XMLTV EPG for " + channel.name, e);
        }
    }

    private void parseJsonEpg(Channel channel, String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            Log.d(TAG, "Parsing EPG JSON for " + channel.name);

            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("epg_data") && obj.get("epg_data").isJsonArray()) {
                    JsonArray programs = obj.getAsJsonArray("epg_data");
                    parseArray(channel, programs);
                }
            } else if (element.isJsonArray()) {
                JsonArray programs = element.getAsJsonArray();
                parseArray(channel, programs);
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

                // Try different field names for program title
                if (prog.has("节目名")) {
                    title = prog.get("节目名").getAsString();
                } else if (prog.has("name")) {
                    title = prog.get("name").getAsString();
                } else if (prog.has("title")) {
                    title = prog.get("title").getAsString();
                }

                if (title != null && !title.isEmpty() && i == 0) {
                    Program program = new Program(title, 0, 0);
                    mPrograms.put(channel.tvgId, program);
                    Log.d(TAG, "EPG program stored for " + channel.name + ": " + title);
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

    public String getSupportedFormats() {
        return "DIYP (51zmt JSON API), ZIP (package with XMLTV), XMLTV (direct XML)";
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
