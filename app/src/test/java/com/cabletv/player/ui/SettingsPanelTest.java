package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tests for the settings panel: the local M3U file feature, and the wording of the update
 * section.
 *
 * <p>The panel is a view inside the player rather than a screen of its own, so it can be built
 * without an activity: everything it needs from the activity goes through a host.
 */
@RunWith(RobolectricTestRunner.class)
public class SettingsPanelTest {

    private File mLocalFile;
    private SettingsPanel mPanel;
    private Intent mPickerIntent;
    private boolean mConfigChanged;

    @Before
    public void setUp() throws Exception {
        AppConfig.setM3uFilePath("");
        AppConfig.setM3uUrl("");
        // The updater stores when it last checked; without clearing it, a leftover timestamp from
        // an earlier test makes the "never checked" line fail.
        AppConfig.setUpdateLastCheckMs(0L);
        mLocalFile = new File(RuntimeEnvironment.getApplication().getCacheDir(), "picker-test.m3u");
        try (FileOutputStream f = new FileOutputStream(mLocalFile)) {
            f.write("#EXTM3U\\n#EXTINF:-1,C\\nhttp://s\\n".getBytes(StandardCharsets.UTF_8));
        }
        mPickerIntent = null;
        mConfigChanged = false;
        mPanel = new SettingsPanel(RuntimeEnvironment.getApplication(), new SettingsPanel.Host() {
            @Override
            public void startFilePicker(Intent intent) {
                mPickerIntent = intent;
            }

            @Override
            public void requestStoragePermission(String[] permissions, int requestCode) {
                // The test never gets that far; the panel falls back to the folder import.
            }

            @Override
            public void openSystemScreen(Intent intent) {
                // Not needed for these tests.
            }

            @Override
            public void onConfigurationChanged() {
                mConfigChanged = true;
            }
        });
    }

    @Test
    public void pickButton_opensDocumentPicker() {
        mPanel.findViewById(R.id.pick_m3u_file_btn).performClick();

        assertNotNull("the system document picker must be asked for", mPickerIntent);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, mPickerIntent.getAction());
    }

    @Test
    public void configuredFile_isShownInSettings() {
        AppConfig.setM3uFilePath(mLocalFile.getAbsolutePath());

        mPanel.loadValues();

        TextView path = mPanel.findViewById(R.id.m3u_file_path_text);
        assertTrue("the active file must be named",
                path.getText().toString().contains(mLocalFile.getName()));
    }

    @Test
    public void noFile_showsNoneState() {
        AppConfig.setM3uFilePath("");

        mPanel.loadValues();

        TextView path = mPanel.findViewById(R.id.m3u_file_path_text);
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.m3u_file_none),
                path.getText().toString());
    }

    @Test
    public void clearButton_removesConfiguredFile() {
        AppConfig.setM3uFilePath(mLocalFile.getAbsolutePath());

        mPanel.findViewById(R.id.clear_m3u_file_btn).performClick();

        assertEquals("the stored path must be gone", "",
                AppConfig.getM3uFilePath());
        assertTrue("the file itself must be gone", !mLocalFile.exists());
    }

    @Test
    public void panel_comesUpWithAllSections() {
        assertNotNull(mPanel.findViewById(R.id.m3u_url_input));
        assertNotNull(mPanel.findViewById(R.id.epg_url_input));
        assertNotNull(mPanel.findViewById(R.id.web_server_toggle));
        assertNotNull(mPanel.findViewById(R.id.merge_same_name_toggle));
        assertNotNull(mPanel.findViewById(R.id.epg_preference_group));
        assertNotNull(mPanel.findViewById(R.id.sidebar_opacity_group));
        assertNotNull(mPanel.findViewById(R.id.video_render_mode_group));
        assertNotNull(mPanel.findViewById(R.id.video_decode_mode_group));
        assertNotNull(mPanel.findViewById(R.id.video_tunneling_toggle));
        assertNotNull(mPanel.findViewById(R.id.update_auto_check_toggle));
        assertNotNull(mPanel.findViewById(R.id.check_update_btn));
    }

    @Test
    public void thePanelTakesTwoThirdsOfTheScreen() {
        android.widget.FrameLayout.LayoutParams lp = mPanel.createPanelLayoutParams();
        int screenWidth = RuntimeEnvironment.getApplication().getResources()
                .getDisplayMetrics().widthPixels;

        assertEquals("the panel must leave a third of the picture visible",
                Math.round(screenWidth * 2f / 3f), lp.width);
        assertEquals("the panel hangs on the right edge",
                android.view.Gravity.END, lp.gravity);
    }

    @Test
    public void updateSection_showsVersionAndShipsAutoCheckOn() {
        AppConfig.setUpdateAutoCheckEnabled(true);
        mPanel.loadValues();

        TextView version = mPanel.findViewById(R.id.app_version_text);
        assertTrue("the installed version must be visible for support questions",
                version.getText().toString().startsWith("LiteTV "));
        ToggleSwitchView toggle = mPanel.findViewById(R.id.update_auto_check_toggle);
        assertTrue("automatic checks are on by default", toggle.isChecked());

        toggle.performClick();
        assertFalse("turning the switch off must be stored", AppConfig.isUpdateAutoCheckEnabled());
    }

    @Test
    public void theLastCheckLine_saysNeverWhenNothingWasCheckedYet() {
        // The wording is checked directly: the stored timestamp is shared state that another
        // test (or a background check) may have written in the meantime.
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.update_never_checked),
                SettingsPanel.formatLastCheck(RuntimeEnvironment.getApplication(), 0L));
        assertTrue("a check that already happened is dated",
                SettingsPanel.formatLastCheck(RuntimeEnvironment.getApplication(), 1_700_000_000_000L)
                        .contains("2023"));
    }

    @Test
    public void savingThePlaylistUrl_tellsThePlayerToReload() {
        ((android.widget.EditText) mPanel.findViewById(R.id.m3u_url_input))
                .setText("http://example.com/list.m3u");

        mPanel.findViewById(R.id.save_m3u_btn).performClick();

        assertEquals("http://example.com/list.m3u", AppConfig.getM3uUrl());
        assertTrue("the player has to learn about the new playlist", mConfigChanged);
    }

    @Test
    public void savingTheGuideUrl_tellsThePlayerToReload() {
        ((android.widget.EditText) mPanel.findViewById(R.id.epg_url_input))
                .setText("http://example.com/epg.xml");

        mPanel.findViewById(R.id.save_epg_btn).performClick();

        assertEquals("http://example.com/epg.xml", AppConfig.getEpgUrl());
        assertTrue("the player has to learn about the new guide", mConfigChanged);
    }

    @Test
    public void thePanelStartsHiddenAndSlidesInWhenShown() {
        assertFalse("the panel must not block the picture before it is asked for",
                mPanel.isPanelVisible());

        mPanel.showPanel();

        assertTrue(mPanel.isPanelVisible());
    }

    @Test
    public void theReplayBadgeIsOnByDefaultAndCanBeSwitchedOff() {
        AppConfig.setCatchupBadgeEnabled(true);
        mPanel.loadValues();

        ToggleSwitchView badge = mPanel.findViewById(R.id.catchup_badge_toggle);
        assertTrue("a viewer must be able to tell a replay from live TV",
                badge.isChecked());

        badge.performClick();
        assertFalse("the switch must be stored", AppConfig.isCatchupBadgeEnabled());
        assertTrue("the picture has to follow immediately", mConfigChanged);
        AppConfig.setCatchupBadgeEnabled(true);
    }

    /**
     * Walking past a text field with the remote must not open the keyboard: on a television it
     * covers half the picture, and the viewer was only on their way to the next row.
     */
    @Test
    public void aTextFieldOnlyStartsEditingWhenAsked() {
        View holder = mPanel.findViewById(R.id.m3u_url_holder);
        View field = mPanel.findViewById(R.id.m3u_url_input);

        assertNotNull("the field needs a focusable frame around it", holder);
        assertNotNull(field);
        assertTrue("the frame is what the remote lands on", holder.isFocusable());
        assertFalse("the field itself must not take the focus", field.isFocusable());

        // The frame is clickable, so OK on it is what starts editing (verified on the television:
        // walking the focus past the row leaves the keyboard closed, OK opens it).
        assertTrue("the frame must answer OK", holder.isClickable());
    }

    @Test
    public void theReplayBadgeSitsWithTheGuideSettings() {
        // Both live in the same part of the panel: the guide and what it shows on screen.
        assertNotNull(mPanel.findViewById(R.id.epg_preference_group));
        assertNotNull(mPanel.findViewById(R.id.catchup_badge_toggle));
    }

    /**
     * BACK used to close the whole panel even while typing, because the activity took the key
     * before the field saw it. The field's own BACK handling was therefore dead code.
     */
    @Test
    public void backLeavesTheFieldFirstAndThePanelSecond() {
        assertEquals(SettingsPanel.BackRoute.LEAVE_FIELD,
                SettingsPanel.backRouteFor(true));
        assertEquals(SettingsPanel.BackRoute.CLOSE_PANEL,
                SettingsPanel.backRouteFor(false));
    }

    @Test
    public void nothingIsBeingEditedWhenThePanelOpens() {
        assertFalse("the panel opens on a normal control, not in a text field",
                mPanel.isEditingText());
    }

    @Test
    public void theSwitchAnswersTheRemote() {
        ToggleSwitchView toggle = mPanel.findViewById(R.id.web_server_toggle);
        boolean before = toggle.isChecked();

        // The remote's OK key, not a finger.
        toggle.onKeyUp(android.view.KeyEvent.KEYCODE_DPAD_CENTER, null);

        assertEquals("OK must flip the switch", !before, toggle.isChecked());
    }
}
