package com.cabletv.player.config;

import java.util.HashMap;
import java.util.Map;

/**
 * Storage for tests: nothing is written anywhere and nothing survives the process.
 */
public final class MemoryStore implements SettingsStore {

    private final Map<String, Object> mValues = new HashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object value = mValues.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            // A stale value of another type must not take the caller down.
            return defaultValue;
        }
    }

    @Override
    public void put(String key, Object value) {
        mValues.put(key, value);
    }

    @Override
    public void remove(String key) {
        mValues.remove(key);
    }

    /** Number of stored keys (used by tests). */
    public int size() {
        return mValues.size();
    }

    public void clear() {
        mValues.clear();
    }
}
