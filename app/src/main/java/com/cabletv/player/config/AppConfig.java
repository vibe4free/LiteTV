package com.cabletv.player.config;

import android.content.Context;

import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import xyz.doikki.videoplayer.player.VideoViewConfig;

public class AppConfig {
    private static final String M3U_URL_KEY = "app_m3u_url";
    private static final String EPG_URL_KEY = "app_epg_url";
    private static final String WEB_SERVER_ENABLED_KEY = "app_web_server_enabled";
    private static final String WEB_SERVER_PORT_KEY = "app_web_server_port";
    private static final String SIDEBAR_OPACITY_KEY = "app_sidebar_opacity";
    private static final String M3U_FILE_PATH_KEY = "app_m3u_file_path";
    private static final String EPG_DISPLAY_KEY = "app_epg_display_enabled";
    private static final String EPG_CACHE_VALIDITY_HOURS_KEY = "app_epg_cache_hours";
    private static final String LAST_CHANNEL_URL_KEY = "app_last_channel_url";
    private static final String FAVORITE_CHANNELS_KEY = "app_favorite_channels";
    private static final String EPG_PREFERENCE_KEY = "app_epg_preference";
    private static final String M3U_EPG_URLS_KEY = "app_m3u_epg_urls";
    private static final String VIDEO_RENDER_MODE_KEY = "app_video_render_mode";
    private static final String VIDEO_DECODE_MODE_KEY = "app_video_decode_mode";
    private static final String VIDEO_TUNNELING_KEY = "app_video_tunneling";
    private static final String UPDATE_AUTO_CHECK_KEY = "app_update_auto_check";
    private static final String UPDATE_LAST_CHECK_KEY = "app_update_last_check_ms";
    private static final String UPDATE_LAST_ERROR_KEY = "app_update_last_error";
    private static final String UPDATE_PENDING_VERSION_KEY = "app_update_pending_version";
    private static final String UPDATE_PENDING_NAME_KEY = "app_update_pending_name";
    private static final String MERGE_SAME_NAME_CHANNELS_KEY = "app_merge_same_name_channels";
    private static final String CATCHUP_BADGE_KEY = "app_catchup_badge";

    private static final String DEFAULT_EPG_URL = "";
    /**
     * Which EPG source wins when both a user-configured URL and one embedded in the M3U exist.
     * auto: user URL first, fall back to the M3U's; m3u: M3U's first, fall back to the user's;
     * user: only ever the user's. The playlist-embedded feed is always a candidate, never a
     * requirement: a playlist without one simply leaves those slots empty.
     */
    public static final String EPG_PREFERENCE_AUTO = "auto";
    public static final String EPG_PREFERENCE_M3U = "m3u";
    public static final String EPG_PREFERENCE_USER = "user";
    private static final int DEFAULT_WEB_SERVER_PORT = 8899;
    private static final int DEFAULT_EPG_CACHE_HOURS = 24; // EPG 缓存 24 小时后刷新

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

    /** EPG source priority; see the constants for the three meanings. */
    public static String getEpgPreference() {
        String preference = Hawk.get(EPG_PREFERENCE_KEY, EPG_PREFERENCE_AUTO);
        if (!EPG_PREFERENCE_AUTO.equals(preference)
                && !EPG_PREFERENCE_M3U.equals(preference)
                && !EPG_PREFERENCE_USER.equals(preference)) {
            return EPG_PREFERENCE_AUTO;
        }
        return preference;
    }

    public static void setEpgPreference(String preference) {
        Hawk.put(EPG_PREFERENCE_KEY, preference);
    }

    /** EPG feed URLs the playlist itself declared, in playlist order (first is primary). */
    public static List<String> getM3uEpgUrls() {
        List<String> urls = Hawk.get(M3U_EPG_URLS_KEY, null);
        return urls != null ? urls : new ArrayList<String>();
    }

    public static void setM3uEpgUrls(List<String> urls) {
        Hawk.put(M3U_EPG_URLS_KEY, urls == null ? new ArrayList<String>() : new ArrayList<>(urls));
    }

    /**
     * The EPG sources to try, in order, given the configured preference. Empty when nothing is
     * available at all. Callers treat the first entry as primary (e.g. for DIYP mode).
     */
    public static List<String> resolveEpgUrls() {
        String userUrl = getEpgUrl();
        List<String> m3uUrls = getM3uEpgUrls();
        String preference = getEpgPreference();

        if (EPG_PREFERENCE_M3U.equals(preference)) {
            if (!m3uUrls.isEmpty()) {
                return m3uUrls;
            }
            return userUrl.isEmpty() ? Collections.<String>emptyList()
                    : Collections.singletonList(userUrl);
        }
        if (EPG_PREFERENCE_USER.equals(preference)) {
            return userUrl.isEmpty() ? Collections.<String>emptyList()
                    : Collections.singletonList(userUrl);
        }
        // auto: the user's own source wins when they bothered to set one.
        if (!userUrl.isEmpty()) {
            return Collections.singletonList(userUrl);
        }
        return m3uUrls;
    }

    /**
     * How video frames reach the screen. A SurfaceView (default) keeps the picture sharp and lets
     * the SoC deinterlace 1080i feeds; a TextureView is the fallback for devices whose SurfaceView
     * misbehaves. Changing it needs an app restart: the player config is read once per process.
     */
    public static final int RENDER_SURFACE = 0;
    public static final int RENDER_TEXTURE = 1;

    public static int getVideoRenderMode() {
        int mode = Hawk.get(VIDEO_RENDER_MODE_KEY, RENDER_SURFACE);
        return mode == RENDER_TEXTURE ? RENDER_TEXTURE : RENDER_SURFACE;
    }

    public static void setVideoRenderMode(int mode) {
        Hawk.put(VIDEO_RENDER_MODE_KEY, mode == RENDER_TEXTURE ? RENDER_TEXTURE : RENDER_SURFACE);
    }

    /**
     * Which decoder the player should use; see {@link VideoViewConfig} for the meanings. Software
     * decoding rescues channels whose hardware decoder is broken on a particular device, at the
     * cost of smoothness. Needs an app restart.
     */
    public static int getVideoDecodeMode() {
        int mode = Hawk.get(VIDEO_DECODE_MODE_KEY, VideoViewConfig.DECODE_AUTO);
        if (mode != VideoViewConfig.DECODE_HARDWARE && mode != VideoViewConfig.DECODE_SOFTWARE) {
            return VideoViewConfig.DECODE_AUTO;
        }
        return mode;
    }

    public static void setVideoDecodeMode(int mode) {
        Hawk.put(VIDEO_DECODE_MODE_KEY, mode);
    }

    /**
     * Whether decoded frames may bypass the compositor and go straight to the display
     * ("tunneling"). Off by default because it has device-specific quirks; on Amlogic-based TVs it
     * is the path that lets the hardware video pipeline handle scaling, deinterlacing and refresh
     * rate. Needs an app restart.
     */
    public static boolean isTunnelingEnabled() {
        return Hawk.get(VIDEO_TUNNELING_KEY, false);
    }

    public static void setTunnelingEnabled(boolean enabled) {
        Hawk.put(VIDEO_TUNNELING_KEY, enabled);
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

    /** How solid the channel-list panels are painted: 0 translucent, 1 semi-opaque, 2 opaque. */
    public static final int SIDEBAR_TRANSLUCENT = 0;
    public static final int SIDEBAR_SEMI_OPAQUE = 1;
    public static final int SIDEBAR_OPAQUE = 2;

    public static int getSidebarOpacity() {
        int level = Hawk.get(SIDEBAR_OPACITY_KEY, SIDEBAR_TRANSLUCENT);
        return level >= SIDEBAR_TRANSLUCENT && level <= SIDEBAR_OPAQUE ? level : SIDEBAR_TRANSLUCENT;
    }

    public static void setSidebarOpacity(int level) {
        Hawk.put(SIDEBAR_OPACITY_KEY, level);
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

    // EPG cache management. How old the data may get lives here; whether the data on disk is
    // still usable is answered by EpgCache alone, so the two cannot disagree.
    public static int getEpgCacheValidityHours() {
        return Hawk.get(EPG_CACHE_VALIDITY_HOURS_KEY, DEFAULT_EPG_CACHE_HOURS);
    }

    public static void setEpgCacheValidityHours(int hours) {
        Hawk.put(EPG_CACHE_VALIDITY_HOURS_KEY, hours);
    }

    // Last played channel URL (for resume playback on app startup)
    public static String getLastChannelUrl() {
        return Hawk.get(LAST_CHANNEL_URL_KEY, "");
    }

    public static void setLastChannelUrl(String url) {
        Hawk.put(LAST_CHANNEL_URL_KEY, url);
    }

    /**
     * Names of the channels the user marked as favourites, oldest first. Names rather than URLs:
     * a playlist may hand out a different URL for the same channel on every refresh.
     */
    public static List<String> getFavoriteChannels() {
        List<String> names = Hawk.get(FAVORITE_CHANNELS_KEY, null);
        return names != null ? names : new ArrayList<String>();
    }

    public static void setFavoriteChannels(List<String> names) {
        Hawk.put(FAVORITE_CHANNELS_KEY, new ArrayList<>(names));
    }

    // Self-update. The install itself is user-initiated; these keys only remember the user's
    // preference and what the last automatic check found.
    public static boolean isUpdateAutoCheckEnabled() {
        return Hawk.get(UPDATE_AUTO_CHECK_KEY, true);  // Default: on, at most once a day
    }

    public static void setUpdateAutoCheckEnabled(boolean enabled) {
        Hawk.put(UPDATE_AUTO_CHECK_KEY, enabled);
    }

    /** When the last check (automatic or manual) completed; 0 when never. */
    public static long getUpdateLastCheckMs() {
        return Hawk.get(UPDATE_LAST_CHECK_KEY, 0L);
    }

    public static void setUpdateLastCheckMs(long timeMs) {
        Hawk.put(UPDATE_LAST_CHECK_KEY, timeMs);
    }

    /**
     * Name of the last {@code UpdateError} ("" when the last check was clean). Stored as text so a
     * later version can still read what an older one wrote, and so the web page can show a reason
     * without a translation table of its own.
     */
    public static String getUpdateLastError() {
        return Hawk.get(UPDATE_LAST_ERROR_KEY, "");
    }

    public static void setUpdateLastError(String error) {
        Hawk.put(UPDATE_LAST_ERROR_KEY, error == null ? "" : error);
    }

    /**
     * versionCode of an update that has been downloaded and verified, so a restart does not make
     * the screen ask for a download again. The file itself is the source of truth; this only says
     * which version it belongs to.
     */
    public static int getUpdatePendingVersion() {
        return Hawk.get(UPDATE_PENDING_VERSION_KEY, 0);
    }

    public static void setUpdatePendingVersion(int versionCode) {
        Hawk.put(UPDATE_PENDING_VERSION_KEY, versionCode);
    }

    /** versionName that goes with {@link #getUpdatePendingVersion()}, for display. */
    public static String getUpdatePendingName() {
        return Hawk.get(UPDATE_PENDING_NAME_KEY, "");
    }

    public static void setUpdatePendingName(String versionName) {
        Hawk.put(UPDATE_PENDING_NAME_KEY, versionName == null ? "" : versionName);
    }

    // Channel list
    /**
     * Whether a playlist that repeats a channel name inside one group becomes a single channel
     * whose sources are tried in order. Subscription links routinely list the same channel once per
     * source, and a viewer treats those as one channel. A playlist without repeated names is
     * unaffected either way.
     */
    public static boolean isMergeSameNameChannelsEnabled() {
        return Hawk.get(MERGE_SAME_NAME_CHANNELS_KEY, true);  // Default: on
    }

    public static void setMergeSameNameChannelsEnabled(boolean enabled) {
        Hawk.put(MERGE_SAME_NAME_CHANNELS_KEY, enabled);
    }

    /**
     * Whether a replay shows a badge in the corner of the picture.
     *
     * <p>On by default: a viewer who rewinds a programme and walks away comes back to something
     * that looks exactly like live TV, and the badge is what tells them it is not. It can be
     * switched off for people who find it distracting.
     */
    public static boolean isCatchupBadgeEnabled() {
        return Hawk.get(CATCHUP_BADGE_KEY, true);
    }

    public static void setCatchupBadgeEnabled(boolean enabled) {
        Hawk.put(CATCHUP_BADGE_KEY, enabled);
    }
}
