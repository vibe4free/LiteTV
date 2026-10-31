package com.cabletv.player.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;
import com.cabletv.player.server.ConfigWebServer;
import com.cabletv.player.update.ApkInstaller;
import com.cabletv.player.update.AppVersion;
import com.cabletv.player.update.UpdateError;
import com.cabletv.player.update.UpdateManager;
import com.cabletv.player.update.UpdateState;
import com.cabletv.player.update.UpdateStatus;
import com.cabletv.player.util.LocalPlaylistFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.List;

/**
 * The settings as a panel that slides in over the picture.
 *
 * <p>This used to be a separate screen, which had two consequences the viewer noticed: the
 * picture stopped while the settings were open, and the settings covered the whole television.
 * As a panel inside the player the picture keeps running and the left third of the screen stays
 * visible.
 *
 * <p>The panel is a plain view, so it has no activity of its own. The few things that really
 * need an activity — picking a file, asking for a permission, opening a system screen — are
 * handed to the {@link Host} it was given.
 */
public class SettingsPanel extends android.widget.FrameLayout implements UpdateManager.Listener {

    private static final String TAG = "SettingsPanel";

    /** The activity-side jobs the panel cannot do on its own. */
    public interface Host {
        /** Opens the system document picker; the result comes back through the panel. */
        void startFilePicker(Intent intent);

        /** Asks for the permissions the folder import needs. */
        void requestStoragePermission(String[] permissions, int requestCode);

        /** Brings the system screen that grants all-files access into the foreground. */
        void openSystemScreen(Intent intent);

        /**
         * Told when a setting that changes the playlist or the guide was saved.
         *
         * <p>The panel lives inside the player, so the player is never resumed and would not
         * notice the change on its own.
         */
        void onConfigurationChanged();
    }

    private static final int REQUEST_PICK_M3U_FILE = 1001;
    private static final int REQUEST_STORAGE_PERMISSION = 1002;
    /** The part of the screen the panel covers; the rest keeps showing the picture. */
    private static final float PANEL_WIDTH_SHARE = 2f / 3f;
    private static final long SLIDE_IN_MS = 220L;
    private static final long SLIDE_OUT_MS = 180L;
    /** Same ceiling as ChannelRepository, so a playlist that loads here also loads there. */
    private static final long MAX_LOCAL_M3U_BYTES = 8 * 1024 * 1024;

    private final Host mHost;

    private EditText mM3uUrlInput;
    private EditText mEpgUrlInput;
    private ToggleSwitchView mWebServerToggle;
    private TextView mWebServerAddress;
    private ToggleSwitchView mEpgDisplayToggle;
    private RadioGroup mSidebarOpacityGroup;
    private RadioGroup mEpgPreferenceGroup;
    private RadioGroup mVideoRenderModeGroup;
    private RadioGroup mVideoDecodeModeGroup;
    private ToggleSwitchView mVideoTunnelingToggle;
    private TextView mEpgM3uEmbeddedText;
    private TextView mM3uFilePathText;
    private TextView mM3uDropDirText;
    private TextView mAppVersionText;
    private ToggleSwitchView mUpdateAutoCheckToggle;
    private ToggleSwitchView mMergeSameNameToggle;
    private TextView mUpdateStatusText;
    private TextView mUpdateChangelogText;
    private TextView mUpdateLastCheckText;
    private Button mUpdateActionButton;

    /** Error string id of the last failed file copy (0 when none). */
    private int mLastFilePickError = 0;

    public SettingsPanel(Context context, Host host) {
        super(context);
        mHost = host;
        LayoutInflater.from(context).inflate(R.layout.settings_panel, this, true);
        // A view is visible by default; the panel has to stay out of the way until it is asked
        // for, or it would cover the picture from the very first frame.
        setVisibility(View.GONE);
        bindViews();
        loadValues();
        attachListeners();
    }

    private void bindViews() {
        mM3uUrlInput = findViewById(R.id.m3u_url_input);
        mEpgUrlInput = findViewById(R.id.epg_url_input);
        mWebServerToggle = findViewById(R.id.web_server_toggle);
        mWebServerAddress = findViewById(R.id.web_server_address);
        mEpgDisplayToggle = findViewById(R.id.epg_display_toggle);
        mSidebarOpacityGroup = findViewById(R.id.sidebar_opacity_group);
        mEpgPreferenceGroup = findViewById(R.id.epg_preference_group);
        mVideoRenderModeGroup = findViewById(R.id.video_render_mode_group);
        mVideoDecodeModeGroup = findViewById(R.id.video_decode_mode_group);
        mVideoTunnelingToggle = findViewById(R.id.video_tunneling_toggle);
        mEpgM3uEmbeddedText = findViewById(R.id.epg_m3u_embedded);
        mM3uFilePathText = findViewById(R.id.m3u_file_path_text);
        mM3uDropDirText = findViewById(R.id.m3u_drop_dir_text);
        mAppVersionText = findViewById(R.id.app_version_text);
        mUpdateAutoCheckToggle = findViewById(R.id.update_auto_check_toggle);
        mMergeSameNameToggle = findViewById(R.id.merge_same_name_toggle);
        mUpdateStatusText = findViewById(R.id.update_status_text);
        mUpdateChangelogText = findViewById(R.id.update_changelog_text);
        mUpdateLastCheckText = findViewById(R.id.update_last_check_text);
        mUpdateActionButton = findViewById(R.id.check_update_btn);
    }

    private void attachListeners() {
        findViewById(R.id.save_m3u_btn).setOnClickListener(v -> saveM3uUrl());
        findViewById(R.id.save_epg_btn).setOnClickListener(v -> saveEpgUrl());
        findViewById(R.id.pick_m3u_file_btn).setOnClickListener(v -> pickM3uFile());
        findViewById(R.id.scan_m3u_file_btn).setOnClickListener(v -> showFolderImportDialog());
        findViewById(R.id.clear_m3u_file_btn).setOnClickListener(v -> clearM3uFile());
        mWebServerToggle.setOnCheckedChangeListener((btn, isChecked) -> handleWebServerToggle(isChecked));
        mUpdateActionButton.setOnClickListener(v -> handleUpdateAction());
        mMergeSameNameToggle.setOnCheckedChangeListener((btn, isChecked) -> {
            // Stored only; the player reloads the playlist when it notices the change, the same
            // way an edited M3U URL does (see MainActivity's config signature check).
            AppConfig.setMergeSameNameChannelsEnabled(isChecked);
            Toast.makeText(getContext(), R.string.merge_same_name_saved, Toast.LENGTH_SHORT).show();
            mHost.onConfigurationChanged();
        });
        mUpdateAutoCheckToggle.setOnCheckedChangeListener((btn, isChecked) -> {
            AppConfig.setUpdateAutoCheckEnabled(isChecked);
            Toast.makeText(getContext(), isChecked ? R.string.update_auto_check_on
                            : R.string.update_auto_check_off, Toast.LENGTH_SHORT).show();
        });
        mEpgDisplayToggle.setOnCheckedChangeListener((btn, isChecked) -> {
            AppConfig.setEpgDisplayEnabled(isChecked);
            Toast.makeText(getContext(), isChecked ? R.string.epg_display_enabled
                    : R.string.epg_display_disabled, Toast.LENGTH_SHORT).show();
        });
        mSidebarOpacityGroup.setOnCheckedChangeListener((group, checkedId) -> {
            AppConfig.setSidebarOpacity(opacityOf(checkedId));
            Toast.makeText(getContext(), R.string.sidebar_opacity_saved, Toast.LENGTH_SHORT).show();
        });
        mEpgPreferenceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            AppConfig.setEpgPreference(epgPreferenceOf(checkedId));
            Toast.makeText(getContext(), R.string.epg_preference_saved, Toast.LENGTH_SHORT).show();
            mHost.onConfigurationChanged();
        });
        mVideoRenderModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            AppConfig.setVideoRenderMode(checkedId == R.id.video_render_texture
                    ? AppConfig.RENDER_TEXTURE : AppConfig.RENDER_SURFACE);
            Toast.makeText(getContext(), R.string.playback_settings_hint, Toast.LENGTH_LONG).show();
        });
        mVideoDecodeModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            AppConfig.setVideoDecodeMode(decodeModeOf(checkedId));
            Toast.makeText(getContext(), R.string.playback_settings_hint, Toast.LENGTH_LONG).show();
        });
        mVideoTunnelingToggle.setOnCheckedChangeListener((button, isChecked) -> {
            AppConfig.setTunnelingEnabled(isChecked);
            Toast.makeText(getContext(), R.string.playback_settings_hint, Toast.LENGTH_LONG).show();
        });
    }

    /** Reads the stored values into the panel; called again whenever the panel is shown. */
    public void loadValues() {
        mM3uUrlInput.setText(AppConfig.getM3uUrl());
        mEpgUrlInput.setText(AppConfig.getEpgUrl());
        mWebServerToggle.setChecked(AppConfig.isWebServerEnabled());
        mEpgDisplayToggle.setChecked(AppConfig.isEpgDisplayEnabled());
        // Checked before the listeners are attached, so restoring the saved value is not
        // mistaken for the viewer choosing it again.
        mSidebarOpacityGroup.check(radioIdOf(AppConfig.getSidebarOpacity()));
        mEpgPreferenceGroup.check(epgPreferenceRadioId(AppConfig.getEpgPreference()));
        mVideoRenderModeGroup.check(AppConfig.getVideoRenderMode() == AppConfig.RENDER_TEXTURE
                ? R.id.video_render_texture : R.id.video_render_surface);
        mVideoDecodeModeGroup.check(decodeModeRadioId(AppConfig.getVideoDecodeMode()));
        mVideoTunnelingToggle.setChecked(AppConfig.isTunnelingEnabled());
        mAppVersionText.setText(getContext().getString(R.string.app_version_format,
                AppVersion.name(getContext()), AppVersion.code(getContext())));
        mUpdateAutoCheckToggle.setChecked(AppConfig.isUpdateAutoCheckEnabled());
        mMergeSameNameToggle.setChecked(AppConfig.isMergeSameNameChannelsEnabled());
        renderUpdateStatus();
        updateM3uEpgInfo();
        updateM3uFileInfo();
        updateM3uDropDirText();
        updateWebServerAddress();
    }

    /** Follows the updater while the panel is on screen. */
    public void attachToUpdater() {
        UpdateManager.get().addListener(this);
        renderUpdateStatus();
    }

    public void detachFromUpdater() {
        UpdateManager.get().removeListener(this);
    }

    private static int decodeModeOf(int checkedId) {
        if (checkedId == R.id.video_decode_hardware) {
            return xyz.doikki.videoplayer.player.VideoViewConfig.DECODE_HARDWARE;
        }
        if (checkedId == R.id.video_decode_software) {
            return xyz.doikki.videoplayer.player.VideoViewConfig.DECODE_SOFTWARE;
        }
        return xyz.doikki.videoplayer.player.VideoViewConfig.DECODE_AUTO;
    }

    private static int decodeModeRadioId(int decodeMode) {
        switch (decodeMode) {
            case xyz.doikki.videoplayer.player.VideoViewConfig.DECODE_HARDWARE:
                return R.id.video_decode_hardware;
            case xyz.doikki.videoplayer.player.VideoViewConfig.DECODE_SOFTWARE:
                return R.id.video_decode_software;
            default:
                return R.id.video_decode_auto;
        }
    }

    private static String epgPreferenceOf(int checkedId) {
        if (checkedId == R.id.epg_preference_m3u) {
            return AppConfig.EPG_PREFERENCE_M3U;
        }
        if (checkedId == R.id.epg_preference_user) {
            return AppConfig.EPG_PREFERENCE_USER;
        }
        return AppConfig.EPG_PREFERENCE_AUTO;
    }

    private static int epgPreferenceRadioId(String preference) {
        if (AppConfig.EPG_PREFERENCE_M3U.equals(preference)) {
            return R.id.epg_preference_m3u;
        }
        if (AppConfig.EPG_PREFERENCE_USER.equals(preference)) {
            return R.id.epg_preference_user;
        }
        return R.id.epg_preference_auto;
    }

    private static int opacityOf(int checkedId) {
        if (checkedId == R.id.sidebar_opacity_opaque) {
            return AppConfig.SIDEBAR_OPAQUE;
        }
        if (checkedId == R.id.sidebar_opacity_semi) {
            return AppConfig.SIDEBAR_SEMI_OPAQUE;
        }
        return AppConfig.SIDEBAR_TRANSLUCENT;
    }

    private static int radioIdOf(int opacity) {
        switch (opacity) {
            case AppConfig.SIDEBAR_OPAQUE:
                return R.id.sidebar_opacity_opaque;
            case AppConfig.SIDEBAR_SEMI_OPAQUE:
                return R.id.sidebar_opacity_semi;
            default:
                return R.id.sidebar_opacity_translucent;
        }
    }

    /**
     * The panel takes two thirds of the screen, so the picture stays visible on the left.
     *
     * <p>The width is a share of the screen rather than a fixed number of pixels: a television
     * in the shop has a different size than the one in the living room, and the panel has to
     * leave the same part of the picture uncovered on both.
     *
     * @return layout params that anchor the panel to the right edge of the player.
     */
    public android.widget.FrameLayout.LayoutParams createPanelLayoutParams() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int panelWidth = Math.round(screenWidth * PANEL_WIDTH_SHARE);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                panelWidth, android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        lp.gravity = android.view.Gravity.END;
        return lp;
    }

    /** Slides the panel in from the right and gives it the focus. */
    public void showPanel() {
        if (getVisibility() == View.VISIBLE) {
            return;
        }
        setVisibility(View.VISIBLE);
        loadValues();
        attachToUpdater();
        fadeIn();
        // The remote has to land somewhere: the first control in the panel.
        post(() -> {
            View first = findFirstFocusable(this);
            if (first != null) {
                first.requestFocus();
            }
        });
    }

    /** Slides the panel away; the picture underneath never stopped. */
    public void hidePanel() {
        if (getVisibility() != View.VISIBLE) {
            return;
        }
        detachFromUpdater();
        fadeOut();
    }

    /**
     * Brings the panel in by fading it up.
     *
     * <p>Not by sliding it in from the right: moving the view sideways makes this television's
     * compositor drop the whole application layer from the picture (the video plane sits on top
     * and the app window is then cropped away), so the panel would never reach the screen. A
     * fade keeps the layer where it is and only changes how opaque it is.
     */
    private void fadeIn() {
        setAlpha(0f);
        final long start = android.os.SystemClock.uptimeMillis();
        final Runnable step = new Runnable() {
            @Override
            public void run() {
                float progress = Math.min(1f,
                        (android.os.SystemClock.uptimeMillis() - start) / (float) SLIDE_IN_MS);
                setAlpha(progress);
                invalidate();
                if (progress < 1f) {
                    postDelayed(this, 16L);
                }
            }
        };
        post(step);
    }

    /** Fades the panel away; the picture underneath never stopped. */
    private void fadeOut() {
        final long start = android.os.SystemClock.uptimeMillis();
        final Runnable step = new Runnable() {
            @Override
            public void run() {
                float progress = Math.min(1f,
                        (android.os.SystemClock.uptimeMillis() - start) / (float) SLIDE_OUT_MS);
                setAlpha(1f - progress);
                invalidate();
                if (progress < 1f) {
                    postDelayed(this, 16L);
                    return;
                }
                setAlpha(1f);
                setVisibility(GONE);
            }
        };
        post(step);
    }


    /** Whether the panel is on screen; the menu key and the back key ask this first. */
    public boolean isPanelVisible() {
        return getVisibility() == View.VISIBLE;
    }

    /**
     * The first control worth putting the focus on.
     *
     * <p>A text field is skipped on purpose: landing on one opens the on-screen keyboard, which
     * covers half of a television and is never what the viewer wanted when they pressed MENU.
     */
    private static View findFirstFocusable(View view) {
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFirstFocusable(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (view instanceof android.widget.EditText) {
            return null;
        }
        if (view.isFocusable() && view.getVisibility() == View.VISIBLE) {
            return view;
        }
        return null;
    }

    /**
     * Opens the system file picker (SAF), so a USB drive or the internal storage can be
     * browsed with the remote. No storage permission is needed for ACTION_OPEN_DOCUMENT.
     */
    private void pickM3uFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // m3u files often carry no useful MIME type
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            mHost.startFilePicker(intent);
        } catch (Exception e) {
            // Android TV builds frequently ship without a documents provider, so instead of
            // dead-ending, fall back to the folder the user can copy a file into (issue #7).
            Log.w(TAG, "No document picker available, offering folder import", e);
            Toast.makeText(getContext(), R.string.m3u_file_pick_error, Toast.LENGTH_LONG).show();
            showFolderImportDialog();
        }
    }

    /**
     * Imports a playlist from a folder rather than the document picker. Scans the advertised drop
     * folder plus Download and any mounted volume, then copies the chosen file into app storage.
     */
    private void showFolderImportDialog() {
        File dropDir = LocalPlaylistFolder.dropDir(getContext());
        updateM3uDropDirText();
        List<File> files = LocalPlaylistFolder.scan(
                LocalPlaylistFolder.candidateDirs(getContext()), LocalPlaylistFolder.MAX_FILE_BYTES);
        if (files.isEmpty()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                    .setTitle(R.string.m3u_scan_title)
                    .setMessage(getContext().getString(R.string.m3u_scan_empty,
                            dropDir.getAbsolutePath()))
                    .setPositiveButton(android.R.string.ok, null);
            if (!LocalPlaylistFolder.canUseSharedStorage(getContext())) {
                builder.setNeutralButton(R.string.m3u_scan_grant,
                        (dialog, which) -> requestStorageAccess());
            }
            builder.show();
            return;
        }
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            labels[i] = file.getName() + "\n" + file.getParent();
        }
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.m3u_scan_title)
                .setItems(labels, (dialog, which) -> importScannedFile(files.get(which)))
                .setNegativeButton(R.string.exit_cancel, null)
                .show();
    }

    /** Copies a scanned playlist into app storage and makes it the active local file. */
    private void importScannedFile(File source) {
        String path = importFileIntoAppStorage(source);
        if (path == null) {
            int errorRes = mLastFilePickError;
            mLastFilePickError = 0;
            Toast.makeText(getContext(), errorRes != 0 ? errorRes : R.string.m3u_file_copy_error,
                    Toast.LENGTH_LONG).show();
            return;
        }
        AppConfig.setM3uFilePath(path);
        Toast.makeText(getContext(), getContext().getString(R.string.m3u_scan_imported,
                source.getName()), Toast.LENGTH_SHORT).show();
        updateM3uFileInfo();
        mHost.onConfigurationChanged();
    }

    /** Asks for shared-storage access so the public drop folder can be scanned. */
    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                mHost.openSystemScreen(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getContext().getPackageName())));
                return;
            } catch (Exception e) {
                Log.d(TAG, "App-specific all-files settings unavailable: " + e.getMessage());
            }
            try {
                mHost.openSystemScreen(new Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return;
            } catch (Exception e) {
                Log.d(TAG, "All-files settings unavailable: " + e.getMessage());
            }
            Toast.makeText(getContext(), R.string.m3u_scan_grant_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mHost.requestStoragePermission(
                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
        }
    }

    /** The result of the system file picker. */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_M3U_FILE || resultCode != android.app.Activity.RESULT_OK
                || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            getContext().getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // Best effort: the file is copied into app storage below, so the URI is not needed
            // again. Some providers refuse persistable grants and throw here.
        }
        String path = copyToAppStorage(uri);
        if (path == null) {
            int errorRes = mLastFilePickError;
            mLastFilePickError = 0;
            Toast.makeText(getContext(), errorRes != 0 ? errorRes : R.string.m3u_file_copy_error,
                    Toast.LENGTH_LONG).show();
            return;
        }
        AppConfig.setM3uFilePath(path);
        Toast.makeText(getContext(), R.string.m3u_file_saved, Toast.LENGTH_SHORT).show();
        updateM3uFileInfo();
        mHost.onConfigurationChanged();
    }

    /** The result of a storage permission request. */
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            updateM3uDropDirText();
            showFolderImportDialog();
        }
    }

    /**
     * Copies the picked document into app-private storage so the playlist survives the drive
     * being unplugged and stays readable by path (ChannelRepository reads by path).
     *
     * @return the absolute path of the copy, or null when the file could not be read.
     */
    private String copyToAppStorage(Uri uri) {
        if (uri == null) {
            return null;
        }
        try {
            InputStream in = getContext().getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            try {
                return copyStreamToPlaylist(in);
            } finally {
                closeQuietly(in);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copying picked M3U file", e);
            return null;
        }
    }

    /** Same as {@link #copyToAppStorage(Uri)} for a file found by the folder scan. */
    private String importFileIntoAppStorage(File source) {
        if (source == null) {
            return null;
        }
        try {
            InputStream in = new FileInputStream(source);
            try {
                return copyStreamToPlaylist(in);
            } finally {
                closeQuietly(in);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error importing scanned M3U file", e);
            return null;
        }
    }

    /**
     * Streams a playlist into the single app-private slot.
     *
     * @return the stored path, or null; {@link #mLastFilePickError} carries the reason.
     */
    private String copyStreamToPlaylist(InputStream in) throws IOException {
        mLastFilePickError = 0;
        File target = LocalPlaylistFolder.playlistSlot(getContext());
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return null;
        }
        OutputStream out = new FileOutputStream(target);
        try {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_LOCAL_M3U_BYTES) {
                    mLastFilePickError = R.string.m3u_file_too_large;
                    return null;
                }
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            closeQuietly(out);
        }
        return target.getAbsolutePath();
    }

    private void clearM3uFile() {
        String path = AppConfig.getM3uFilePath();
        if (!TextUtils.isEmpty(path)) {
            //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
        }
        AppConfig.setM3uFilePath("");
        Toast.makeText(getContext(), R.string.m3u_file_cleared, Toast.LENGTH_SHORT).show();
        updateM3uFileInfo();
        mHost.onConfigurationChanged();
    }

    /** Shows which local playlist is active, if any. */
    private void updateM3uFileInfo() {
        String path = AppConfig.getM3uFilePath();
        if (TextUtils.isEmpty(path)) {
            mM3uFilePathText.setText(R.string.m3u_file_none);
        } else {
            String name = new File(path).getName();
            if (new File(path).exists()) {
                mM3uFilePathText.setText(getContext().getString(R.string.m3u_file_selected, name));
            } else {
                // Stale reference (e.g. app data moved): tell the user to pick it again.
                mM3uFilePathText.setText(getContext().getString(R.string.m3u_file_missing, name));
            }
        }
    }

    /**
     * Names the folder the user can drop a playlist into. This is the import path on devices
     * without a document picker, so it is always shown next to the file buttons.
     */
    private void updateM3uDropDirText() {
        mM3uDropDirText.setText(getContext().getString(R.string.m3u_drop_dir,
                LocalPlaylistFolder.dropDir(getContext()).getAbsolutePath()));
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    /** Shows which EPG feed the current playlist embedded, if any. */
    private void updateM3uEpgInfo() {
        List<String> urls = AppConfig.getM3uEpgUrls();
        if (urls.isEmpty()) {
            mEpgM3uEmbeddedText.setText(R.string.epg_m3u_embedded_none);
        } else {
            mEpgM3uEmbeddedText.setText(getContext().getString(R.string.epg_m3u_embedded_found,
                    android.text.TextUtils.join(", ", urls)));
        }
    }

    private void saveM3uUrl() {
        String url = mM3uUrlInput.getText().toString().trim();
        AppConfig.setM3uUrl(url);
        Toast.makeText(getContext(), R.string.m3u_url_saved, Toast.LENGTH_SHORT).show();
        mHost.onConfigurationChanged();
    }

    private void saveEpgUrl() {
        String url = mEpgUrlInput.getText().toString().trim();
        AppConfig.setEpgUrl(url);
        Toast.makeText(getContext(), R.string.epg_url_saved, Toast.LENGTH_SHORT).show();
        mHost.onConfigurationChanged();
    }

    private void handleWebServerToggle(boolean enabled) {
        AppConfig.setWebServerEnabled(enabled);
        if (enabled) {
            ConfigWebServer.startServer(getContext());
            Toast.makeText(getContext(), R.string.web_server_started, Toast.LENGTH_SHORT).show();
        } else {
            ConfigWebServer.stopServer();
            Toast.makeText(getContext(), R.string.web_server_stopped, Toast.LENGTH_SHORT).show();
        }
        updateWebServerAddress();
    }

    private void updateWebServerAddress() {
        if (AppConfig.isWebServerEnabled()) {
            String ip = getLocalIpAddress();
            int port = AppConfig.getWebServerPort();
            String address = "http://" + ip + ":" + port;
            mWebServerAddress.setText(getContext().getString(R.string.server_address_format, address));
        } else {
            mWebServerAddress.setText("");
        }
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getContext().getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                int ipInt = wifiManager.getConnectionInfo().getIpAddress();
                return String.format("%d.%d.%d.%d",
                        (ipInt & 0xff),
                        (ipInt >> 8 & 0xff),
                        (ipInt >> 16 & 0xff),
                        (ipInt >> 24 & 0xff));
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot read the Wi-Fi address", e);
        }
        return "127.0.0.1";
    }

    @Override
    public void onUpdateStatusChanged(UpdateStatus status) {
        renderUpdateStatus();
    }

    /** Paints the current update status; the only place that decides how it reads on screen. */
    private void renderUpdateStatus() {
        UpdateStatus status = UpdateManager.get().getStatus();
        mUpdateStatusText.setText(statusText(status));
        if (status.state == UpdateState.UPDATE_AVAILABLE && !status.changelog.isEmpty()) {
            StringBuilder text = new StringBuilder(
                    getContext().getString(R.string.update_changelog_title));
            for (String line : status.changelog) {
                text.append('\n').append("• ").append(line);
            }
            mUpdateChangelogText.setText(text.toString());
            mUpdateChangelogText.setVisibility(View.VISIBLE);
        } else {
            mUpdateChangelogText.setVisibility(View.GONE);
        }
        renderUpdateAction(status);
        renderLastCheck();
    }

    /**
     * One button whose meaning follows the state, which is what a remote needs: the focused
     * control always says what pressing OK will do.
     */
    private void renderUpdateAction(UpdateStatus status) {
        switch (status.state) {
            case UPDATE_AVAILABLE:
                mUpdateActionButton.setEnabled(true);
                mUpdateActionButton.setText(R.string.update_download);
                break;
            case DOWNLOADING:
                mUpdateActionButton.setEnabled(true);
                mUpdateActionButton.setText(R.string.update_cancel_download);
                break;
            case READY:
                mUpdateActionButton.setEnabled(true);
                mUpdateActionButton.setText(R.string.update_install);
                break;
            case INSTALLING:
                mUpdateActionButton.setEnabled(false);
                mUpdateActionButton.setText(R.string.update_installing_button);
                break;
            case NEEDS_PERMISSION:
                // The switch may have been granted in the meantime (the TV settings screen or
                // ADB), in which case the same button continues the install instead of asking
                // again.
                mUpdateActionButton.setEnabled(true);
                mUpdateActionButton.setText(ApkInstaller.canRequestInstall(getContext())
                        ? R.string.update_install : R.string.update_permission_open);
                break;
            default:
                mUpdateActionButton.setEnabled(true);
                mUpdateActionButton.setText(R.string.update_check_now);
                break;
        }
    }

    private void handleUpdateAction() {
        UpdateStatus status = UpdateManager.get().getStatus();
        switch (status.state) {
            case UPDATE_AVAILABLE:
                UpdateManager.get().downloadUpdate(getContext());
                break;
            case DOWNLOADING:
                UpdateManager.get().cancelDownload();
                break;
            case READY:
                UpdateManager.get().installUpdate(getContext());
                break;
            case NEEDS_PERMISSION:
                if (ApkInstaller.canRequestInstall(getContext())) {
                    // Permission granted since the last attempt: pick up where it stopped,
                    // without downloading the verified file again.
                    UpdateManager.get().installUpdate(getContext());
                } else {
                    showInstallPermissionHelp();
                }
                break;
            default:
                UpdateManager.get().checkNow(getContext());
                break;
        }
    }

    /**
     * Android 8 and newer require the user to allow installs from this app, and on some
     * televisions the screen for that is missing or greyed out. Rather than dead-ending, offer
     * the system screen when it exists and the documented ways out when it does not.
     */
    private void showInstallPermissionHelp() {
        Intent permission = ApkInstaller.permissionIntent(getContext());
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_permission_open,
                        (dialog, which) -> openSystemSettings(permission))
                .setNeutralButton(R.string.update_permission_adb_button,
                        (dialog, which) -> Toast.makeText(getContext(), R.string.update_permission_adb,
                                Toast.LENGTH_LONG).show())
                .setNegativeButton(R.string.exit_cancel, null)
                .create()
                .show();
    }

    private void openSystemSettings(Intent preferred) {
        Intent intent = preferred != null ? preferred : ApkInstaller.appDetailsIntent(getContext());
        try {
            mHost.openSystemScreen(intent);
        } catch (Exception e) {
            // The specific screen may not exist on this firmware; the app's own page usually does.
            Log.w(TAG, "Cannot open " + intent, e);
            try {
                mHost.openSystemScreen(ApkInstaller.appDetailsIntent(getContext()));
            } catch (Exception nested) {
                Toast.makeText(getContext(), R.string.update_permission_adb, Toast.LENGTH_LONG).show();
            }
        }
    }

    private CharSequence statusText(UpdateStatus status) {
        switch (status.state) {
            case CHECKING:
                return getContext().getString(R.string.update_checking);
            case UP_TO_DATE:
                return getContext().getString(R.string.update_up_to_date);
            case UPDATE_AVAILABLE:
                // A cancelled or failed download leaves the update on offer, with the reason shown.
                return status.error != UpdateError.NONE
                        ? updateErrorText(status.error)
                        : getContext().getString(R.string.update_available, status.latestVersionName);
            case DOWNLOADING:
                return getContext().getString(R.string.update_downloading,
                        Math.round(status.progressFraction() * 100f));
            case READY:
                // A failed install leaves the verified file in place, so show why it stopped.
                return status.error != UpdateError.NONE
                        ? updateErrorText(status.error)
                        : getContext().getString(R.string.update_ready);
            case INSTALLING:
                return getContext().getString(R.string.update_installing);
            case NEEDS_PERMISSION:
                return getContext().getString(R.string.update_permission_needed);
            case ERROR:
                return updateErrorText(status.error);
            default:
                return "";
        }
    }

    private String updateErrorText(UpdateError error) {
        switch (error) {
            case NETWORK:
                return getContext().getString(R.string.update_error_network);
            case HTTP_NOT_FOUND:
                // Normal until a release publishes the manifest asset, so it must not read as a
                // fault.
                return getContext().getString(R.string.update_error_no_manifest);
            case HTTP:
                return getContext().getString(R.string.update_error_http);
            case PARSE:
                return getContext().getString(R.string.update_error_parse);
            case NO_BUILD_FOR_DEVICE:
                return getContext().getString(R.string.update_error_no_build);
            case CHECKSUM:
                return getContext().getString(R.string.update_error_checksum);
            case SIGNATURE:
                return getContext().getString(R.string.update_error_signature);
            case CANCELLED:
                return getContext().getString(R.string.update_error_cancelled);
            case STORAGE:
                return getContext().getString(R.string.update_error_storage);
            case INSTALL_BLOCKED:
                return getContext().getString(R.string.update_error_install_blocked);
            case INSTALL_FAILED:
                return getContext().getString(R.string.update_error_install_failed);
            case INSTALL_RESTRICTED:
                return getContext().getString(R.string.update_error_install_restricted);
            default:
                return getContext().getString(R.string.update_error_unknown);
        }
    }

    private void renderLastCheck() {
        mUpdateLastCheckText.setText(formatLastCheck(getContext(), AppConfig.getUpdateLastCheckMs()));
    }

    /**
     * The line that says when the last check happened.
     *
     * <p>Kept apart from the view so the wording can be checked directly: a timestamp of zero
     * means the app has never looked for an update.
     */
    public static String formatLastCheck(Context context, long lastCheckMs) {
        if (lastCheckMs <= 0L) {
            return context.getString(R.string.update_never_checked);
        }
        Date when = new Date(lastCheckMs);
        String stamp = android.text.format.DateFormat.getMediumDateFormat(context).format(when)
                + " " + android.text.format.DateFormat.getTimeFormat(context).format(when);
        return context.getString(R.string.update_last_check_format, stamp);
    }
}
