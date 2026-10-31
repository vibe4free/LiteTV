package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.widget.Button;
import android.widget.TextView;

import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.robolectric.Shadows.shadowOf;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Settings-screen tests for the local M3U file feature: the pick button opens the system
 * document picker, the active file is shown, and the clear button drops the configuration.
 */
@RunWith(RobolectricTestRunner.class)
public class SettingsActivityTest {

    private File mLocalFile;

    @Before
    public void setUp() throws Exception {
        AppConfig.setM3uFilePath("");
        AppConfig.setM3uUrl("");
        // The updater stores when it last checked; without clearing it, a leftover timestamp from
        // an earlier test makes the "never checked" line fail.
        AppConfig.setUpdateLastCheckMs(0L);
        mLocalFile = new File(RuntimeEnvironment.getApplication().getCacheDir(), "picker-test.m3u");
        try (FileOutputStream f = new FileOutputStream(mLocalFile)) {
            f.write("#EXTM3U\n#EXTINF:-1,C\nhttp://s\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    @After
    public void tearDown() {
        //noinspection ResultOfMethodCallIgnored
        mLocalFile.delete();
        // The copy the settings screen produces lives in app private storage.
        File dir = new File(RuntimeEnvironment.getApplication().getFilesDir(), "playlists");
        if (dir.exists()) {
            File copy = new File(dir, "local.m3u");
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
        }
    }

    private SettingsActivity buildActivity() {
        return Robolectric.buildActivity(SettingsActivity.class).setup().get();
    }

    @Test
    public void pickButton_opensDocumentPicker() {
        SettingsActivity activity = buildActivity();
        Button pick = activity.findViewById(R.id.pick_m3u_file_btn);
        assertNotNull("pick button must exist", pick);

        pick.performClick();

        Intent launched = shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, launched.getAction());
        assertTrue(launched.getCategories().contains(Intent.CATEGORY_OPENABLE));
        assertEquals("*/*", launched.getType());
        assertTrue((launched.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void configuredFile_isShownInSettings() {
        AppConfig.setM3uFilePath(mLocalFile.getAbsolutePath());
        SettingsActivity activity = buildActivity();

        TextView info = activity.findViewById(R.id.m3u_file_path_text);
        assertTrue(info.getText().toString().contains("picker-test.m3u"));
    }

    @Test
    public void noFile_showsNoneState() {
        SettingsActivity activity = buildActivity();
        TextView info = activity.findViewById(R.id.m3u_file_path_text);
        assertEquals(activity.getString(R.string.m3u_file_none), info.getText().toString());
    }

    @Test
    public void clearButton_removesConfiguredFile() {
        AppConfig.setM3uFilePath(mLocalFile.getAbsolutePath());
        SettingsActivity activity = buildActivity();

        Button clear = activity.findViewById(R.id.clear_m3u_file_btn);
        clear.performClick();

        assertEquals("", AppConfig.getM3uFilePath());
        TextView info = activity.findViewById(R.id.m3u_file_path_text);
        assertEquals(activity.getString(R.string.m3u_file_none), info.getText().toString());
    }

    @Test
    public void activity_survivesOnCreate_withAllSections() {
        // Regression: the new section must not break the existing settings screen.
        SettingsActivity activity = buildActivity();
        assertNotNull(activity.findViewById(R.id.m3u_url_input));
        assertNotNull(activity.findViewById(R.id.epg_url_input));
        assertNotNull(activity.findViewById(R.id.web_server_toggle));
        assertNotNull(activity.findViewById(R.id.pick_m3u_file_btn));
        assertNotNull(activity.findViewById(R.id.clear_m3u_file_btn));
        assertNotNull(activity.findViewById(R.id.m3u_file_path_text));
    }

    @Test
    public void backReturnsToThePlayerInsteadOfEndingTheTask() {
        // On televisions whose task manager reaps the background player (the MiTV does), finishing
        // the settings screen used to land the viewer on the launcher, as if the app had quit.
        SettingsActivity activity = buildActivity();
        activity.onBackPressed();

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull("BACK must raise the player", started);
        assertNotNull(started.getComponent());
        assertEquals(MainActivity.class.getName(), started.getComponent().getClassName());
        assertTrue("the settings screen itself still closes", activity.isFinishing());
    }

    @Test
    public void updateSection_showsVersionAndShipsAutoCheckOn() {
        AppConfig.setUpdateAutoCheckEnabled(true);
        SettingsActivity activity = buildActivity();

        TextView version = activity.findViewById(R.id.app_version_text);
        assertTrue("the installed version must be visible for support questions",
                version.getText().toString().startsWith("LiteTV "));
        assertTrue("automatic checks are on by default",
                ((android.widget.CheckBox) activity.findViewById(R.id.update_auto_check_toggle)).isChecked());
        assertEquals("nothing has been checked in a fresh install",
                activity.getString(R.string.update_never_checked),
                ((TextView) activity.findViewById(R.id.update_last_check_text)).getText().toString());

        ((android.widget.CheckBox) activity.findViewById(R.id.update_auto_check_toggle)).setChecked(false);
        assertFalse("turning the switch off must be stored", AppConfig.isUpdateAutoCheckEnabled());
    }
}