package com.cabletv.player.config;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.cabletv.player.model.Channel;
import com.cabletv.player.model.ChannelGroup;
import com.cabletv.player.util.M3uParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChannelRepository {
    private static final String TAG = "ChannelRepository";
    private static final String CACHE_DIR = "playlist_cache";
    private static final String CACHE_FILE = "playlist.m3u";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_PLAYLIST_BYTES = 8 * 1024 * 1024;

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<OnChannelsChangedListener> mListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean mLoading = new AtomicBoolean(false);
    private final File mCacheFile;

    private volatile List<ChannelGroup> mChannels = Collections.emptyList();
    private volatile List<Channel> mFlatChannels = Collections.emptyList();
    private volatile OnLoadFailedListener mLoadFailedListener;

    public interface OnChannelsChangedListener {
        void onChannelsChanged(List<ChannelGroup> channels);
    }

    /**
     * Reports that the configured playlist could not be loaded, so the UI can say so
     * instead of showing an empty channel list with no explanation.
     */
    public interface OnLoadFailedListener {
        /**
         * @param reason           short human readable cause (HTTP status, IO message, ...)
         * @param servedFromCache  true when the last saved playlist was shown instead
         */
        void onLoadFailed(String reason, boolean servedFromCache);
    }

    public ChannelRepository(Context context) {
        mContext = context.getApplicationContext();
        File cacheDir = new File(mContext.getCacheDir(), CACHE_DIR);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            Log.w(TAG, "Cannot create playlist cache dir: " + cacheDir);
        }
        mCacheFile = new File(cacheDir, CACHE_FILE);
    }

    public void addListener(OnChannelsChangedListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(OnChannelsChangedListener listener) {
        mListeners.remove(listener);
    }

    public void setOnLoadFailedListener(OnLoadFailedListener listener) {
        mLoadFailedListener = listener;
    }

    /** Drops every callback so a destroyed Activity is not kept alive by a running load. */
    public void clearListeners() {
        mListeners.clear();
        mLoadFailedListener = null;
    }

    public void reload() {
        if (!mLoading.compareAndSet(false, true)) {
            Log.d(TAG, "reload() ignored: a load is already in progress");
            return;
        }
        new Thread(() -> {
            try {
                loadBlocking();
            } catch (Throwable t) {
                Log.e(TAG, "Unexpected error loading channels", t);
            } finally {
                mLoading.set(false);
            }
        }, "channel-reload").start();
    }

    private void loadBlocking() {
        String m3uUrl = AppConfig.getM3uUrl();
        String m3uFilePath = AppConfig.getM3uFilePath();

        String content = null;
        String failure = null;
        boolean fromNetwork = false;

        if (!TextUtils.isEmpty(m3uFilePath) && new File(m3uFilePath).exists()) {
            content = readFileQuietly(new File(m3uFilePath));
            if (content == null) {
                failure = "Cannot read " + m3uFilePath;
            }
        } else if (!TextUtils.isEmpty(m3uUrl)) {
            try {
                content = loadFromUrl(m3uUrl);
                fromNetwork = true;
            } catch (Exception e) {
                Log.e(TAG, "Error loading URL: " + m3uUrl, e);
                failure = describe(e);
            }
        } else {
            // MainActivity already tells the user how to configure a source.
            Log.w(TAG, "No M3U source configured");
            return;
        }

        List<ChannelGroup> parsed = parseQuietly(content);
        if (parsed != null) {
            if (fromNetwork) {
                saveToCache(content);
            }
            publish(parsed);
            return;
        }
        if (failure == null) {
            failure = "Playlist is empty or could not be parsed";
        }

        // Network or parsing failed: fall back to the last playlist that did work.
        List<ChannelGroup> cached = parseQuietly(readFileQuietly(mCacheFile));
        if (cached != null) {
            Log.w(TAG, "Serving cached playlist after failure: " + failure);
            publish(cached);
            notifyLoadFailed(failure, true);
        } else {
            Log.e(TAG, "Playlist load failed and no cached copy is available: " + failure);
            notifyLoadFailed(failure, false);
        }
    }

    /** @return parsed groups, or null when the content is missing, empty or channel-less. */
    private List<ChannelGroup> parseQuietly(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        try {
            List<ChannelGroup> groups = M3uParser.parse(content);
            if (groups == null || groups.isEmpty()) {
                return null;
            }
            for (ChannelGroup group : groups) {
                if (group.channels != null && !group.channels.isEmpty()) {
                    return groups;
                }
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing playlist", e);
            return null;
        }
    }

    private void publish(List<ChannelGroup> groups) {
        List<Channel> flat = new ArrayList<>();
        for (ChannelGroup group : groups) {
            if (group.channels != null) {
                flat.addAll(group.channels);
            }
        }
        mChannels = Collections.unmodifiableList(groups);
        mFlatChannels = Collections.unmodifiableList(flat);
        Log.i(TAG, "Channels loaded: " + groups.size() + " groups, " + flat.size() + " channels");
        notifyListeners();
    }

    private String loadFromUrl(String urlString) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            return decode(readAll(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private String readFileQuietly(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            InputStream in = new FileInputStream(file);
            try {
                return decode(readAll(in));
            } finally {
                closeQuietly(in);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading playlist file: " + file, e);
            return null;
        }
    }

    private byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_PLAYLIST_BYTES) {
                    throw new IOException("Playlist exceeds " + MAX_PLAYLIST_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    /** M3U playlists are UTF-8 in practice; decode explicitly and drop a leading BOM. */
    private String decode(byte[] bytes) throws IOException {
        String text = new String(bytes, "UTF-8");
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private void saveToCache(String content) {
        File tmp = new File(mCacheFile.getParentFile(), CACHE_FILE + ".tmp");
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                out.write(content.getBytes("UTF-8"));
                out.flush();
            } finally {
                closeQuietly(out);
            }
            if (mCacheFile.exists() && !mCacheFile.delete()) {
                Log.w(TAG, "Cannot replace cached playlist");
            }
            if (!tmp.renameTo(mCacheFile)) {
                Log.w(TAG, "Cannot move cached playlist into place");
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } else {
                Log.d(TAG, "Playlist cached to " + mCacheFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error caching playlist", e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    /** Listeners touch views, so they always run on the main thread. */
    private void notifyListeners() {
        final List<ChannelGroup> snapshot = mChannels;
        mMainHandler.post(() -> {
            for (OnChannelsChangedListener listener : mListeners) {
                listener.onChannelsChanged(snapshot);
            }
        });
    }

    private void notifyLoadFailed(final String reason, final boolean servedFromCache) {
        mMainHandler.post(() -> {
            OnLoadFailedListener listener = mLoadFailedListener;
            if (listener != null) {
                listener.onLoadFailed(reason, servedFromCache);
            }
        });
    }

    public List<ChannelGroup> getChannels() {
        return mChannels;
    }

    public List<Channel> getAllChannels() {
        return mFlatChannels;
    }

    public Channel getChannel(int index) {
        List<Channel> all = mFlatChannels;
        if (index >= 0 && index < all.size()) {
            return all.get(index);
        }
        return null;
    }

    public int getChannelCount() {
        return mFlatChannels.size();
    }

    public int getCurrentChannelIndex(String currentUrl) {
        if (TextUtils.isEmpty(currentUrl)) {
            return 0;
        }
        List<Channel> all = mFlatChannels;
        for (int i = 0; i < all.size(); i++) {
            if (currentUrl.equals(all.get(i).url)) {
                return i;
            }
        }
        return 0;
    }
}
