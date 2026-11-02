package com.cabletv.player.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings without Android.
 *
 * <p>Plain JUnit, no Robolectric: the whole point of the injectable store is that the settings
 * layer can be exercised on its own. Before this, every settings test needed an Android
 * environment just to reach Hawk.
 */
public class AppConfigStoreTest {

    private MemoryStore mStore;

    @Before
    public void setUp() {
        mStore = new MemoryStore();
        AppConfig.useStore(mStore);
    }

    @After
    public void tearDown() {
        AppConfig.useStore(new HawkStore());
    }

    @Test
    public void valuesRoundTripThroughTheStore() {
        AppConfig.setM3uUrl("http://example.com/list.m3u");
        AppConfig.setEpgUrl("http://example.com/epg.xml");

        assertEquals("http://example.com/list.m3u", AppConfig.getM3uUrl());
        assertEquals("http://example.com/epg.xml", AppConfig.getEpgUrl());
        assertEquals(2, mStore.size());
    }

    @Test
    public void defaultsApplyUntilSomethingIsWritten() {
        assertEquals("", AppConfig.getM3uUrl());
        assertTrue("merging same-name channels ships on", AppConfig.isMergeSameNameChannelsEnabled());
        assertTrue("the replay badge ships on", AppConfig.isCatchupBadgeEnabled());
        assertEquals(0, mStore.size());
    }

    @Test
    public void switchesFlipAndPersist() {
        AppConfig.setMergeSameNameChannelsEnabled(false);
        AppConfig.setCatchupBadgeEnabled(false);

        assertFalse(AppConfig.isMergeSameNameChannelsEnabled());
        assertFalse(AppConfig.isCatchupBadgeEnabled());
    }

    /**
     * Writes announce themselves, so the player no longer has to guess on resume by comparing a
     * fingerprint of the settings it cares about.
     */
    @Test
    public void writingASettingTellsTheListeners() {
        final List<Set<String>> seen = new ArrayList<>();
        AppConfig.OnSettingsChangedListener listener =
                keys -> seen.add(new HashSet<>(keys));
        AppConfig.addSettingsListener(listener);
        try {
            AppConfig.setM3uUrl("http://example.com/a.m3u");
            AppConfig.setCatchupBadgeEnabled(false);
        } finally {
            AppConfig.removeSettingsListener(listener);
        }

        assertEquals(2, seen.size());
        assertEquals(1, seen.get(0).size());
        assertEquals(1, seen.get(1).size());
    }

    /**
     * The player asks these instead of naming storage keys, so a renamed key cannot silently stop
     * triggering a reload.
     */
    @Test
    public void changesAreClassifiedWithoutNamingStorageKeys() {
        Set<String> playlist = new HashSet<>();
        playlist.add("app_m3u_url");
        assertTrue(AppConfig.affectsPlaylist(playlist));
        assertFalse(AppConfig.affectsGuide(playlist));

        Set<String> guide = new HashSet<>();
        guide.add("app_epg_preference");
        assertTrue(AppConfig.affectsGuide(guide));
        assertFalse(AppConfig.affectsPlaylist(guide));

        Set<String> neither = new HashSet<>();
        neither.add("app_catchup_badge");
        assertFalse(AppConfig.affectsPlaylist(neither));
        assertFalse(AppConfig.affectsGuide(neither));
    }

    @Test
    public void aThrowingListenerDoesNotBreakTheWrite() {
        AppConfig.OnSettingsChangedListener bad = keys -> {
            throw new IllegalStateException("boom");
        };
        AppConfig.addSettingsListener(bad);
        try {
            AppConfig.setM3uUrl("http://example.com/still-written.m3u");
        } finally {
            AppConfig.removeSettingsListener(bad);
        }

        assertEquals("http://example.com/still-written.m3u", AppConfig.getM3uUrl());
    }
}
