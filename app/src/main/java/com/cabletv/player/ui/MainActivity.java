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
                // Reset to first channel when playlist updates
                mCurrentChannelIndex = 0;
                playChannel(mCurrentChannelIndex);
            }
        });

        // Load channels from configured source or use test URL if none configured
        String m3uUrl = AppConfig.getM3uUrl();

        mChannelRepository.reload();

        // Check EPG cache and preload if needed
        Log.d(TAG, "Checking EPG cache validity...");
        if (!AppConfig.isEpgCacheValid()) {
            Log.d(TAG, "EPG cache is stale or missing, preloading from network...");
            new Thread(() -> {
                java.util.List<Channel> allChannels = mChannelRepository.getAllChannels();
                if (allChannels != null && !allChannels.isEmpty()) {
                    mEpgManager.preloadEpgForAllChannels(allChannels);
                    Log.d(TAG, "EPG preload from network completed");
                }
            }).start();
        } else {
            Log.d(TAG, "EPG cache is still valid, loading from cache...");
            // Try to load from cache file for faster startup
            mEpgManager.loadFromCache();
        }

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
            boolean swapped = AppConfig.isChannelUpDownSwapped();

            // If program list is visible, scroll it (affected by config)
            if (mChannelListComponent.isProgramListVisible()) {
                // UP: swapped means scroll down, else scroll up
                mChannelListComponent.scrollProgramList(swapped);
                Log.d(TAG, "handleChannelUp: Scrolling program list");
                return;
            }

            // Otherwise, navigate channel list (never affected by config)
            int selected = mChannelListComponent.getSelectedChannelIndex();
            int count = mChannelRepository.getChannelCount();
            if (count > 0) {
                // Always: UP means next (index+1)
                selected = (selected + 1) % count;
                Log.d(TAG, "handleChannelUp: Selecting next channel: " + selected);
                mChannelListComponent.selectChannel(selected);
            }
        } else {
            // No channel list visible: change channel (never affected by config)
            int count = mChannelRepository.getChannelCount();
            Log.d(TAG, "handleChannelUp: Channel list not visible, count=" + count);
            if (count > 0) {
                // Always: UP means next
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
            boolean swapped = AppConfig.isChannelUpDownSwapped();

            // If program list is visible, scroll it (affected by config)
            if (mChannelListComponent.isProgramListVisible()) {
                // DOWN: swapped means scroll up, else scroll down
                mChannelListComponent.scrollProgramList(!swapped);
                return;
            }

            // Otherwise, navigate channel list (never affected by config)
            int selected = mChannelListComponent.getSelectedChannelIndex();
            int count = mChannelRepository.getChannelCount();
            if (count > 0) {
                // Always: DOWN means previous (index-1)
                selected = (selected - 1 + count) % count;
                mChannelListComponent.selectChannel(selected);
            }
        } else {
            // No channel list visible: change channel (never affected by config)
            int count = mChannelRepository.getChannelCount();
            if (count > 0) {
                // Always: DOWN means previous
                mCurrentChannelIndex = (mCurrentChannelIndex - 1 + count) % count;
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
            mChannelListComponent.setCurrentChannel(mCurrentChannelIndex);
            mChannelListComponent.selectChannel(mCurrentChannelIndex);
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
        if (mChannelListComponent != null) {
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
