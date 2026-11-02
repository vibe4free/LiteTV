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

    private static final String UPLOAD_PATH = "/config/m3u-file-upload";
    /** Bounded worker pool, so many connections cannot exhaust threads. */
    private static final int MAX_WORKERS = 8;

    private static ServerSocket sServerSocket;
    private static Thread sServerThread;
    private static ExecutorService sWorkers;
    private static volatile boolean sRunning = false;

    /**
     * Told when the page changes something that is not a plain setting.
     *
     * <p>Settings themselves are announced by {@code AppConfig}, which notifies every write; the
     * web page used to duplicate that with one callback per setting, so a single save could
     * reload the playlist twice.
     */
    public interface OnConfigChangeListener {
        /** The local playlist file was replaced or removed from the web page. */
        void onM3uFileChanged(String newPath);
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
            HttpRequest request;
            try {
                request = HttpRequest.read(socket, UPLOAD_PATH);
            } catch (HttpRequest.HttpError e) {
                Log.w(TAG, "Rejected request: " + e.getMessage());
                sendResponse(socket, statusText(e.status), e.status, "text/plain");
                return;
            }
            String method = request.method;
            String path = request.path;
            byte[] rawBody = request.body;
            String body = request.bodyText();


            if ("POST".equals(method)) {
                // The server has no authentication, so it must at least refuse
                // browser-driven cross-site writes: any page the user visits could
                // otherwise silently repoint the playlist. Browsers send Origin on
                // cross-site POSTs; local tools (curl and the page we serve) do not
                // trip this check.
                if (isCrossSite(request.origin, request.host)) {
                    Log.w(TAG, "Rejected cross-site POST from origin: " + request.origin);
                    sendResponse(socket, "403 Forbidden", 403, "text/plain");
                    return;
                }
                String requestType = request.contentType.toLowerCase(java.util.Locale.US);
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
                response = handleM3uFileUpload(rawBody, request.contentType);
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

    /** Package-visible so the contract test can check it against the page. */
    static String getConfigJson() {
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

    /** The page ships in the APK and never changes, so it is read once and kept. */
    private static volatile String sIndexHtml;

    private static String getIndexHtml() {
        String cached = sIndexHtml;
        if (cached != null) {
            return cached;
        }
        Context context = sAppContext;
        if (context == null) {
            return UNAVAILABLE_PAGE;
        }
        try (InputStream in = context.getAssets().open("web/index.html")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            String html = new String(out.toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8);
            sIndexHtml = html;
            return html;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Cannot read web/index.html from assets", e);
            return UNAVAILABLE_PAGE;
        }
    }

    private static final String UNAVAILABLE_PAGE =
            "<!DOCTYPE html><html><body>"
            + "<p>Configuration page unavailable. The web assets are missing from this build.</p>"
            + "</body></html>";
}
