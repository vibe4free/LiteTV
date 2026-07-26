package com.cabletv.player.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;
import com.cabletv.player.config.ChannelRepository;
import com.cabletv.player.config.KeyMapping;
import com.cabletv.player.model.Channel;
import com.cabletv.player.epg.EpgManager;
import com.cabletv.player.server.ConfigWebServer;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.exo.ExoMediaPlayerFactory;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private VideoView mVideoView;
    private ChannelRepository mChannelRepository;
    private EpgManager mEpgManager;
    private ChannelListComponent mChannelListComponent;
    private PlaybackInfoComponent mPlaybackInfoComponent;
    private int mCurrentChannelIndex = 0;
    private long mLastChannelSwitchTime = 0;
    private boolean mChannelListVisible = false;
    private boolean mIsInitialLoad = true;
    private static final long CHANNEL_SWITCH_DEBOUNCE_MS = 250;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mVideoView = findViewById(R.id.video_view);
        // Configure VideoView to use ExoPlayer instead of default Android MediaPlayer
        mVideoView.setPlayerFactory(new ExoMediaPlayerFactory());
        mChannelRepository = new ChannelRepository(this);
        mEpgManager = new EpgManager(this);

        // Create overlay components
        FrameLayout rootView = (FrameLayout) mVideoView.getParent();
        if (rootView == null) {
            rootView = findViewById(R.id.video_container);
            if (rootView == null) {
                // Fallback: create a root container
                rootView = new FrameLayout(this);
                setContentView(rootView);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);
                mVideoView.setLayoutParams(lp);
                rootView.addView(mVideoView);
            }
        }

        // Add bottom playback info bar
        mPlaybackInfoComponent = new PlaybackInfoComponent(this);
        mPlaybackInfoComponent.setEpgManager(mEpgManager);
        FrameLayout.LayoutParams infoBarlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp2px(100));
        infoBarlp.gravity = android.view.Gravity.BOTTOM;
        mPlaybackInfoComponent.setLayoutParams(infoBarlp);
        rootView.addView(mPlaybackInfoComponent);

        // Setup channel change listener
        mChannelRepository.addListener(new ChannelRepository.OnChannelsChangedListener() {
            @Override
            public void onChannelsChanged(java.util.List<com.cabletv.player.model.ChannelGroup> channels) {
                Log.i(TAG, "Channels updated: " + channels.size());
                if (mChannelListComponent != null) {
                    mChannelListComponent.updateChannels(
                            mChannelRepository.getAllChannels(), mCurrentChannelIndex);
                }
                // On initial load, try to resume last played channel; on subsequent reloads, reset to first
                if (mIsInitialLoad) {
                    mIsInitialLoad = false;
                    mCurrentChannelIndex = mChannelRepository.getCurrentChannelIndex(AppConfig.getLastChannelUrl());
                    Log.d(TAG, "Initial load: restoring last channel with url=" + AppConfig.getLastChannelUrl() + ", index=" + mCurrentChannelIndex);
                } else {
                    mCurrentChannelIndex = 0;
                    Log.d(TAG, "Subsequent reload: resetting to first channel");
                }
                playChannel(mCurrentChannelIndex);

                // Trigger EPG bulk preload now that channels are truly available (no race with reload())
                if (!AppConfig.isEpgCacheValid()) {
                    java.util.List<Channel> allChannels = mChannelRepository.getAllChannels();
                    if (allChannels != null && !allChannels.isEmpty()) {
                        Log.d(TAG, "Triggering EPG bulk preload for " + allChannels.size() + " channels");
                        mEpgManager.preloadEpgForAllChannels(allChannels);
                    }
                }
            }
        });

        // Load channels from the configured source; guide the user if there is none yet.
        String m3uUrl = AppConfig.getM3uUrl();
        String m3uFilePath = AppConfig.getM3uFilePath();
        if ((m3uUrl == null || m3uUrl.isEmpty()) && (m3uFilePath == null || m3uFilePath.isEmpty())) {
            Log.w(TAG, "No M3U source configured");
            android.widget.Toast.makeText(this,
                    getString(R.string.no_playlist_configured, AppConfig.getWebServerPort()),
                    android.widget.Toast.LENGTH_LONG).show();
        }

        // Load EPG from cache first (if exists) for faster startup (stale-while-revalidate)
        Log.d(TAG, "Loading EPG from cache if available...");
        mEpgManager.loadFromCache();

        mChannelRepository.reload();

        // Set up audio manager for volume control
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Start web server for remote configuration
        if (AppConfig.isWebServerEnabled()) {
            ConfigWebServer.startServer(this);
            // Set up callback for configuration changes
            ConfigWebServer.setConfigChangeListener(new ConfigWebServer.OnConfigChangeListener() {
                @Override
                public void onM3uUrlChanged(String newUrl) {
                    Log.d(TAG, "M3U URL changed, reloading channels: " + newUrl);
                    mChannelRepository.reload();
                }

                @Override
                public void onEpgUrlChanged(String newUrl) {
                    Log.d(TAG, "EPG URL changed: " + newUrl);
                    // Invalidate EPG cache to force refresh on next app start
                    AppConfig.setEpgLastUpdateTime(0);
                }
            });
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        Log.d(TAG, "Key pressed: " + event.getKeyCode() + " (" + KeyEvent.keyCodeToString(event.getKeyCode()) + ")");
        KeyMapping.Action action = KeyMapping.resolve(event.getKeyCode());
        if (action == null) {
            Log.d(TAG, "No action mapped for keycode: " + event.getKeyCode());
            return super.dispatchKeyEvent(event);
        }
        Log.d(TAG, "Action: " + action);

        switch (action) {
            case CHANNEL_UP:
                handleChannelUp();
                return true;
            case CHANNEL_DOWN:
                handleChannelDown();
                return true;
            case VOLUME_UP:
                handleVolumeUp();
                return true;
            case VOLUME_DOWN:
                handleVolumeDown();
                return true;
            case LEFT:
                handleFocusLeft();
                return true;
            case RIGHT:
                handleFocusRight();
                return true;
            case OK:
                handleOk();
                return true;
            case MENU:
                handleMenu();
                return true;
            case BACK:
                handleBack();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void handleChannelUp() {
        long now = System.currentTimeMillis();
        if (now - mLastChannelSwitchTime < CHANNEL_SWITCH_DEBOUNCE_MS) {
            return;
        }
        mLastChannelSwitchTime = now;

        Log.d(TAG, "handleChannelUp: mChannelListVisible=" + mChannelListVisible);

        if (mChannelListVisible && mChannelListComponent != null) {
            // If focus is on program list, navigate it (UP always moves up)
            if (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.PROGRAMS) {
                Log.d(TAG, "handleChannelUp: Focus on programs, moving selection up");
                mChannelListComponent.moveProgramSelection(false); // false = move up
                return;
            }

            // Otherwise, navigate channel list (UP = highlight move up = previous channel)
            int selected = mChannelListComponent.getSelectedChannelIndex();
            int count = mChannelRepository.getChannelCount();
            if (count > 0) {
                selected = (selected - 1 + count) % count; // UP = previous channel
                Log.d(TAG, "handleChannelUp: Selecting channel: " + selected);
                mChannelListComponent.selectChannel(selected);
            }
        } else {
            // No channel list visible: change channel (UP = next channel)
            int count = mChannelRepository.getChannelCount();
            Log.d(TAG, "handleChannelUp: Channel list not visible, count=" + count);
            if (count > 0) {
                mCurrentChannelIndex = (mCurrentChannelIndex + 1) % count;
                Log.d(TAG, "handleChannelUp: Calling playChannel with index=" + mCurrentChannelIndex);
                playChannel(mCurrentChannelIndex);
            }
        }
    }

    private void handleChannelDown() {
        long now = System.currentTimeMillis();
        if (now - mLastChannelSwitchTime < CHANNEL_SWITCH_DEBOUNCE_MS) {
            return;
        }
        mLastChannelSwitchTime = now;

        if (mChannelListVisible && mChannelListComponent != null) {
            // If focus is on program list, navigate it (DOWN always moves down)
            if (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.PROGRAMS) {
                Log.d(TAG, "handleChannelDown: Focus on programs, moving selection down");
                mChannelListComponent.moveProgramSelection(true); // true = move down
                return;
            }

            // Otherwise, navigate channel list (DOWN = highlight move down = next channel)
            int selected = mChannelListComponent.getSelectedChannelIndex();
            int count = mChannelRepository.getChannelCount();
            if (count > 0) {
                selected = (selected + 1) % count; // DOWN = next channel
                Log.d(TAG, "handleChannelDown: Selecting channel: " + selected);
                mChannelListComponent.selectChannel(selected);
            }
        } else {
            // No channel list visible: change channel (DOWN = previous channel)
            int count = mChannelRepository.getChannelCount();
            if (count > 0) {
                mCurrentChannelIndex = (mCurrentChannelIndex - 1 + count) % count;
                Log.d(TAG, "handleChannelDown: Calling playChannel with index=" + mCurrentChannelIndex);
                playChannel(mCurrentChannelIndex);
            }
        }
    }

    private void handleVolumeUp() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI);
        }
    }

    private void handleVolumeDown() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
        }
    }

    private void handleFocusLeft() {
        if (mChannelListVisible && mChannelListComponent != null) {
            Log.d(TAG, "handleFocusLeft: Moving focus to channels");
            mChannelListComponent.moveFocusToChannels();
        }
    }

    private void handleFocusRight() {
        if (mChannelListVisible && mChannelListComponent != null) {
            Log.d(TAG, "handleFocusRight: Moving focus to programs");
            mChannelListComponent.moveFocusToPrograms();
        }
    }

    private void handleOk() {
        Log.d(TAG, "handleOk called, mChannelListVisible=" + mChannelListVisible);
        if (mChannelListVisible) {
            // Confirm channel selection and switch
            if (mChannelListComponent != null) {
                mChannelListComponent.confirmSelection();
            }
            hideChannelList();
            mChannelListVisible = false;
            Log.d(TAG, "handleOk - hiding list");
        } else {
            // Show channel list for selection
            mChannelListVisible = true;
            showChannelList();
            Log.d(TAG, "handleOk - showing list, mChannelListVisible now=" + mChannelListVisible);
        }
    }

    private void showChannelList() {
        if (mChannelListComponent == null) {
            mChannelListComponent = new ChannelListComponent(
                    this, mChannelRepository.getAllChannels());
            mChannelListComponent.setEpgManager(mEpgManager);

            // Set channel selection listener
            mChannelListComponent.setOnChannelSelectedListener((index, channel) -> {
                mCurrentChannelIndex = index;
                playChannel(index);
            });

            // Add to VideoView's parent or create appropriate params
            if (mVideoView.getParent() instanceof android.view.ViewGroup) {
                android.view.ViewGroup parent = (android.view.ViewGroup) mVideoView.getParent();
                if (parent instanceof FrameLayout) {
                    // Menu width: 240 (channels) + 300 (programs) = 540dp
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            dp2px(540), FrameLayout.LayoutParams.MATCH_PARENT);
                    lp.gravity = android.view.Gravity.START;
                    mChannelListComponent.setLayoutParams(lp);
                } else {
                    android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                            dp2px(540), android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
                    mChannelListComponent.setLayoutParams(lp);
                }
                parent.addView(mChannelListComponent);
            }
        }
        // Always refresh when opening menu to show current channel and program
        if (mChannelListComponent != null) {
            mChannelListComponent.setCurrentChannel(mCurrentChannelIndex);
            mChannelListComponent.selectChannel(mCurrentChannelIndex);
            mChannelListComponent.setVisibility(android.view.View.VISIBLE);
        }
    }

    private void hideChannelList() {
        if (mChannelListComponent != null) {
            mChannelListComponent.setVisibility(android.view.View.GONE);
        }
    }

    private void handleMenu() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void handleBack() {
        if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
        } else {
            onBackPressed();
        }
    }

    private void playChannel(int index) {
        Log.d(TAG, "playChannel called with index: " + index);
        Channel channel = mChannelRepository.getChannel(index);
        Log.d(TAG, "Got channel from repository: " + (channel != null ? channel.name : "null"));
        if (channel != null) {
            Log.d(TAG, "Playing channel: " + channel.name + " - " + channel.url);
            // Ensure UI operations happen on main thread
            runOnUiThread(() -> {
                mVideoView.switchUrl(channel.url, channel.headers);
                mVideoView.start();
                if (mChannelListComponent != null) {
                    mChannelListComponent.setCurrentChannel(index);
                }
                // Show bottom playback info bar
                if (mPlaybackInfoComponent != null) {
                    mPlaybackInfoComponent.showChannelInfo(channel);
                }
            });
            // Save current channel for resume on next app startup
            AppConfig.setLastChannelUrl(channel.url);
            Log.d(TAG, "About to call mEpgManager.loadEpg for channel: " + channel.name);
            mEpgManager.loadEpg(channel);
        } else {
            Log.d(TAG, "Channel is null for index: " + index);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mVideoView != null) {
            mVideoView.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mVideoView != null) {
            mVideoView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mVideoView != null) {
            mVideoView.release();
        }
        ConfigWebServer.stopServer();
    }

    private int dp2px(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }
}
