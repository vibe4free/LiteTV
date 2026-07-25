package com.cabletv.player.server;

import android.content.Context;
import android.util.Log;

import com.cabletv.player.config.AppConfig;

public class ConfigWebServer {
    private static final String TAG = "ConfigWebServer";

    public static void startServer(Context context, int port) {
        Log.i(TAG, "Web server implementation planned for next phase (port: " + port + ")");
    }

    public static void startServer(Context context) {
        startServer(context, 8899);
    }

    public static void stopServer() {
        Log.i(TAG, "Web server stop");
    }
}
