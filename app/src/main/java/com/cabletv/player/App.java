package com.cabletv.player;

import android.app.Application;
import android.util.Log;

import com.orhanobut.hawk.Hawk;

public class App extends Application {
    private static final String TAG = "CableTV";

    @Override
    public void onCreate() {
        super.onCreate();
        Hawk.init(this).build();
        setupExceptionHandler();
    }

    private void setupExceptionHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught exception", throwable);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }
}
