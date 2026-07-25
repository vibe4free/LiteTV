package com.cabletv.player.ui;

import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;

import androidx.appcompat.app.AppCompatActivity;

import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;
import com.cabletv.player.config.ChannelRepository;
import com.cabletv.player.config.KeyMapping;
import com.cabletv.player.model.Channel;
import com.cabletv.player.epg.EpgManager;
import xyz.doikki.videoplayer.player.VideoView;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private VideoView mVideoView;
    private ChannelRepository mChannelRepository;
    private EpgManager mEpgManager;
    private int mCurrentChannelIndex = 0;
    private long mLastChannelSwitchTime = 0;
    private static final long CHANNEL_SWITCH_DEBOUNCE_MS = 250;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mVideoView = findViewById(R.id.video_view);
        mChannelRepository = new ChannelRepository(this);
        mEpgManager = new EpgManager(this);

        // Setup channel change listener
        mChannelRepository.addListener(channels -> {
            Log.i(TAG, "Channels updated: " + channels.size());
            // Reset to first channel when playlist updates
            mCurrentChannelIndex = 0;
            playChannel(mCurrentChannelIndex);
        });

        // Load channels from configured source
        mChannelRepository.reload();

        // Set up audio manager for volume control
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
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
        // TODO: Toggle channel list sidebar visibility
        Log.d(TAG, "OK pressed - channel list toggle");
    }

    private void handleMenu() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void handleBack() {
        // TODO: Close channel list if open
        onBackPressed();
    }

    private void playChannel(int index) {
        Channel channel = mChannelRepository.getChannel(index);
        if (channel != null) {
            Log.d(TAG, "Playing channel: " + channel.name + " - " + channel.url);
            mVideoView.setUrl(channel.url);
            mVideoView.start();
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
    }
}
