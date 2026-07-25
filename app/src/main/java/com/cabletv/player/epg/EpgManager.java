package com.cabletv.player.epg;

import android.content.Context;
import android.util.Log;

import com.cabletv.player.config.AppConfig;
import com.cabletv.player.model.Channel;

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
                    // Parse EPG content (simplified for now - just log success)
                    Log.d(TAG, "EPG loaded for channel: " + channel.name);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error loading EPG for " + channel.name, e);
            }
        }).start();
    }

    public Program getCurrentProgram(Channel channel) {
        if (channel == null) {
            return null;
        }
        return mPrograms.get(channel.tvgId);
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
