package com.cabletv.player.server;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.cabletv.player.config.AppConfig;
import com.cabletv.player.update.AppVersion;
import com.cabletv.player.update.UpdateError;
import com.cabletv.player.update.UpdateManager;
import com.cabletv.player.update.UpdateState;
import com.cabletv.player.update.UpdateStatus;
import com.cabletv.player.util.LocalPlaylistFolder;
import com.google.gson.JsonObject;

import xyz.doikki.videoplayer.player.VideoViewConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigWebServer {
    private static final String TAG = "ConfigWebServer";

    /** Request line cap; a legitimate config request is a few dozen bytes. */
    private static final int MAX_REQUEST_LINE_BYTES = 2048;
    /** Header count cap, guards against a client that never sends a blank line. */
    private static final int MAX_HEADER_LINES = 64;
    /** Body cap; the only bodies we accept are two short form fields. */
    private static final int MAX_BODY_BYTES = 64 * 1024;
    /** Playlist uploads are bounded by the same 8 MB ceiling ChannelRepository enforces. */
    private static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024 + 64 * 1024;
    private static final String UPLOAD_PATH = "/config/m3u-file-upload";
    /**
     * A client that connects but never sends a request head must release its worker
     * quickly, otherwise a handful of idle sockets makes the server unavailable.
     */
    private static final int HEAD_TIMEOUT_MS = 3_000;
    /** Once a valid head has arrived, allow a slower body transfer. */
    private static final int BODY_TIMEOUT_MS = 10_000;
    /** A playlist upload over Wi-Fi can be several MB, so it gets its own budget. */
    private static final int UPLOAD_TIMEOUT_MS = 60_000;
    /** Bounded worker pool, so many connections cannot exhaust threads. */
    private static final int MAX_WORKERS = 8;

    private static ServerSocket sServerSocket;
    private static Thread sServerThread;
    private static ExecutorService sWorkers;
    private static volatile boolean sRunning = false;

    public interface OnConfigChangeListener {
        void onM3uUrlChanged(String newUrl);
        void onEpgUrlChanged(String newUrl);
        void onEpgPreferenceChanged(String preference);
        /** The local playlist was cleared (newPath is empty) from the web page. */
        void onM3uFileChanged(String newPath);

        /** A display preference changed; the picture has to follow without a restart. */
        void onDisplaySettingsChanged();
    }

    /** Cleared by the Activity in onDestroy(); volatile because server threads read it. */
    private static volatile OnConfigChangeListener sConfigChangeListener;
    /** Kept only to answer /config/get (e.g. the playlist drop folder); never used to leak. */
    private static volatile Context sAppContext;

    public static synchronized void startServer(Context context, int port) {
        if (context != null) {
            sAppContext = context.getApplicationContext();
        }
        if (sRunning) {
            Log.i(TAG, "Server already running on port " + sServerSocket.getLocalPort());
            return;
        }

        // Bind on the calling thread so a port conflict surfaces here instead of
        // racing with the accept loop.
        try {
            sServerSocket = new ServerSocket(port);
        } catch (IOException e) {
            Log.e(TAG, "Cannot bind web server to port " + port, e);
            sServerSocket = null;
            return;
        }

        sRunning = true;
        // No queue: once all workers are busy, further connections are rejected and
        // closed immediately instead of waiting behind a stalled client.
        sWorkers = new ThreadPoolExecutor(1, MAX_WORKERS, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>());
        sServerThread = new Thread(ConfigWebServer::runServer, "ConfigWebServer");
        sServerThread.setDaemon(true);
        sServerThread.start();
        Log.i(TAG, "Web server started on port " + port);
    }

    public static void startServer(Context context) {
        startServer(context, AppConfig.getWebServerPort());
    }

    public static void setConfigChangeListener(OnConfigChangeListener listener) {
        sConfigChangeListener = listener;
    }

    public static synchronized void stopServer() {
        sRunning = false;
        if (sServerSocket != null) {
            try {
                sServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
            sServerSocket = null;
        }
        if (sWorkers != null) {
            sWorkers.shutdownNow();
            sWorkers = null;
        }
        Log.i(TAG, "Web server stopped");
    }

    private static void runServer() {
        try {
            while (sRunning) {
                Socket clientSocket = sServerSocket.accept();
                ExecutorService workers = sWorkers;
                if (workers == null) {
                    closeQuietly(clientSocket);
                    break;
                }
                try {
                    workers.execute(() -> handleClient(clientSocket));
                } catch (Exception e) {
                    // Pool saturated or shutting down: drop the connection rather than queue forever.
                    closeQuietly(clientSocket);
                }
            }
        } catch (IOException e) {
            if (sRunning) {
                Log.e(TAG, "Server error", e);
            }
        } finally {
            sRunning = false;
        }
    }

    private static void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(HEAD_TIMEOUT_MS);
            InputStream in = socket.getInputStream();

            String requestLine = readLine(in, MAX_REQUEST_LINE_BYTES);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendResponse(socket, "400 Bad Request", 400, "text/plain");
                return;
            }
            String method = parts[0];
            String path = parts[1];

            // Read headers
            int contentLength = 0;
            String origin = null;
            String host = null;
            String requestContentType = "";
            for (int i = 0; i < MAX_HEADER_LINES; i++) {
                String line = readLine(in, MAX_REQUEST_LINE_BYTES);
                if (line == null || line.isEmpty()) break;
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = parseContentLength(line.substring("content-length:".length()));
                    if (contentLength < 0) {
                        sendResponse(socket, "400 Bad Request", 400, "text/plain");
                        return;
                    }
                    if (contentLength > bodyLimitFor(path)) {
                        sendResponse(socket, "413 Payload Too Large", 413, "text/plain");
                        return;
                    }
                } else if (lower.startsWith("origin:")) {
                    origin = line.substring("origin:".length()).trim();
                } else if (lower.startsWith("host:")) {
                    host = line.substring("host:".length()).trim();
                } else if (lower.startsWith("content-type:")) {
                    // Keep the original case: the multipart boundary is case-sensitive even though
                    // the header name and parameters are not.
                    requestContentType = line.substring("content-type:".length()).trim();
                }
            }

            // Read body (exact byte count). Kept as bytes because a multipart upload can hold
            // content that is not valid UTF-8; the text form is decoded only when needed.
            byte[] rawBody = null;
            if (contentLength > 0) {
                socket.setSoTimeout(UPLOAD_PATH.equals(path) ? UPLOAD_TIMEOUT_MS : BODY_TIMEOUT_MS);
                rawBody = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int n = in.read(rawBody, off, contentLength - off);
                    if (n == -1) break;
                    off += n;
                }
                if (off < contentLength) {
                    sendResponse(socket, "400 Bad Request", 400, "text/plain");
                    return;
                }
            }
            String body = rawBody == null ? "" : new String(rawBody, "UTF-8");

            if ("POST".equals(method)) {
                // The server has no authentication, so it must at least refuse
                // browser-driven cross-site writes: any page the user visits could
                // otherwise silently repoint the playlist. Browsers send Origin on
                // cross-site POSTs; local tools (curl and the page we serve) do not
                // trip this check.
                if (isCrossSite(origin, host)) {
                    Log.w(TAG, "Rejected cross-site POST from origin: " + origin);
                    sendResponse(socket, "403 Forbidden", 403, "text/plain");
                    return;
                }
                String requestType = requestContentType.toLowerCase();
                if (!requestType.startsWith("application/x-www-form-urlencoded")
                        && !(UPLOAD_PATH.equals(path)
                        && requestType.startsWith("multipart/form-data"))) {
                    sendResponse(socket, "415 Unsupported Media Type", 415, "text/plain");
                    return;
                }
            }

            String response;
            String contentType = "text/html; charset=utf-8";

            if ("GET".equals(method) && "/".equals(path)) {
                response = getIndexHtml();
            } else if ("GET".equals(method) && "/config/get".equals(path)) {
                response = getConfigJson();
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/m3u-url".equals(path)) {
                response = handleM3uUrlPost(body);
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/epg-url".equals(path)) {
                response = handleEpgUrlPost(body);
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/epg-preference".equals(path)) {
                response = handleEpgPreferencePost(body);
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/m3u-file-clear".equals(path)) {
                response = handleM3uFileClear();
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/playback".equals(path)) {
                response = handlePlaybackPost(body);
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/display".equals(path)) {
                response = handleDisplayPost(body);
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/channels".equals(path)) {
                response = handleChannelsPost(body);
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/update-settings".equals(path)) {
                response = handleUpdateSettingsPost(body);
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/update-check".equals(path)) {
                response = handleUpdateCheckPost();
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/update-install".equals(path)) {
                response = handleUpdateInstallPost();
                contentType = "application/json";
            } else if ("POST".equals(method) && UPLOAD_PATH.equals(path)) {
                response = handleM3uFileUpload(rawBody, requestContentType);
                contentType = "application/json";
            } else {
                sendResponse(socket, "404 Not Found", 404, "text/plain");
                return;
            }

            sendResponse(socket, response, 200, contentType);
        } catch (java.net.SocketTimeoutException e) {
            // Idle or stalled client: expected, not worth a stack trace.
            Log.d(TAG, "Client timed out: " + socket.getInetAddress());
        } catch (Exception e) {
            Log.e(TAG, "Error handling client", e);
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * Reads one CRLF-terminated line, capped at maxBytes. Returns null at end of stream.
     */
    private static String readLine(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return new String(buffer.toByteArray(), "UTF-8");
            }
            if (b == '\r') {
                continue;
            }
            if (buffer.size() >= maxBytes) {
                throw new IOException("Header line exceeds " + maxBytes + " bytes");
            }
            buffer.write(b);
        }
        return buffer.size() == 0 ? null : new String(buffer.toByteArray(), "UTF-8");
    }

    private static int parseContentLength(String value) {
        try {
            long length = Long.parseLong(value.trim());
            if (length < 0) return -1;
            // Clamp instead of overflowing; caller rejects anything over the cap.
            return length > MAX_UPLOAD_BYTES ? MAX_UPLOAD_BYTES + 1 : (int) length;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Playlist uploads may be several MB; every other request stays tiny. */
    private static int bodyLimitFor(String path) {
        return UPLOAD_PATH.equals(path) ? MAX_UPLOAD_BYTES : MAX_BODY_BYTES;
    }

    /**
     * True when the request carries an Origin that is not this server itself.
     */
    private static boolean isCrossSite(String origin, String host) {
        if (origin == null || origin.isEmpty() || "null".equals(origin)) {
            return false; // Not a browser cross-site request.
        }
        if (host == null || host.isEmpty()) {
            return true; // Origin present but no Host to compare against: refuse.
        }
        String originAuthority = origin.replaceFirst("(?i)^https?://", "");
        return !originAuthority.equalsIgnoreCase(host);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    private static void sendResponse(Socket socket, String body, int statusCode, String contentType) throws IOException {
        byte[] payload = body.getBytes("UTF-8");
        String response = "HTTP/1.1 " + statusCode + " " + statusText(statusCode) + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + payload.length + "\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        OutputStream os = socket.getOutputStream();
        os.write(response.getBytes("UTF-8"));
        os.write(payload);
        os.flush();
    }

    private static String statusText(int statusCode) {
        switch (statusCode) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            default: return "Error";
        }
    }

    private static String handleM3uUrlPost(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String m3uUrl = params.get("m3u_url");
            if (m3uUrl != null && !m3uUrl.isEmpty()) {
                AppConfig.setM3uUrl(m3uUrl);
                OnConfigChangeListener listener = sConfigChangeListener;
                if (listener != null) {
                    listener.onM3uUrlChanged(m3uUrl);
                }
                JsonObject json = new JsonObject();
                json.addProperty("success", true);
                json.addProperty("message", "M3U URL saved");
                return json.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving M3U URL", e);
        }
        JsonObject json = new JsonObject();
        json.addProperty("success", false);
        json.addProperty("message", "Failed to save M3U URL");
        return json.toString();
    }

    private static String handleEpgUrlPost(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String epgUrl = params.get("epg_url");
            if (epgUrl != null && !epgUrl.isEmpty()) {
                AppConfig.setEpgUrl(epgUrl);
                OnConfigChangeListener listener = sConfigChangeListener;
                if (listener != null) {
                    listener.onEpgUrlChanged(epgUrl);
                }
                JsonObject json = new JsonObject();
                json.addProperty("success", true);
                json.addProperty("message", "EPG URL saved");
                return json.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving EPG URL", e);
        }
        JsonObject json = new JsonObject();
        json.addProperty("success", false);
        json.addProperty("message", "Failed to save EPG URL");
        return json.toString();
    }

    private static String handleEpgPreferencePost(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String preference = params.get("epg_preference");
            if (preference != null && (AppConfig.EPG_PREFERENCE_AUTO.equals(preference)
                    || AppConfig.EPG_PREFERENCE_M3U.equals(preference)
                    || AppConfig.EPG_PREFERENCE_USER.equals(preference))) {
                AppConfig.setEpgPreference(preference);
                OnConfigChangeListener listener = sConfigChangeListener;
                if (listener != null) {
                    listener.onEpgPreferenceChanged(preference);
                }
                JsonObject json = new JsonObject();
                json.addProperty("success", true);
                json.addProperty("message", "EPG priority saved");
                return json.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving EPG preference", e);
        }
        JsonObject json = new JsonObject();
        json.addProperty("success", false);
        json.addProperty("message", "Failed to save EPG priority");
        return json.toString();
    }

    /** Removes the local playlist so the configured URL becomes the source again. */
    private static String handleM3uFileClear() {
        try {
            String path = AppConfig.getM3uFilePath();
            if (!TextUtils.isEmpty(path)) {
                //noinspection ResultOfMethodCallIgnored
                new File(path).delete();
            }
            AppConfig.setM3uFilePath("");
            OnConfigChangeListener listener = sConfigChangeListener;
            if (listener != null) {
                listener.onM3uFileChanged("");
            }
            JsonObject json = new JsonObject();
            json.addProperty("success", true);
            json.addProperty("message", "Local M3U file removed");
            return json.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error clearing local M3U file", e);
        }
        JsonObject json = new JsonObject();
        json.addProperty("success", false);
        json.addProperty("message", "Failed to clear local M3U file");
        return json.toString();
    }

    private static String getConfigJson() {
        JsonObject config = new JsonObject();
        config.addProperty("m3u_url", AppConfig.getM3uUrl());
        config.addProperty("m3u_file_path", AppConfig.getM3uFilePath());
        // Whether repeated channel names in one group are merged into one channel (subscription
        // links list a channel once per source); see M3uParser.
        config.addProperty("merge_same_name_channels", AppConfig.isMergeSameNameChannelsEnabled());
        // Where to drop a playlist when the TV has no document picker (issue #7).
        Context appContext = sAppContext;
        config.addProperty("m3u_drop_dir", appContext == null ? ""
                : com.cabletv.player.util.LocalPlaylistFolder.dropDir(appContext).getAbsolutePath());
        config.addProperty("epg_url", AppConfig.getEpgUrl());
        config.addProperty("epg_preference", AppConfig.getEpgPreference());
        config.add("m3u_epg_urls", new com.google.gson.Gson().toJsonTree(AppConfig.getM3uEpgUrls()));
        List<String> active = AppConfig.resolveEpgUrls();
        config.addProperty("active_epg_url", active.isEmpty() ? "" : active.get(0));
        config.addProperty("web_server_enabled", AppConfig.isWebServerEnabled());
        // Display and playback preferences, so the web page and the TV settings screen expose the
        // same configuration instead of each covering half of it.
        config.addProperty("epg_display_enabled", AppConfig.isEpgDisplayEnabled());
        config.addProperty("sidebar_opacity", AppConfig.getSidebarOpacity());
        config.addProperty("catchup_badge", AppConfig.isCatchupBadgeEnabled());
        config.addProperty("video_render_mode",
                AppConfig.getVideoRenderMode() == AppConfig.RENDER_TEXTURE ? "texture" : "surface");
        config.addProperty("video_decode_mode", decodeModeName(AppConfig.getVideoDecodeMode()));
        config.addProperty("video_tunneling", AppConfig.isTunnelingEnabled());
        // Update state, so the page can show what the television knows without polling it.
        config.addProperty("app_version_name", appContext == null ? "" : AppVersion.name(appContext));
        config.addProperty("app_version_code", appContext == null ? 0 : AppVersion.code(appContext));
        config.addProperty("update_auto_check", AppConfig.isUpdateAutoCheckEnabled());
        config.addProperty("update_last_check_ms", AppConfig.getUpdateLastCheckMs());
        config.addProperty("update_last_error", AppConfig.getUpdateLastError());
        addUpdateStateJson(config, UpdateManager.get().getStatus());
        return config.toString();
    }

    /**
     * Adds the parts of the update status both {@code /config/get} and the two update endpoints
     * return. One helper so the page never has to reconcile two shapes.
     */
    static void addUpdateStateJson(JsonObject target, UpdateStatus status) {
        target.addProperty("update_state", status.state.name().toLowerCase(java.util.Locale.US));
        target.addProperty("update_progress", Math.round(status.progressFraction() * 100f));
        target.addProperty("update_error", status.error == UpdateError.NONE ? "" : status.error.name());
        if (status.latestVersionName != null) {
            target.addProperty("update_latest_version", status.latestVersionName);
        }
        if (!status.changelog.isEmpty()) {
            target.add("update_changelog", new com.google.gson.Gson().toJsonTree(status.changelog));
        }
    }

    /**
     * Saves the channel-list preferences. Takes effect when the playlist is next loaded, which the
     * television does when it notices the change (or on the next start).
     */
    private static String handleChannelsPost(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String merge = params.get("merge_same_name_channels");
            if (!isValidFlag(merge)) {
                return jsonResult(false, "Invalid channel setting");
            }
            if (merge != null) {
                AppConfig.setMergeSameNameChannelsEnabled("1".equals(merge));
            }
            return jsonResult(true, "Channel settings saved (the playlist reloads now)");
        } catch (Exception e) {
            Log.e(TAG, "Error saving channel settings", e);
            return jsonResult(false, "Failed to save channel settings");
        }
    }

    /**
     * Saves the update preferences. Only the automatic-check switch is a setting; checking and
     * installing are actions with their own endpoints, because they do something.
     */
    private static String handleUpdateSettingsPost(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String autoCheck = params.get("update_auto_check");
            if (!isValidFlag(autoCheck)) {
                return jsonResult(false, "Invalid update setting");
            }
            if (autoCheck != null) {
                AppConfig.setUpdateAutoCheckEnabled("1".equals(autoCheck));
            }
            return jsonResult(true, "Update settings saved");
        } catch (Exception e) {
            Log.e(TAG, "Error saving update settings", e);
            return jsonResult(false, "Failed to save update settings");
        }
    }

    /**
     * Checks for a newer release and answers with the result. This is the one endpoint that blocks
     * on the network; the fetcher's timeouts bound it.
     */
    private static String handleUpdateCheckPost() {
        Context appContext = sAppContext;
        if (appContext == null) {
            return jsonResult(false, "Not ready");
        }
        try {
            UpdateStatus status = UpdateManager.get().checkNowAndWait(appContext);
            JsonObject json = new JsonObject();
            json.addProperty("success", true);
            json.addProperty("message", "Checked");
            addUpdateStateJson(json, status);
            return json.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error checking for updates", e);
            return jsonResult(false, "Failed to check for updates");
        }
    }

    /**
     * Starts the whole flow on the television: download, verify, then the system installer prompt.
     * Answers immediately; the page follows the state through {@code /config/get}.
     */
    private static String handleUpdateInstallPost() {
        Context appContext = sAppContext;
        if (appContext == null) {
            return jsonResult(false, "Not ready");
        }
        UpdateStatus status = UpdateManager.get().getStatus();
        if (!status.isUpdateAvailable() && !status.isReadyToInstall()) {
            return jsonResult(false, "No update to install yet");
        }
        try {
            UpdateManager.get().downloadAndInstall(appContext);
            return jsonResult(true, "Updating on the TV; confirm the install there");
        } catch (Exception e) {
            Log.e(TAG, "Error starting the update", e);
            return jsonResult(false, "Failed to start the update");
        }
    }

    private static String decodeModeName(int mode) {
        switch (mode) {
            case VideoViewConfig.DECODE_HARDWARE:
                return "hardware";
            case VideoViewConfig.DECODE_SOFTWARE:
                return "software";
            default:
                return "auto";
        }
    }

    /**
     * Saves the playback preferences. Every provided field must be valid, otherwise nothing is
     * written — a half-applied change would be harder to notice than a rejection. These take
     * effect on the next launch (the player reads its config once per process), which the page
     * tells the user.
     */
    private static String handlePlaybackPost(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String render = params.get("video_render_mode");
            String decode = params.get("video_decode_mode");
            String tunneling = params.get("video_tunneling");
            if (!isValidRenderMode(render) || !isValidDecodeMode(decode) || !isValidFlag(tunneling)) {
                return jsonResult(false, "Invalid playback setting");
            }
            if (render != null) {
                AppConfig.setVideoRenderMode("texture".equals(render)
                        ? AppConfig.RENDER_TEXTURE : AppConfig.RENDER_SURFACE);
            }
            if (decode != null) {
                AppConfig.setVideoDecodeMode(decodeModeOf(decode));
            }
            if (tunneling != null) {
                AppConfig.setTunnelingEnabled("1".equals(tunneling));
            }
            return jsonResult(true, "Playback settings saved (restart the app to apply)");
        } catch (Exception e) {
            Log.e(TAG, "Error saving playback settings", e);
            return jsonResult(false, "Failed to save playback settings");
        }
    }

    /** Saves the display preferences; both are applied the next time the channel list opens. */
    private static String handleDisplayPost(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String epgDisplay = params.get("epg_display_enabled");
            String opacity = params.get("sidebar_opacity");
            String catchupBadge = params.get("catchup_badge");
            if (!isValidFlag(epgDisplay) || !isValidOpacity(opacity) || !isValidFlag(catchupBadge)) {
                return jsonResult(false, "Invalid display setting");
            }
            if (epgDisplay != null) {
                AppConfig.setEpgDisplayEnabled("1".equals(epgDisplay));
            }
            if (opacity != null) {
                AppConfig.setSidebarOpacity(Integer.parseInt(opacity));
            }
            if (catchupBadge != null) {
                AppConfig.setCatchupBadgeEnabled("1".equals(catchupBadge));
            }
            OnConfigChangeListener listener = sConfigChangeListener;
            if (listener != null) {
                try {
                    listener.onDisplaySettingsChanged();
                } catch (RuntimeException e) {
                    // The page's answer must not depend on the player being alive.
                    Log.w(TAG, "Display change listener threw", e);
                }
            }
            return jsonResult(true, "Display settings saved");
        } catch (Exception e) {
            Log.e(TAG, "Error saving display settings", e);
            return jsonResult(false, "Failed to save display settings");
        }
    }

    private static boolean isValidRenderMode(String value) {
        return value == null || "surface".equals(value) || "texture".equals(value);
    }

    private static boolean isValidDecodeMode(String value) {
        return value == null || "auto".equals(value) || "hardware".equals(value)
                || "software".equals(value);
    }

    private static int decodeModeOf(String value) {
        if ("hardware".equals(value)) {
            return VideoViewConfig.DECODE_HARDWARE;
        }
        if ("software".equals(value)) {
            return VideoViewConfig.DECODE_SOFTWARE;
        }
        return VideoViewConfig.DECODE_AUTO;
    }

    private static boolean isValidFlag(String value) {
        return value == null || "0".equals(value) || "1".equals(value);
    }

    private static boolean isValidOpacity(String value) {
        if (value == null) {
            return true;
        }
        return "0".equals(value) || "1".equals(value) || "2".equals(value);
    }

    private static String jsonResult(boolean success, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("success", success);
        json.addProperty("message", message);
        return json.toString();
    }

    /**
     * Accepts a playlist uploaded from the browser and makes it the active local file. This is the
     * import path that works on TVs with no document picker and no reachable drop folder: the user
     * picks the file on their phone or PC and the TV fetches it over the LAN.
     */
    private static String handleM3uFileUpload(byte[] rawBody, String contentType) {
        Context appContext = sAppContext;
        if (appContext == null) {
            return jsonResult(false, "Server not ready");
        }
        MultipartFile file = parseMultipartFile(rawBody, contentType);
        if (file == null || file.content.length == 0) {
            return jsonResult(false, "No file in the upload");
        }
        if (file.content.length > LocalPlaylistFolder.MAX_FILE_BYTES) {
            return jsonResult(false, "File is larger than 8 MB");
        }
        if (!LocalPlaylistFolder.looksLikePlaylist(file.fileName, file.content)) {
            return jsonResult(false, "That does not look like an M3U/TXT playlist");
        }
        String path = LocalPlaylistFolder.writePlaylist(appContext, file.content);
        if (path == null) {
            return jsonResult(false, "Could not store the playlist");
        }
        AppConfig.setM3uFilePath(path);
        Log.i(TAG, "Playlist uploaded from the web page: " + file.fileName
                + " (" + file.content.length + " bytes)");
        OnConfigChangeListener listener = sConfigChangeListener;
        if (listener != null) {
            listener.onM3uFileChanged(path);
        }
        return jsonResult(true, "Playlist uploaded and activated");
    }

    /** A file part of a multipart/form-data body. */
    static final class MultipartFile {
        final String fileName;
        final byte[] content;

        MultipartFile(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content;
        }
    }

    /**
     * Extracts the first file part from a multipart/form-data body.
     *
     * <p>Parsed over an ISO-8859-1 view of the bytes so string indices map 1:1 onto byte offsets;
     * that keeps the payload byte-exact (UTF-8 playlists are not ASCII) without hand-writing a
     * byte search.
     *
     * @return the part, or null when the body is malformed or carries no file.
     */
    static MultipartFile parseMultipartFile(byte[] body, String contentType) {
        if (body == null || body.length == 0 || contentType == null) {
            return null;
        }
        String boundary = boundaryOf(contentType);
        if (boundary == null || boundary.isEmpty()) {
            return null;
        }
        String raw = new String(body, java.nio.charset.StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        int firstPart = raw.indexOf(delimiter);
        if (firstPart < 0) {
            return null;
        }
        int cursor = raw.indexOf("\r\n\r\n", firstPart);
        if (cursor < 0) {
            return null;
        }
        String headers = raw.substring(firstPart, cursor);
        String fileName = filenameOf(headers);
        int contentStart = cursor + 4;
        int contentEnd = raw.indexOf("\r\n" + delimiter, contentStart);
        if (contentEnd < 0) {
            contentEnd = raw.length();
        }
        byte[] content = raw.substring(contentStart, contentEnd)
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return new MultipartFile(fileName, content);
    }

    /** @return the boundary parameter of a multipart content type, or null. */
    private static String boundaryOf(String contentType) {
        String lower = contentType.toLowerCase();
        int index = lower.indexOf("boundary=");
        if (index < 0) {
            return null;
        }
        String value = contentType.substring(index + "boundary=".length()).trim();
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** @return the filename of a part, or an empty string when it has none. */
    private static String filenameOf(String partHeaders) {
        Matcher matcher = Pattern.compile("filename=\"([^\"]*)\"").matcher(partHeaders);
        String name;
        if (matcher.find()) {
            name = matcher.group(1);
        } else {
            matcher = Pattern.compile("filename=([^;\r\n]*)").matcher(partHeaders);
            name = matcher.find() ? matcher.group(1).trim() : "";
        }
        return fixMojibake(name);
    }

    /**
     * The part headers were read as ISO-8859-1 so byte offsets stay exact, which turns a UTF-8
     * filename (browsers send Chinese names that way) into mojibake. Round-tripping the same bytes
     * back through UTF-8 restores it; a pure ASCII name is unaffected.
     */
    private static String fixMojibake(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean ascii = true;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return value;
        }
        return new String(value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseFormData(String body) {
        Map<String, String> params = new HashMap<>();
        try {
            for (String pair : body.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    params.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing form data", e);
        }
        return params;
    }

    private static String getIndexHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>LiteTV 配置</title>\n" +
                "    <style>\n" +
                "        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #1e1e1e; color: #fff; margin: 0; padding: 20px; }\n" +
                "        .container { max-width: 700px; margin: 0 auto; }\n" +
                "        h1 { color: #FF6B35; margin-bottom: 30px; text-align: center; }\n" +
                "        .section { background-color: #2a2a2a; border-left: 4px solid #FF6B35; border-radius: 4px; padding: 20px; margin-bottom: 20px; }\n" +
                "        .section h2 { color: #FF6B35; margin-top: 0; font-size: 18px; }\n" +
                "        label { display: block; margin-bottom: 8px; font-weight: 500; font-size: 14px; }\n" +
                "        input[type=\"text\"], input[type=\"url\"], textarea { width: 100%; padding: 10px; margin-bottom: 12px; background-color: #1a1a1a; color: #fff; border: 1px solid #444; border-radius: 4px; box-sizing: border-box; font-size: 14px; }\n" +
                "        input[type=\"text\"]:focus, input[type=\"url\"]:focus, textarea:focus { outline: none; border-color: #FF6B35; }\n" +
                "        button { background-color: #FF6B35; color: #fff; padding: 10px 20px; border: none; border-radius: 4px; cursor: pointer; font-size: 14px; font-weight: 500; transition: background-color 0.3s; }\n" +
                "        button:hover { background-color: #ff7d52; }\n" +
                "        .info { font-size: 12px; color: #aaa; margin-top: 8px; line-height: 1.5; }\n" +
                "        .status { padding: 10px; border-radius: 4px; margin-top: 8px; font-size: 12px; display: none; }\n" +
                "        .status.success { background-color: #2d5a2d; color: #90EE90; display: block; }\n" +
                "        .status.error { background-color: #5a2d2d; color: #FF6B6B; display: block; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <h1>📺 LiteTV 配置</h1>\n" +
                "        \n" +
                "        <div class=\"section\">\n" +
                "            <h2>M3U 播放列表</h2>\n" +
                "            <label for=\"m3uUrl\">播放列表 URL:</label>\n" +
                "            <input type=\"url\" id=\"m3uUrl\" placeholder=\"http://example.com/playlist.m3u\">\n" +
                "            <div class=\"info\">输入 M3U 播放列表的完整 URL，例如: http://example.com/playlist.m3u</div>\n" +
                "            <button onclick=\"saveM3uUrl()\">保存 M3U URL</button>\n" +
                "            <div id=\"m3uStatus\" class=\"status\"></div>\n" +
                "            <div id=\"m3uFileInfo\" class=\"info\"></div>\n" +
                "            <div id=\"m3uDropDirInfo\" class=\"info\"></div>\n" +
                "            <label for=\"m3uUploadInput\" style=\"margin-top:12px;\">从本机上传 M3U 文件（手机 / 电脑选择）:</label>\n" +
                "            <input type=\"file\" id=\"m3uUploadInput\" accept=\".m3u,.m3u8,.txt,text/plain\" style=\"margin-bottom:12px;\">\n" +
                "            <button onclick=\"uploadM3uFile()\">上传并启用</button>\n" +
                "            <div id=\"uploadStatus\" class=\"status\"></div>\n" +
                "            <button id=\"clearM3uFileBtn\" onclick=\"clearM3uFile()\" style=\"display:none;\">清除本地文件</button>\n" +
                "            <div id=\"m3uFileStatus\" class=\"status\"></div>\n" +
                "            <label style=\"display:flex;align-items:center;gap:8px;margin:12px 0;\">\n" +
                "                <input type=\"checkbox\" id=\"mergeSameName\"> 合并同名频道（同一频道多个源合成一个）\n" +
                "            </label>\n" +
                "            <button onclick=\"saveChannels()\">保存频道设置</button>\n" +
                "            <div id=\"channelsStatus\" class=\"status\"></div>\n" +
                "            <div class=\"info\">订阅链接常把同一频道按不同源重复列出；开启后合并为一个频道并依次尝试各个源（播放界面按 R 或双击可手动切换源）。列表本身没有重复时无任何影响。</div>\n" +
                "        </div>\n" +
                "        \n" +
                "        <div class=\"section\">\n" +
                "            <h2>EPG 电子节目单</h2>\n" +
                "            <label for=\"epgUrl\">EPG 地址模板:</label>\n" +
                "            <input type=\"url\" id=\"epgUrl\" placeholder=\"http://epg.example.com/api/diyp?ch={name}&date={date}\">\n" +
                "            <div class=\"info\">支持 {name} 和 {date} 占位符，例如: http://epg.example.com/api/diyp?ch={name}&date={date}</div>\n" +
                "            <button onclick=\"saveEpgUrl()\">保存 EPG 地址</button>\n" +
                "            <div id=\"epgStatus\" class=\"status\"></div>\n" +
                "            <label for=\"epgPreference\" style=\"margin-top:16px;\">EPG 来源优先级:</label>\n" +
                "            <select id=\"epgPreference\" style=\"width:100%;padding:10px;margin-bottom:12px;background-color:#1a1a1a;color:#fff;border:1px solid #444;border-radius:4px;font-size:14px;\">\n" +
                "                <option value=\"auto\">自动：优先使用我设置的 EPG，未设置时用 M3U 内置</option>\n" +
                "                <option value=\"m3u\">优先使用 M3U 内置 EPG</option>\n" +
                "                <option value=\"user\">仅使用我设置的 EPG</option>\n" +
                "            </select>\n" +
                "            <button onclick=\"saveEpgPreference()\">保存优先级</button>\n" +
                "            <div id=\"epgPrefStatus\" class=\"status\"></div>\n" +
                "            <div id=\"epgM3uInfo\" class=\"info\"></div>\n" +
                "            <div id=\"epgActiveInfo\" class=\"info\"></div>\n" +
                "        </div>\n" +
                "        \n" +
                "        <div class=\"section\">\n" +
                "            <h2>播放与显示</h2>\n" +
                "            <label for=\"videoRenderMode\">画面输出方式:</label>\n" +
                "            <select id=\"videoRenderMode\" style=\"width:100%;padding:10px;margin-bottom:8px;background-color:#1a1a1a;color:#fff;border:1px solid #444;border-radius:4px;font-size:14px;\">\n" +
                "                <option value=\"surface\">SurfaceView（推荐，画面更清晰）</option>\n" +
                "                <option value=\"texture\">TextureView（兼容备用）</option>\n" +
                "            </select>\n" +
                "            <label for=\"videoDecodeMode\">解码方式:</label>\n" +
                "            <select id=\"videoDecodeMode\" style=\"width:100%;padding:10px;margin-bottom:8px;background-color:#1a1a1a;color:#fff;border:1px solid #444;border-radius:4px;font-size:14px;\">\n" +
                "                <option value=\"auto\">自动（优先硬件，失败回退软件）</option>\n" +
                "                <option value=\"hardware\">仅硬件解码</option>\n" +
                "                <option value=\"software\">优先软件解码（可解决个别频道打不开）</option>\n" +
                "            </select>\n" +
                "            <label style=\"display:flex;align-items:center;gap:8px;margin-bottom:12px;\">\n" +
                "                <input type=\"checkbox\" id=\"videoTunneling\"> 视频直通显示（实验，部分电视画面更流畅）\n" +
                "            </label>\n" +
                "            <label for=\"epgDisplay\">频道列表显示节目单:</label>\n" +
                "            <select id=\"epgDisplay\" style=\"width:100%;padding:10px;margin-bottom:8px;background-color:#1a1a1a;color:#fff;border:1px solid #444;border-radius:4px;font-size:14px;\">\n" +
                "                <option value=\"1\">显示</option>\n" +
                "                <option value=\"0\">不显示</option>\n" +
                "            </select>\n" +
                "            <label for=\"catchupBadge\">回看时显示回放标记:</label>\n" +
                "            <select id=\"catchupBadge\" style=\"width:100%;padding:10px;margin-bottom:8px;background-color:#1a1a1a;color:#fff;border:1px solid #444;border-radius:4px;font-size:14px;\">\n" +
                "                <option value=\"1\">显示（默认）</option>\n" +
                "                <option value=\"0\">不显示</option>\n" +
                "            </select>\n" +
                "            <label for=\"sidebarOpacity\">频道列表背景:</label>\n" +
                "            <select id=\"sidebarOpacity\" style=\"width:100%;padding:10px;margin-bottom:12px;background-color:#1a1a1a;color:#fff;border:1px solid #444;border-radius:4px;font-size:14px;\">\n" +
                "                <option value=\"0\">半透明（默认）</option>\n" +
                "                <option value=\"1\">半不透明</option>\n" +
                "                <option value=\"2\">不透明</option>\n" +
                "            </select>\n" +
                "            <button onclick=\"savePlayback()\">保存播放设置</button>\n" +
                "            <div id=\"playbackStatus\" class=\"status\"></div>\n" +
                "            <div class=\"info\">播放设置需重启电视端应用后生效；显示设置下次打开频道列表时生效。</div>\n" +
                "        </div>\n" +
                "        \n" +
                "        <div class=\"section\">\n" +
                "            <h2>关于 / 更新</h2>\n" +
                "            <div id=\"appVersionInfo\" class=\"info\"></div>\n" +
                "            <label style=\"display:flex;align-items:center;gap:8px;margin:12px 0;\">\n" +
                "                <input type=\"checkbox\" id=\"updateAutoCheck\"> 自动检查更新（每天最多一次）\n" +
                "            </label>\n" +
                "            <div id=\"updateStateInfo\" class=\"info\"></div>\n" +
                "            <div id=\"updateChangelog\" class=\"info\" style=\"white-space:pre-line;\"></div>\n" +
                "            <button onclick=\"checkUpdate()\">检查更新</button>\n" +
                "            <button onclick=\"installUpdate()\" id=\"updateInstallBtn\" style=\"margin-left:8px;\">立即更新</button>\n" +
                "            <div id=\"updateStatus\" class=\"status\"></div>\n" +
                "            <div class=\"info\">「立即更新」会让电视自动下载并校验安装包，然后在电视上弹出系统安装确认——安装需要有人在电视旁按确认。</div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    <script>\n" +
                "        function saveM3uUrl() {\n" +
                "            const url = document.getElementById('m3uUrl').value.trim();\n" +
                "            if (!url) { alert('请输入 M3U URL'); return; }\n" +
                "            fetch('/config/m3u-url', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'm3u_url=' + encodeURIComponent(url)\n" +
                "            }).then(r => r.json()).then(d => {\n" +
                "                const status = document.getElementById('m3uStatus');\n" +
                "                if (d.success) {\n" +
                "                    status.className = 'status success';\n" +
                "                    status.textContent = '✓ ' + d.message + ' (TV 端将自动重新加载)';\n" +
                "                } else {\n" +
                "                    status.className = 'status error';\n" +
                "                    status.textContent = '✗ ' + d.message;\n" +
                "                }\n" +
                "            }).catch(e => {\n" +
                "                document.getElementById('m3uStatus').className = 'status error';\n" +
                "                document.getElementById('m3uStatus').textContent = '✗ 网络错误: ' + e;\n" +
                "            });\n" +
                "        }\n" +
                "        function saveEpgUrl() {\n" +
                "            const url = document.getElementById('epgUrl').value.trim();\n" +
                "            if (!url) { alert('请输入 EPG 地址'); return; }\n" +
                "            fetch('/config/epg-url', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'epg_url=' + encodeURIComponent(url)\n" +
                "            }).then(r => r.json()).then(d => {\n" +
                "                const status = document.getElementById('epgStatus');\n" +
                "                if (d.success) {\n" +
                "                    status.className = 'status success';\n" +
                "                    status.textContent = '✓ ' + d.message;\n" +
                "                } else {\n" +
                "                    status.className = 'status error';\n" +
                "                    status.textContent = '✗ ' + d.message;\n" +
                "                }\n" +
                "            }).catch(e => {\n" +
                "                document.getElementById('epgStatus').className = 'status error';\n" +
                "                document.getElementById('epgStatus').textContent = '✗ 网络错误: ' + e;\n" +
                "            });\n" +
                "        }\n" +
                "        function saveEpgPreference() {\n" +
                "            const pref = document.getElementById('epgPreference').value;\n" +
                "            fetch('/config/epg-preference', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'epg_preference=' + encodeURIComponent(pref)\n" +
                "            }).then(r => r.json()).then(d => {\n" +
                "                const status = document.getElementById('epgPrefStatus');\n" +
                "                if (d.success) {\n" +
                "                    status.className = 'status success';\n" +
                "                    status.textContent = '✓ ' + d.message + ' (TV 端将自动重新加载)';\n" +
                "                } else {\n" +
                "                    status.className = 'status error';\n" +
                "                    status.textContent = '✗ ' + d.message;\n" +
                "                }\n" +
                "            }).catch(e => {\n" +
                "                document.getElementById('epgPrefStatus').className = 'status error';\n" +
                "                document.getElementById('epgPrefStatus').textContent = '✗ 网络错误: ' + e;\n" +
                "            });\n" +
                "        }\n" +
                "        function clearM3uFile() {\n" +
                "            fetch('/config/m3u-file-clear', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'action=clear'\n" +
                "            }).then(r => r.json()).then(d => {\n" +
                "                const status = document.getElementById('m3uFileStatus');\n" +
                "                if (d.success) {\n" +
                "                    status.className = 'status success';\n" +
                "                    status.textContent = '✓ ' + d.message + ' (TV 端将自动重新加载)';\n" +
                "                    document.getElementById('m3uFileInfo').textContent = '未使用本地文件，使用上方 M3U 地址';\n" +
                "                    document.getElementById('clearM3uFileBtn').style.display = 'none';\n" +
                "                } else {\n" +
                "                    status.className = 'status error';\n" +
                "                    status.textContent = '✗ ' + d.message;\n" +
                "                }\n" +
                "            }).catch(e => {\n" +
                "                document.getElementById('m3uFileStatus').className = 'status error';\n" +
                "                document.getElementById('m3uFileStatus').textContent = '✗ 网络错误: ' + e;\n" +
                "            });\n" +
                "        }\n" +
                "        function uploadM3uFile() {\n" +
                "            const input = document.getElementById('m3uUploadInput');\n" +
                "            if (!input.files || !input.files.length) { alert('请先选择文件'); return; }\n" +
                "            const status = document.getElementById('uploadStatus');\n" +
                "            status.className = 'status';\n" +
                "            status.textContent = '上传中…';\n" +
                "            status.style.display = 'block';\n" +
                "            const form = new FormData();\n" +
                "            form.append('file', input.files[0]);\n" +
                "            fetch('/config/m3u-file-upload', {method: 'POST', body: form})\n" +
                "                .then(r => r.json()).then(d => {\n" +
                "                    status.className = 'status ' + (d.success ? 'success' : 'error');\n" +
                "                    status.textContent = (d.success ? '✓ ' : '✗ ') + d.message + (d.success ? '（电视端已重新加载）' : '');\n" +
                "                    if (d.success) {\n" +
                "                        document.getElementById('m3uFileInfo').textContent = '本地 M3U 文件生效: ' + input.files[0].name;\n" +
                "                        document.getElementById('clearM3uFileBtn').style.display = 'block';\n" +
                "                    }\n" +
                "                }).catch(e => {\n" +
                "                    status.className = 'status error';\n" +
                "                    status.textContent = '✗ 上传失败: ' + e;\n" +
                "                });\n" +
                "        }\n" +
                "        function saveChannels() {\n" +
                "            const body = 'merge_same_name_channels=' + (document.getElementById('mergeSameName').checked ? '1' : '0');\n" +
                "            fetch('/config/channels', {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body})\n" +
                "                .then(r => r.json()).then(d => {\n" +
                "                    const status = document.getElementById('channelsStatus');\n" +
                "                    status.className = 'status ' + (d.success ? 'success' : 'error');\n" +
                "                    status.textContent = (d.success ? '✓ ' : '✗ ') + d.message;\n" +
                "                }).catch(e => {\n" +
                "                    status.className = 'status error';\n" +
                "                    status.textContent = '✗ 网络错误: ' + e;\n" +
                "                });\n" +
                "        }\n" +
                "        function savePlayback() {\n" +
                "            const body = 'video_render_mode=' + encodeURIComponent(document.getElementById('videoRenderMode').value)\n" +
                "                + '&video_decode_mode=' + encodeURIComponent(document.getElementById('videoDecodeMode').value)\n" +
                "                + '&video_tunneling=' + (document.getElementById('videoTunneling').checked ? '1' : '0')\n" +
                "                + '&epg_display_enabled=' + encodeURIComponent(document.getElementById('epgDisplay').value)\n" +
                "                + '&catchup_badge=' + encodeURIComponent(document.getElementById('catchupBadge').value)\n" +
                "                + '&sidebar_opacity=' + encodeURIComponent(document.getElementById('sidebarOpacity').value);\n" +
                "            Promise.all([\n" +
                "                fetch('/config/playback', {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body}),\n" +
                "                fetch('/config/display', {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body})\n" +
                "            ]).then(rs => Promise.all(rs.map(r => r.json()))).then(ds => {\n" +
                "                const status = document.getElementById('playbackStatus');\n" +
                "                const ok = ds.every(d => d.success);\n" +
                "                status.className = 'status ' + (ok ? 'success' : 'error');\n" +
                "                status.textContent = ok ? '✓ ' + ds[0].message : '✗ ' + (ds.find(d => !d.success) || {}).message;\n" +
                "            }).catch(e => {\n" +
                "                document.getElementById('playbackStatus').className = 'status error';\n" +
                "                document.getElementById('playbackStatus').textContent = '✗ 网络错误: ' + e;\n" +
                "            });\n" +
                "        }\n" +
                "        function saveUpdateSettings() {\n" +
                "            const body = 'update_auto_check=' + (document.getElementById('updateAutoCheck').checked ? '1' : '0');\n" +
                "            fetch('/config/update-settings', {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body})\n" +
                "                .then(r => r.json()).then(d => {\n" +
                "                    const status = document.getElementById('updateStatus');\n" +
                "                    status.className = 'status ' + (d.success ? 'success' : 'error');\n" +
                "                    status.textContent = (d.success ? '✓ ' : '✗ ') + d.message;\n" +
                "                }).catch(e => console.error('保存更新设置失败:', e));\n" +
                "        }\n" +
                "        function updateStateText(d) {\n" +
                "            const state = d.update_state || '';\n" +
                "            if (state === 'checking') return '正在检查更新…';\n" +
                "            if (state === 'up_to_date') return '已是最新版本';\n" +
                "            if (state === 'update_available') return '发现新版本 ' + (d.update_latest_version || '');\n" +
                "            if (state === 'downloading') return '电视正在下载… ' + (d.update_progress || 0) + '%';\n" +
                "            if (state === 'ready') return '已下载并校验通过，等待在电视上确认安装';\n" +
                "            if (state === 'installing') return '等待电视上的安装确认…';\n" +
                "            if (state === 'needs_permission') return '电视需要先允许 LiteTV 安装更新（请在电视上打开设置）';\n" +
                "            if (state === 'error') return '检查失败：' + updateErrorText(d.update_error);\n" +
                "            return '尚未检查';\n" +
                "        }\n" +
                // Error codes are the app's own enums; translate them once for the page.
                "        function updateErrorText(code) {\n" +
                "            const errors = {NETWORK: '网络不可用', HTTP: '服务器错误', HTTP_NOT_FOUND: '该版本尚未发布更新信息', PARSE: '更新信息格式不可用', NO_BUILD_FOR_DEVICE: '没有适配电视 CPU 的安装包', CHECKSUM: '安装包校验失败，已删除', SIGNATURE: '安装包签名与应用不一致，已拒绝', STORAGE: '存储空间异常', INSTALL_BLOCKED: '系统阻止安装，需在电视上允许', INSTALL_FAILED: '安装失败', CANCELLED: '已取消下载', UNKNOWN: '未知错误'};\n" +
                "            return errors[code] || code || '未知错误';\n" +
                "        }\n" +
                "        function renderUpdate(d) {\n" +
                "            document.getElementById('appVersionInfo').textContent = '电视端版本: LiteTV ' + (d.app_version_name || '?') + ' (' + (d.app_version_code || 0) + ')' + (d.update_last_check_ms ? '，上次检查 ' + new Date(d.update_last_check_ms).toLocaleString() : '，从未检查');\n" +
                "            document.getElementById('updateStateInfo').textContent = updateStateText(d);\n" +
                "            const changelog = d.update_changelog || [];\n" +
                "            document.getElementById('updateChangelog').textContent = changelog.length ? ('更新内容：\\n· ' + changelog.join('\\n· ')) : '';\n" +
                "        }\n" +
                "        function checkUpdate() {\n" +
                "            const status = document.getElementById('updateStatus');\n" +
                "            status.className = 'status success';\n" +
                "            status.textContent = '正在让电视检查更新…';\n" +
                "            fetch('/config/update-check', {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: 'action=check'})\n" +
                "                .then(r => r.json()).then(d => {\n" +
                "                    renderUpdate(d);\n" +
                "                    status.className = 'status ' + (d.success ? 'success' : 'error');\n" +
                "                    status.textContent = (d.success ? '✓ ' : '✗ ') + updateStateText(d);\n" +
                "                }).catch(e => {\n" +
                "                    status.className = 'status error';\n" +
                "                    status.textContent = '✗ 无法让电视检查更新: ' + e;\n" +
                "                });\n" +
                "        }\n" +
                "        function installUpdate() {\n" +
                "            const status = document.getElementById('updateStatus');\n" +
                "            if (!confirm('将在电视上下载并校验安装包，然后需要有人在电视上确认安装。继续？')) return;\n" +
                "            fetch('/config/update-install', {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: 'action=install'})\n" +
                "                .then(r => r.json()).then(d => {\n" +
                "                    if (!d.success) {\n" +
                "                        status.className = 'status error';\n" +
                "                        status.textContent = '✗ ' + d.message;\n" +
                "                        return;\n" +
                "                    }\n" +
                "                    status.className = 'status success';\n" +
                "                    status.textContent = '✓ ' + d.message;\n" +
                "                    pollUpdate(0);\n" +
                "                }).catch(e => {\n" +
                "                    status.className = 'status error';\n" +
                "                    status.textContent = '✗ 无法触发更新: ' + e;\n" +
                "                });\n" +
                "        }\n" +
                "        function pollUpdate(attempt) {\n" +
                "            if (attempt > 40) return;\n" +
                "            fetch('/config/get').then(r => r.json()).then(d => {\n" +
                "                renderUpdate(d);\n" +
                "                const status = document.getElementById('updateStatus');\n" +
                "                status.className = 'status ' + (d.update_state === 'error' ? 'error' : 'success');\n" +
                "                status.textContent = updateStateText(d);\n" +
                "                const state = d.update_state || '';\n" +
                "                if (state === 'downloading' || state === 'checking' || state === 'ready') {\n" +
                "                    setTimeout(() => pollUpdate(attempt + 1), 1500);\n" +
                "                }\n" +
                "            }).catch(e => console.error('轮询更新状态失败:', e));\n" +
                "        }\n" +
                "        window.onload = function() {\n" +
                "            fetch('/config/get').then(r => r.json()).then(d => {\n" +
                "                document.getElementById('m3uUrl').value = d.m3u_url || '';\n" +
                "                document.getElementById('epgUrl').value = d.epg_url || '';\n" +
                "                document.getElementById('epgPreference').value = d.epg_preference || 'auto';\n" +
                "                document.getElementById('videoRenderMode').value = d.video_render_mode || 'surface';\n" +
                "                document.getElementById('videoDecodeMode').value = d.video_decode_mode || 'auto';\n" +
                "                document.getElementById('videoTunneling').checked = !!d.video_tunneling;\n" +
                "                document.getElementById('epgDisplay').value = d.epg_display_enabled === false ? '0' : '1';\n" +
                "                document.getElementById('catchupBadge').value = d.catchup_badge === false ? '0' : '1';\n" +
                "                document.getElementById('sidebarOpacity').value = String(d.sidebar_opacity == null ? 0 : d.sidebar_opacity);\n" +
                "                const m3uFile = d.m3u_file_path || '';\n" +
                "                const dropDir = d.m3u_drop_dir || '';\n" +
                "                if (dropDir) {\n" +
                "                    document.getElementById('m3uDropDirInfo').textContent = '也可把 .m3u 文件放到电视上：' + dropDir + '，再在电视设置里点「从文件夹导入」';\n" +
                "                }\n" +
                "                if (m3uFile) {\n" +
                "                    document.getElementById('m3uFileInfo').textContent = '本地 M3U 文件生效: ' + m3uFile;\n" +
                "                    document.getElementById('clearM3uFileBtn').style.display = 'block';\n" +
                "                } else {\n" +
                "                    document.getElementById('m3uFileInfo').textContent = '未使用本地文件，使用上方 M3U 地址';\n" +
                "                }\n" +
                "                const m3uUrls = d.m3u_epg_urls || [];\n" +
                "                document.getElementById('epgM3uInfo').textContent = m3uUrls.length\n" +
                "                    ? 'M3U 内置 EPG: ' + m3uUrls.join(' | ')\n" +
                "                    : '当前 M3U 未提供内置 EPG';\n" +
                "                const active = d.active_epg_url || '';\n" +
                "                document.getElementById('epgActiveInfo').textContent = active\n" +
                "                    ? '当前生效 EPG: ' + active\n" +
                "                    : '当前无生效 EPG 来源';\n" +
                "                document.getElementById('mergeSameName').checked = d.merge_same_name_channels !== false;\n" +
                "                document.getElementById('updateAutoCheck').checked = d.update_auto_check !== false;\n" +
                "                renderUpdate(d);\n" +
                "            }).catch(e => console.error('加载配置失败:', e));\n" +
                "        };\n" +
                "        document.getElementById('updateAutoCheck').addEventListener('change', saveUpdateSettings);\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }
}
