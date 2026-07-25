package com.cabletv.player.server;

import android.content.Context;
import android.util.Log;

import com.cabletv.player.config.AppConfig;
import com.google.gson.JsonObject;

public class ConfigWebServer {
    private static final String TAG = "ConfigWebServer";
    private final Context mContext;
    private static ConfigWebServer sInstance;

    public static ConfigWebServer getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ConfigWebServer(context);
        }
        return sInstance;
    }

    public ConfigWebServer(Context context) {
        mContext = context.getApplicationContext();
    }

    public static void startServer(Context context) {
        Log.i(TAG, "Web server placeholder - will be implemented in next phase");
    }

    public static void stopServer() {
        Log.i(TAG, "Web server stop");
    }
}
