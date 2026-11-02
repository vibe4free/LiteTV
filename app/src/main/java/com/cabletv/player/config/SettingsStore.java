package com.cabletv.player.config;

/**
 * Where the settings live.
 *
 * <p>Exists so {@link AppConfig} can be exercised without Android: production uses
 * {@link HawkStore}, tests inject {@link MemoryStore} and get a plain JVM run instead of a
 * Robolectric environment.
 */
public interface SettingsStore {

    /** @return the stored value, or {@code defaultValue} when the key has never been written */
    <T> T get(String key, T defaultValue);

    void put(String key, Object value);

    void remove(String key);
}
