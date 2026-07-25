package com.cabletv.player.config;

import android.content.Context;
import android.util.Log;

import com.cabletv.player.model.Channel;
import com.cabletv.player.model.ChannelGroup;
import com.cabletv.player.util.M3uParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class ChannelRepository {
    private static final String TAG = "ChannelRepository";
    private final Context mContext;
    private List<ChannelGroup> mChannels = new ArrayList<>();
    private List<OnChannelsChangedListener> mListeners = new ArrayList<>();

    public interface OnChannelsChangedListener {
        void onChannelsChanged(List<ChannelGroup> channels);
    }

    public ChannelRepository(Context context) {
        mContext = context.getApplicationContext();
    }

    public void addListener(OnChannelsChangedListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(OnChannelsChangedListener listener) {
        mListeners.remove(listener);
    }

    public void reload() {
        new Thread(() -> {
            try {
                String m3uUrl = AppConfig.getM3uUrl();
                String m3uFilePath = AppConfig.getM3uFilePath();

                String content = null;
                if (!m3uFilePath.isEmpty() && new File(m3uFilePath).exists()) {
                    content = loadFromFile(m3uFilePath);
                } else if (!m3uUrl.isEmpty()) {
                    content = loadFromUrl(m3uUrl);
                }

                if (content != null && !content.isEmpty()) {
                    List<ChannelGroup> channels = M3uParser.parse(content);
                    mChannels = channels;
                    notifyListeners();
                    Log.i(TAG, "Channels loaded: " + channels.size() + " groups");
                } else {
                    Log.w(TAG, "No M3U content to parse");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading channels", e);
            }
        }).start();
    }

    private String loadFromFile(String filePath) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new FileReader(filePath));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error reading file: " + filePath, e);
            return null;
        }
    }

    private String loadFromUrl(String urlString) {
        StringBuilder sb = new StringBuilder();
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.connect();

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = connection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                inputStream.close();
                return sb.toString();
            } else {
                Log.e(TAG, "HTTP error: " + connection.getResponseCode());
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading URL: " + urlString, e);
            return null;
        }
    }

    private void notifyListeners() {
        for (OnChannelsChangedListener listener : mListeners) {
            listener.onChannelsChanged(new ArrayList<>(mChannels));
        }
    }

    public List<ChannelGroup> getChannels() {
        return new ArrayList<>(mChannels);
    }

    public List<Channel> getAllChannels() {
        List<Channel> allChannels = new ArrayList<>();
        for (ChannelGroup group : mChannels) {
            allChannels.addAll(group.channels);
        }
        return allChannels;
    }

    public Channel getChannel(int index) {
        List<Channel> all = getAllChannels();
        if (index >= 0 && index < all.size()) {
            return all.get(index);
        }
        return null;
    }

    public int getChannelCount() {
        return getAllChannels().size();
    }

    public int getCurrentChannelIndex(String currentUrl) {
        List<Channel> all = getAllChannels();
        for (int i = 0; i < all.size(); i++) {
            if (currentUrl != null && currentUrl.equals(all.get(i).url)) {
                return i;
            }
        }
        return 0;
    }
}
