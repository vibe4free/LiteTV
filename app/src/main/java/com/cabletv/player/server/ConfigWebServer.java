package com.cabletv.player.server;

import android.content.Context;
import android.util.Log;

import com.cabletv.player.config.AppConfig;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

public class ConfigWebServer {
    private static final String TAG = "ConfigWebServer";
    private static ServerSocket sServerSocket;
    private static Thread sServerThread;
    private static volatile boolean sRunning = false;

    public interface OnConfigChangeListener {
        void onM3uUrlChanged(String newUrl);
        void onEpgUrlChanged(String newUrl);
    }

    private static OnConfigChangeListener sConfigChangeListener;

    public static void startServer(Context context, int port) {
        if (sRunning && sServerSocket != null) {
            Log.i(TAG, "Server already running on port " + sServerSocket.getLocalPort());
            return;
        }

        sServerThread = new Thread(() -> runServer(port));
        sServerThread.setDaemon(true);
        sServerThread.start();
    }

    public static void startServer(Context context) {
        startServer(context, 8899);
    }

    public static void setConfigChangeListener(OnConfigChangeListener listener) {
        sConfigChangeListener = listener;
    }

    public static void stopServer() {
        sRunning = false;
        if (sServerSocket != null) {
            try {
                sServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
        }
        Log.i(TAG, "Web server stopped");
    }

    private static void runServer(int port) {
        try {
            sServerSocket = new ServerSocket(port);
            sRunning = true;
            Log.i(TAG, "Web server started on port " + port);

            while (sRunning) {
                Socket clientSocket = sServerSocket.accept();
                new Thread(() -> handleClient(clientSocket)).start();
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
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String requestLine = reader.readLine();

            if (requestLine == null) {
                socket.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts[1];

            // Read headers
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            // Read body
            String body = "";
            if (contentLength > 0) {
                char[] buffer = new char[contentLength];
                reader.read(buffer);
                body = new String(buffer);
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
                response = "404 Not Found";
                sendResponse(socket, response, 404, "text/plain");
                socket.close();
                return;
            }

            sendResponse(socket, response, 200, contentType);
            socket.close();
        } catch (Exception e) {
            Log.e(TAG, "Error handling client", e);
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private static void sendResponse(Socket socket, String body, int statusCode, String contentType) throws IOException {
        String status = statusCode == 200 ? "OK" : "Not Found";
        String response = "HTTP/1.1 " + statusCode + " " + status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.getBytes().length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n" +
                "\r\n" + body;

        OutputStream os = socket.getOutputStream();
        os.write(response.getBytes());
        os.flush();
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
