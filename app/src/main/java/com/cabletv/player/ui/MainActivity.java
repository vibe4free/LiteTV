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

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private VideoView mVideoView;
    private ChannelRepository mChannelRepository;
    private EpgManager mEpgManager;
    private ChannelListComponent mChannelListComponent;
    private SwitchOverlayComponent mSwitchOverlay;
    private int mCurrentChannelIndex = 0;
    private long mLastChannelSwitchTime = 0;
    private boolean mChannelListVisible = false;
    private static final long CHANNEL_SWITCH_DEBOUNCE_MS = 250;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mVideoView = findViewById(R.id.video_view);
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

        mSwitchOverlay = new SwitchOverlayComponent(this);
        rootView.addView(mSwitchOverlay);

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

        // Set up audio manager for volume control
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Start web server for remote configuration
        if (AppConfig.isWebServerEnabled()) {
            ConfigWebServer.startServer(this);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        KeyMapping.Action action = KeyMapping.resolve(event.getKeyCode());
        if (action == null) {
            return super.dispatchKeyEvent(event);
        }

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

        int count = mChannelRepository.getChannelCount();
        if (count > 0) {
            mCurrentChannelIndex = (mCurrentChannelIndex + 1) % count;
            playChannel(mCurrentChannelIndex);
        }
    }

    private void handleChannelDown() {
        long now = System.currentTimeMillis();
        if (now - mLastChannelSwitchTime < CHANNEL_SWITCH_DEBOUNCE_MS) {
            return;
        }
        mLastChannelSwitchTime = now;

        int count = mChannelRepository.getChannelCount();
        if (count > 0) {
            mCurrentChannelIndex = (mCurrentChannelIndex - 1 + count) % count;
            playChannel(mCurrentChannelIndex);
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
        mChannelListVisible = !mChannelListVisible;
        if (mChannelListVisible) {
            showChannelList();
        } else {
            hideChannelList();
        }
    }

    private void showChannelList() {
        if (mChannelListComponent == null) {
            FrameLayout rootView = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content).getParent();
            if (rootView == null) {
                rootView = (FrameLayout) mVideoView.getParent();
            }
            if (rootView != null) {
                mChannelListComponent = new ChannelListComponent(
                        this, mChannelRepository.getAllChannels());
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        dp2px(240), FrameLayout.LayoutParams.MATCH_PARENT);
                lp.gravity = android.view.Gravity.START;
                mChannelListComponent.setLayoutParams(lp);
                rootView.addView(mChannelListComponent);
                mChannelListComponent.setCurrentChannel(mCurrentChannelIndex);
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
        Channel channel = mChannelRepository.getChannel(index);
        if (channel != null) {
            Log.d(TAG, "Playing channel: " + channel.name + " - " + channel.url);
            // Ensure UI operations happen on main thread
            runOnUiThread(() -> {
                if (mSwitchOverlay != null) {
                    mSwitchOverlay.showChannelSwitch(channel);
                }
                mVideoView.switchUrl(channel.url, channel.headers);
                mVideoView.start();
                if (mChannelListComponent != null) {
                    mChannelListComponent.setCurrentChannel(index);
                }
            });
            mEpgManager.loadEpg(channel);
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
