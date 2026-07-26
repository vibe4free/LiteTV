package com.cabletv.player.server;

import android.content.Context;
import android.util.Log;

import com.cabletv.player.config.AppConfig;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ConfigWebServer {
    private static final String TAG = "ConfigWebServer";

    /** Request line cap; a legitimate config request is a few dozen bytes. */
    private static final int MAX_REQUEST_LINE_BYTES = 2048;
    /** Header count cap, guards against a client that never sends a blank line. */
    private static final int MAX_HEADER_LINES = 64;
    /** Body cap; the only bodies we accept are two short form fields. */
    private static final int MAX_BODY_BYTES = 64 * 1024;
    /**
     * A client that connects but never sends a request head must release its worker
     * quickly, otherwise a handful of idle sockets makes the server unavailable.
     */
    private static final int HEAD_TIMEOUT_MS = 3_000;
    /** Once a valid head has arrived, allow a slower body transfer. */
    private static final int BODY_TIMEOUT_MS = 10_000;
    /** Bounded worker pool, so many connections cannot exhaust threads. */
    private static final int MAX_WORKERS = 8;

    private static ServerSocket sServerSocket;
    private static Thread sServerThread;
    private static ExecutorService sWorkers;
    private static volatile boolean sRunning = false;

    public interface OnConfigChangeListener {
        void onM3uUrlChanged(String newUrl);
        void onEpgUrlChanged(String newUrl);
    }

    private static OnConfigChangeListener sConfigChangeListener;

    public static synchronized void startServer(Context context, int port) {
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
                    if (contentLength > MAX_BODY_BYTES) {
                        sendResponse(socket, "413 Payload Too Large", 413, "text/plain");
                        return;
                    }
                } else if (lower.startsWith("origin:")) {
                    origin = line.substring("origin:".length()).trim();
                } else if (lower.startsWith("host:")) {
                    host = line.substring("host:".length()).trim();
                } else if (lower.startsWith("content-type:")) {
                    requestContentType = lower.substring("content-type:".length()).trim();
                }
            }

            // Read body (exact byte count, then decode as UTF-8 — char counts and
            // Content-Length disagree for non-ASCII URLs).
            String body = "";
            if (contentLength > 0) {
                socket.setSoTimeout(BODY_TIMEOUT_MS);
                byte[] raw = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int n = in.read(raw, off, contentLength - off);
                    if (n == -1) break;
                    off += n;
                }
                if (off < contentLength) {
                    sendResponse(socket, "400 Bad Request", 400, "text/plain");
                    return;
                }
                body = new String(raw, "UTF-8");
            }

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
                if (!requestContentType.startsWith("application/x-www-form-urlencoded")) {
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
            return length > MAX_BODY_BYTES ? MAX_BODY_BYTES + 1 : (int) length;
        } catch (NumberFormatException e) {
            return -1;
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
                if (sConfigChangeListener != null) {
                    sConfigChangeListener.onM3uUrlChanged(m3uUrl);
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
                if (sConfigChangeListener != null) {
                    sConfigChangeListener.onEpgUrlChanged(epgUrl);
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

    private static String getConfigJson() {
        JsonObject config = new JsonObject();
        config.addProperty("m3u_url", AppConfig.getM3uUrl());
        config.addProperty("epg_url", AppConfig.getEpgUrl());
        config.addProperty("web_server_enabled", AppConfig.isWebServerEnabled());
        return config.toString();
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
                "        </div>\n" +
                "        \n" +
                "        <div class=\"section\">\n" +
                "            <h2>EPG 电子节目单</h2>\n" +
                "            <label for=\"epgUrl\">EPG 地址模板:</label>\n" +
                "            <input type=\"url\" id=\"epgUrl\" placeholder=\"http://epg.example.com/api/diyp?ch={name}&date={date}\">\n" +
                "            <div class=\"info\">支持 {name} 和 {date} 占位符，例如: http://epg.example.com/api/diyp?ch={name}&date={date}</div>\n" +
                "            <button onclick=\"saveEpgUrl()\">保存 EPG 地址</button>\n" +
                "            <div id=\"epgStatus\" class=\"status\"></div>\n" +
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
                "        window.onload = function() {\n" +
                "            fetch('/config/get').then(r => r.json()).then(d => {\n" +
                "                document.getElementById('m3uUrl').value = d.m3u_url || '';\n" +
                "                document.getElementById('epgUrl').value = d.epg_url || '';\n" +
                "            }).catch(e => console.error('加载配置失败:', e));\n" +
                "        };\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }
}
