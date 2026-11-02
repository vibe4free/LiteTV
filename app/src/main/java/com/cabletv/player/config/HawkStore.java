package com.cabletv.player.config;

import com.orhanobut.hawk.Hawk;

/** The real storage: encrypted preferences through Hawk. */
final class HawkStore implements SettingsStore {

    @Override
    public <T> T get(String key, T defaultValue) {
        return Hawk.get(key, defaultValue);
    }

    @Override
    public void put(String key, Object value) {
        Hawk.put(key, value);
    }

    @Override
    public void remove(String key) {
        Hawk.delete(key);
    }
}
