package com.cabletv.player.server;

/**
 * The settings the web page can read and write, in one place.
 *
 * <p>Adding a setting used to mean editing four spots: {@code AppConfig}, the settings panel, the
 * JSON of {@code /config/get} plus a POST handler, and the hand-written HTML/JS. Nothing tied
 * them together, so a forgotten one showed up as "the page has no control for it" or "the page
 * sends a field nobody reads" -- both silent.
 *
 * <p>This list is the contract those four spots are checked against by
 * {@code WebSettingsContractTest}: every entry has to appear in the JSON and in the page. The
 * JSON and the page are still written by hand, because their shapes differ deliberately (one is
 * data, the other layout), but a new setting can no longer go missing unnoticed.
 */
enum WebSetting {

    M3U_URL("m3u_url", "app_m3u_url"),
    EPG_URL("epg_url", "app_epg_url"),
    EPG_PREFERENCE("epg_preference", "app_epg_preference"),
    MERGE_SAME_NAME_CHANNELS("merge_same_name_channels", "app_merge_same_name_channels"),
    EPG_DISPLAY("epg_display_enabled", "app_epg_display_enabled"),
    SIDEBAR_OPACITY("sidebar_opacity", "app_sidebar_opacity"),
    CATCHUP_BADGE("catchup_badge", "app_catchup_badge"),
    VIDEO_RENDER_MODE("video_render_mode", "app_video_render_mode"),
    VIDEO_DECODE_MODE("video_decode_mode", "app_video_decode_mode"),
    VIDEO_TUNNELING("video_tunneling", "app_video_tunneling"),
    UPDATE_AUTO_CHECK("update_auto_check", "app_update_auto_check");

    /** The name used on the wire: JSON key, form field and JS variable alike. */
    final String field;

    /** Where {@code AppConfig} stores it. */
    final String configKey;

    WebSetting(String field, String configKey) {
        this.field = field;
        this.configKey = configKey;
    }

    static WebSetting byField(String field) {
        for (WebSetting setting : values()) {
            if (setting.field.equals(field)) {
                return setting;
            }
        }
        return null;
    }
}
