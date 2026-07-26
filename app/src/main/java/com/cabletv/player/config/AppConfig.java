package com.cabletv.player.config;

import android.content.Context;

import com.orhanobut.hawk.Hawk;

public class AppConfig {
    private static final String M3U_URL_KEY = "app_m3u_url";
    private static final String EPG_URL_KEY = "app_epg_url";
    private static final String WEB_SERVER_ENABLED_KEY = "app_web_server_enabled";
    private static final String WEB_SERVER_PORT_KEY = "app_web_server_port";
    private static final String SIDEBAR_ALPHA_KEY = "app_sidebar_alpha";
    private static final String M3U_FILE_PATH_KEY = "app_m3u_file_path";
    private static final String EPG_DISPLAY_KEY = "app_epg_display_enabled";

    private static final String DEFAULT_EPG_URL = "";
    private static final int DEFAULT_WEB_SERVER_PORT = 8899;
    private static final float DEFAULT_SIDEBAR_ALPHA = 0.75f;

    // M3U URL
    public static String getM3uUrl() {
        return Hawk.get(M3U_URL_KEY, "");
    }

    public static void setM3uUrl(String url) {
        Hawk.put(M3U_URL_KEY, url);
    }

    // EPG URL
    public static String getEpgUrl() {
        return Hawk.get(EPG_URL_KEY, DEFAULT_EPG_URL);
    }

    public static void setEpgUrl(String url) {
        Hawk.put(EPG_URL_KEY, url);
    }

    // Web Server
    public static boolean isWebServerEnabled() {
        return Hawk.get(WEB_SERVER_ENABLED_KEY, true);  // Default: enabled for first launch
    }

    public static void setWebServerEnabled(boolean enabled) {
        Hawk.put(WEB_SERVER_ENABLED_KEY, enabled);
    }

    public static int getWebServerPort() {
        return Hawk.get(WEB_SERVER_PORT_KEY, DEFAULT_WEB_SERVER_PORT);
    }

    public static void setWebServerPort(int port) {
        Hawk.put(WEB_SERVER_PORT_KEY, port);
    }

    // Sidebar transparency
    public static float getSidebarAlpha() {
        return Hawk.get(SIDEBAR_ALPHA_KEY, DEFAULT_SIDEBAR_ALPHA);
    }

    public static void setSidebarAlpha(float alpha) {
        Hawk.put(SIDEBAR_ALPHA_KEY, alpha);
    }

    // M3U File path (for uploaded files)
    public static String getM3uFilePath() {
        return Hawk.get(M3U_FILE_PATH_KEY, "");
    }

    public static void setM3uFilePath(String path) {
        Hawk.put(M3U_FILE_PATH_KEY, path);
    }

    // EPG Display toggle
    public static boolean isEpgDisplayEnabled() {
        return Hawk.get(EPG_DISPLAY_KEY, true);  // Default: enabled
    }

    public static void setEpgDisplayEnabled(boolean enabled) {
        Hawk.put(EPG_DISPLAY_KEY, enabled);
    }
}
