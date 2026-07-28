package com.cabletv.player.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;
import com.cabletv.player.config.ChannelNavigator;
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
    private ChannelNavigator mNavigator;
    private EpgManager mEpgManager;
    private ChannelListComponent mChannelListComponent;
    private PlaybackInfoComponent mPlaybackInfoComponent;
    private PlaybackStatusView mPlaybackStatusView;
    private ChannelNumberView mChannelNumberView;
    private ChannelRepository.OnChannelsChangedListener mChannelsChangedListener;
    private int mCurrentChannelIndex = 0;
    private long mLastChannelSwitchTime = 0;
    private boolean mChannelListVisible = false;
    private boolean mIsInitialLoad = true;
    private static final long CHANNEL_SWITCH_DEBOUNCE_MS = 250;

    /** Backoff between reconnect attempts; its length is also the attempt limit. */
    private static final long[] RECONNECT_DELAYS_MS = {1_000, 2_000, 4_000, 8_000, 15_000};
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mReconnectRunnable = this::reconnectCurrentChannel;
    private int mReconnectAttempt = 0;
    /** Which of the current channel's URLs is playing; the rest are fallbacks. */
    private int mCurrentUrlIndex = 0;
    private boolean mDestroyed = false;

    /** How long the digits typed on the remote stay on screen before they tune a channel. */
    private static final long NUMBER_INPUT_TIMEOUT_MS = 2_000;
    private static final int MAX_CHANNEL_DIGITS = 4;
    private final StringBuilder mNumberInput = new StringBuilder();
    private final Runnable mNumberCommitRunnable = this::commitNumberInput;

    /** How long OK must be held to mean "favourite this channel" rather than "select". */
    private static final long OK_LONG_PRESS_MS = 600;
    private final Runnable mOkLongPressRunnable = this::onOkLongPress;
    private boolean mOkLongPressFired = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mVideoView = findViewById(R.id.video_view);
        // Configure VideoView to use ExoPlayer instead of default Android MediaPlayer
        mVideoView.setPlayerFactory(new ExoMediaPlayerFactory());
        mChannelRepository = new ChannelRepository(this);
        mNavigator = new ChannelNavigator();
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

        // Centred status message for buffering / reconnect / playback failure
        mPlaybackStatusView = new PlaybackStatusView(this);
        rootView.addView(mPlaybackStatusView, mPlaybackStatusView.createCenteredLayoutParams());

        // Digits typed on the remote, shown while the user is still typing
        mChannelNumberView = new ChannelNumberView(this);
        rootView.addView(mChannelNumberView, mChannelNumberView.createTopEndLayoutParams());

        // Surface playback failures and reconnect automatically instead of showing a black screen
        mVideoView.addOnStateChangeListener(new VideoView.SimpleOnStateChangeListener() {
            @Override
            public void onPlayStateChanged(int playState) {
                handlePlayStateChanged(playState);
            }
        });

        // Setup channel change listener (always called on the main thread)
        mChannelsChangedListener = new ChannelRepository.OnChannelsChangedListener() {
            @Override
            public void onChannelsChanged(java.util.List<com.cabletv.player.model.ChannelGroup> channels) {
                Log.i(TAG, "Channels updated: " + channels.size());
                // On initial load, try to resume last played channel; on subsequent reloads, reset to first
                if (mIsInitialLoad) {
                    mIsInitialLoad = false;
                    mCurrentChannelIndex = mChannelRepository.getCurrentChannelIndex(AppConfig.getLastChannelUrl());
                    Log.d(TAG, "Initial load: restoring last channel with url=" + AppConfig.getLastChannelUrl() + ", index=" + mCurrentChannelIndex);
                } else {
                    mCurrentChannelIndex = 0;
                    Log.d(TAG, "Subsequent reload: resetting to first channel");
                }
                // Group the new playlist, and start the channel keys in the group holding whatever
                // is about to play
                mNavigator.setChannels(channels, mChannelRepository.getAllChannels());
                mNavigator.setCurrentGroupIndex(mNavigator.homeGroupFor(mCurrentChannelIndex));
                // Refresh the list after the index is known, so the highlight matches what plays
                if (mChannelListComponent != null) {
                    mChannelListComponent.refreshChannels(mCurrentChannelIndex);
                }
                // Hand the channels to the EPG manager now that they are truly available (no
                // race with reload()). It decides for itself whether the cache still serves or
                // the feed has to be fetched, and it runs before playChannel() so the load
                // triggered by the first channel is recognised as the same one.
                java.util.List<Channel> allChannels = mChannelRepository.getAllChannels();
                if (allChannels != null && !allChannels.isEmpty()) {
                    mEpgManager.preloadEpgForAllChannels(allChannels);
                }
                playChannel(mCurrentChannelIndex);
            }
        };
        mChannelRepository.addListener(mChannelsChangedListener);

        // Tell the user when the playlist cannot be loaded or refreshed
        mChannelRepository.setOnLoadFailedListener((reason, servedFromCache) -> {
            Log.w(TAG, "Playlist load failed: " + reason + ", servedFromCache=" + servedFromCache);
            android.widget.Toast.makeText(MainActivity.this,
                    getString(servedFromCache
                                    ? R.string.playlist_load_failed_cached
                                    : R.string.playlist_load_failed,
                            reason),
                    android.widget.Toast.LENGTH_LONG).show();
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

        mChannelRepository.reload();

        // Set up audio manager for volume control
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Register for remote configuration changes unconditionally: the user can switch the web
        // server on from Settings later, and without a listener those edits would only take
        // effect on the next launch.
        ConfigWebServer.setConfigChangeListener(new ConfigWebServer.OnConfigChangeListener() {
            @Override
            public void onM3uUrlChanged(String newUrl) {
                Log.d(TAG, "M3U URL changed, reloading channels: " + newUrl);
                mChannelRepository.reload();
            }

            @Override
            public void onEpgUrlChanged(String newUrl) {
                Log.d(TAG, "EPG URL changed, reloading EPG: " + newUrl);
                mEpgManager.onEpgSourceChanged();
                // Per-channel sources load lazily, so ask for the channel on screen right
                // away instead of leaving its info bar empty until the user navigates.
                Channel current = mChannelRepository.getChannel(mCurrentChannelIndex);
                if (current != null) {
                    mEpgManager.loadEpg(current);
                }
            }
        });
        if (AppConfig.isWebServerEnabled()) {
            ConfigWebServer.startServer(this);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        KeyMapping.Action action = KeyMapping.resolve(event.getKeyCode());
        if (action == null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "No action mapped for keycode: " + event.getKeyCode());
            }
            return super.dispatchKeyEvent(event);
        }
        // OK is the only key whose short and long press differ, so it is the only one that has to
        // see the whole press-and-release sequence instead of just the press.
        if (action == KeyMapping.Action.OK) {
            return handleOkKey(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        Log.d(TAG, "Key pressed: " + event.getKeyCode() + " ("
                + KeyEvent.keyCodeToString(event.getKeyCode()) + "), action: " + action);

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
            case MENU:
                handleMenu();
                return true;
            case BACK:
                handleBack();
                return true;
            default:
                int digit = digitFor(action);
                if (digit >= 0) {
                    handleNumberKey(digit);
                    return true;
                }
                return super.dispatchKeyEvent(event);
        }
    }

    /**
     * Splits OK into its two meanings: released quickly it selects, held down it favourites. The
     * short press has to wait for the release, otherwise both would fire on the same press.
     */
    private boolean handleOkKey(KeyEvent event) {
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                if (event.getRepeatCount() == 0) {
                    mOkLongPressFired = false;
                    mMainHandler.removeCallbacks(mOkLongPressRunnable);
                    mMainHandler.postDelayed(mOkLongPressRunnable, OK_LONG_PRESS_MS);
                } else if (event.isLongPress()) {
                    // Remotes that report the long press themselves are believed straight away
                    onOkLongPress();
                }
                return true;
            case KeyEvent.ACTION_UP:
                mMainHandler.removeCallbacks(mOkLongPressRunnable);
                if (!mOkLongPressFired) {
                    handleOk();
                }
                mOkLongPressFired = false;
                return true;
            default:
                return true;
        }
    }

    /**
     * Marks or unmarks the channel the viewer is pointing at: the highlighted one when the list is
     * open, otherwise the one on screen.
     */
    private void onOkLongPress() {
        if (mOkLongPressFired) {
            return;
        }
        mOkLongPressFired = true;
        mMainHandler.removeCallbacks(mOkLongPressRunnable);

        Channel target = mChannelListVisible && mChannelListComponent != null
                ? mChannelListComponent.getSelectedChannel()
                : mChannelRepository.getChannel(mCurrentChannelIndex);
        if (target == null) {
            return;
        }
        boolean nowFavorite = mNavigator.toggleFavorite(target);
        Log.i(TAG, (nowFavorite ? "Favourited: " : "Unfavourited: ") + target.name);
        if (mChannelListComponent != null) {
            mChannelListComponent.onFavoritesChanged();
        }
        android.widget.Toast.makeText(this,
                getString(nowFavorite ? R.string.favorite_added : R.string.favorite_removed,
                        target.name),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** @return 0-9 for the number actions, or -1 for anything else. */
    private static int digitFor(KeyMapping.Action action) {
        int offset = action.ordinal() - KeyMapping.Action.NUM_0.ordinal();
        return offset >= 0 && offset <= 9 ? offset : -1;
    }

    /**
     * Collects the digits typed on the remote and tunes to that channel number once the user
     * stops typing, the way a TV does. Numbers are positions in the channel list, counted from 1.
     */
    private void handleNumberKey(int digit) {
        if (mNumberInput.length() >= MAX_CHANNEL_DIGITS) {
            mNumberInput.setLength(0);
        }
        mNumberInput.append(digit);
        Channel target = channelForNumber(numberOf(mNumberInput));
        mChannelNumberView.showDigits(mNumberInput.toString(), target != null ? target.name : null);
        mMainHandler.removeCallbacks(mNumberCommitRunnable);
        mMainHandler.postDelayed(mNumberCommitRunnable, NUMBER_INPUT_TIMEOUT_MS);
    }

    private void commitNumberInput() {
        int number = numberOf(mNumberInput);
        cancelNumberInput();
        Channel target = channelForNumber(number);
        if (target == null) {
            Log.d(TAG, "No channel at number " + number);
            return;
        }
        if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
        }
        mCurrentChannelIndex = number - 1;
        // Channel numbers count through the whole playlist, so the one just typed may well be
        // outside the group being walked; the channel keys carry on from wherever it landed.
        mNavigator.setCurrentGroupIndex(mNavigator.homeGroupFor(mCurrentChannelIndex));
        playChannel(mCurrentChannelIndex);
    }

    private void cancelNumberInput() {
        mNumberInput.setLength(0);
        mMainHandler.removeCallbacks(mNumberCommitRunnable);
        if (mChannelNumberView != null) {
            mChannelNumberView.hide();
        }
    }

    private boolean isNumberInputActive() {
        return mNumberInput.length() > 0;
    }

    private Channel channelForNumber(int number) {
        return number >= 1 ? mChannelRepository.getChannel(number - 1) : null;
    }

    private static int numberOf(CharSequence digits) {
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return -1;
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
            moveSelectionInList(false);
        } else {
            // No channel list visible: change channel (UP = next channel)
            tuneWithinCurrentGroup(1);
        }
    }

    private void handleChannelDown() {
        long now = System.currentTimeMillis();
        if (now - mLastChannelSwitchTime < CHANNEL_SWITCH_DEBOUNCE_MS) {
            return;
        }
        mLastChannelSwitchTime = now;

        if (mChannelListVisible && mChannelListComponent != null) {
            moveSelectionInList(true);
        } else {
            // No channel list visible: change channel (DOWN = previous channel)
            tuneWithinCurrentGroup(-1);
        }
    }

    /** Up and down move the cursor inside whichever of the three columns has the focus. */
    private void moveSelectionInList(boolean down) {
        switch (mChannelListComponent.getFocusPanel()) {
            case PROGRAMS:
                mChannelListComponent.moveProgramSelection(down);
                break;
            case GROUPS:
                mChannelListComponent.moveGroupSelection(down);
                break;
            default:
                mChannelListComponent.moveChannelSelection(down);
                break;
        }
    }

    /**
     * Tunes one channel along the group the viewer chose, wrapping at its ends. Leaving the group
     * takes opening the list or typing a number, so stepping through a group of favourites does not
     * drop the viewer into an unrelated part of the playlist.
     */
    private void tuneWithinCurrentGroup(int delta) {
        int next = mNavigator.step(mNavigator.currentGroupIndex(), mCurrentChannelIndex, delta);
        Log.d(TAG, "tuneWithinCurrentGroup: group=" + mNavigator.currentGroupIndex()
                + ", from=" + mCurrentChannelIndex + ", to=" + next);
        if (next < 0) {
            return;
        }
        mCurrentChannelIndex = next;
        playChannel(mCurrentChannelIndex);
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

    /** Left steps back through the three columns: programmes, channels, groups. */
    private void handleFocusLeft() {
        if (!mChannelListVisible || mChannelListComponent == null) {
            return;
        }
        if (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.PROGRAMS) {
            Log.d(TAG, "handleFocusLeft: Moving focus to channels");
            mChannelListComponent.moveFocusToChannels();
        } else {
            Log.d(TAG, "handleFocusLeft: Moving focus to groups");
            mChannelListComponent.moveFocusToGroups();
        }
    }

    /** Right steps forward through the three columns: groups, channels, programmes. */
    private void handleFocusRight() {
        if (!mChannelListVisible || mChannelListComponent == null) {
            return;
        }
        if (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.GROUPS) {
            Log.d(TAG, "handleFocusRight: Moving focus to channels");
            mChannelListComponent.moveFocusToChannels();
        } else {
            Log.d(TAG, "handleFocusRight: Moving focus to programs");
            mChannelListComponent.moveFocusToPrograms();
        }
    }

    private void handleOk() {
        Log.d(TAG, "handleOk called, mChannelListVisible=" + mChannelListVisible);
        if (mChannelListVisible) {
            if (mChannelListComponent != null) {
                if (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.GROUPS) {
                    // OK on a group means "let me pick from this one", not "play something"
                    mChannelListComponent.moveFocusToChannels();
                    return;
                }
                // The group the channel was picked from is the one the channel keys walk from now on
                mNavigator.setCurrentGroupIndex(mChannelListComponent.getBrowsedGroupIndex());
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
            mChannelListComponent = new ChannelListComponent(this, mNavigator);
            mChannelListComponent.setEpgManager(mEpgManager);

            // Set channel selection listener
            mChannelListComponent.setOnChannelSelectedListener((index, channel) -> {
                mCurrentChannelIndex = index;
                playChannel(index);
            });

            // Add to VideoView's parent or create appropriate params
            if (mVideoView.getParent() instanceof android.view.ViewGroup) {
                android.view.ViewGroup parent = (android.view.ViewGroup) mVideoView.getParent();
                // The menu measures itself: its columns come and go, so a fixed width would leave
                // an empty stripe of background behind whichever column is folded away.
                if (parent instanceof FrameLayout) {
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT);
                    lp.gravity = android.view.Gravity.START;
                    mChannelListComponent.setLayoutParams(lp);
                } else {
                    android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
                    mChannelListComponent.setLayoutParams(lp);
                }
                parent.addView(mChannelListComponent);
            }
        }
        // Always refresh when opening menu to show current channel and program
        if (mChannelListComponent != null) {
            // The viewer may have changed the opacity in the settings since the menu was built
            mChannelListComponent.applyOpacity();
            mChannelListComponent.setCurrentChannel(mCurrentChannelIndex);
            // Open on the group the channel keys are walking, with the playing channel highlighted
            mChannelListComponent.showGroup(mNavigator.currentGroupIndex(), mCurrentChannelIndex);
            mChannelListComponent.moveFocusToChannels();
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
        if (isNumberInputActive()) {
            // BACK abandons a half-typed channel number instead of leaving the app.
            cancelNumberInput();
        } else if (mChannelListVisible && mChannelListComponent != null
                && mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.GROUPS) {
            // The group column was a detour: BACK folds it away again rather than closing the menu.
            mChannelListComponent.moveFocusToChannels();
        } else if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
        } else {
            onBackPressed();
        }
    }

    private void playChannel(int index) {
        Log.d(TAG, "playChannel called with index: " + index);
        // A deliberate channel change cancels any pending reconnect for the previous channel
        cancelReconnect();
        Channel channel = mChannelRepository.getChannel(index);
        Log.d(TAG, "Got channel from repository: " + (channel != null ? channel.name : "null"));
        if (channel != null) {
            // A new channel always starts from its first source
            mCurrentUrlIndex = 0;
            final String url = channel.url();
            Log.d(TAG, "Playing channel: " + channel.name + " - " + url
                    + " (" + channel.urlCount() + " source(s))");
            // Ensure UI operations happen on main thread
            runOnUiThread(() -> {
                mVideoView.switchUrl(url, channel.headers);
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
            AppConfig.setLastChannelUrl(url);
            Log.d(TAG, "About to call mEpgManager.loadEpg for channel: " + channel.name);
            mEpgManager.loadEpg(channel);
        } else {
            Log.d(TAG, "Channel is null for index: " + index);
        }
    }

    private void handlePlayStateChanged(int playState) {
        Log.d(TAG, "Play state changed: " + playState);
        switch (playState) {
            case VideoView.STATE_PLAYING:
            case VideoView.STATE_BUFFERED:
                // Stream is alive again: drop the backoff and clear the message
                cancelReconnect();
                break;
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_BUFFERING:
                if (mReconnectAttempt == 0) {
                    // Don't overwrite the reconnect countdown with a plain "buffering"
                    mPlaybackStatusView.showMessage(getString(R.string.playback_buffering));
                }
                break;
            case VideoView.STATE_ERROR:
            case VideoView.STATE_PLAYBACK_COMPLETED:
                // Live streams should never complete; when they do, the source dropped us.
                // A dead source is worth replacing before waiting: try the channel's other
                // URLs first, and only back off once none of them work.
                if (!tryNextUrl()) {
                    scheduleReconnect();
                }
                break;
            default:
                break;
        }
    }

    /**
     * Moves to the next URL the playlist lists for this channel.
     *
     * @return true when another source was started, false when they have all been tried
     */
    private boolean tryNextUrl() {
        if (mDestroyed) {
            return false;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null || mCurrentUrlIndex + 1 >= channel.urlCount()) {
            return false;
        }
        mCurrentUrlIndex++;
        String url = channel.urlAt(mCurrentUrlIndex);
        Log.w(TAG, "Source " + (mCurrentUrlIndex + 1) + "/" + channel.urlCount()
                + " for " + channel.name + ": " + url);
        mPlaybackStatusView.showMessage(getString(R.string.playback_trying_backup,
                channel.name, mCurrentUrlIndex + 1, channel.urlCount()));
        restartPlayer(channel, url);
        return true;
    }

    private void scheduleReconnect() {
        if (mDestroyed) {
            return;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null) {
            return;
        }
        if (mReconnectAttempt >= RECONNECT_DELAYS_MS.length) {
            Log.w(TAG, "Giving up on " + channel.name + " after " + mReconnectAttempt + " attempts");
            mPlaybackStatusView.showMessage(getString(R.string.playback_failed, channel.name));
            return;
        }
        long delay = RECONNECT_DELAYS_MS[mReconnectAttempt];
        mReconnectAttempt++;
        mPlaybackStatusView.showMessage(getString(R.string.playback_reconnecting,
                mReconnectAttempt, RECONNECT_DELAYS_MS.length));
        Log.w(TAG, "Scheduling reconnect " + mReconnectAttempt + "/" + RECONNECT_DELAYS_MS.length
                + " for " + channel.name + " in " + delay + "ms");
        mMainHandler.removeCallbacks(mReconnectRunnable);
        mMainHandler.postDelayed(mReconnectRunnable, delay);
    }

    private void reconnectCurrentChannel() {
        if (mDestroyed || mVideoView == null) {
            return;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null) {
            return;
        }
        Log.i(TAG, "Reconnect attempt " + mReconnectAttempt + " for " + channel.name);
        // Start the whole list over: the source that failed a moment ago may well be back, and
        // the ones after it get another turn through tryNextUrl().
        mCurrentUrlIndex = 0;
        restartPlayer(channel, channel.url());
    }

    /** Rebuilds the player: after an error the existing instance cannot be reused. */
    private void restartPlayer(Channel channel, String url) {
        if (url == null) {
            return;
        }
        mVideoView.release();
        mVideoView.setUrl(url, channel.headers);
        mVideoView.start();
    }

    private void cancelReconnect() {
        mReconnectAttempt = 0;
        mMainHandler.removeCallbacks(mReconnectRunnable);
        if (mPlaybackStatusView != null) {
            mPlaybackStatusView.hideMessage();
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
        // Leaving mid-press must not turn into a favourite once we are no longer on screen
        mMainHandler.removeCallbacks(mOkLongPressRunnable);
        if (mVideoView != null) {
            mVideoView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mMainHandler.removeCallbacks(mReconnectRunnable);
        mMainHandler.removeCallbacks(mNumberCommitRunnable);
        mMainHandler.removeCallbacks(mOkLongPressRunnable);
        // The web server keeps its listener in a static field; leaving ours there would leak
        // this Activity for the whole process lifetime.
        ConfigWebServer.setConfigChangeListener(null);
        ConfigWebServer.stopServer();
        if (mChannelsChangedListener != null) {
            mChannelRepository.removeListener(mChannelsChangedListener);
            mChannelsChangedListener = null;
        }
        mChannelRepository.clearListeners();
        mEpgManager.shutdown();
        if (mVideoView != null) {
            mVideoView.release();
        }
        super.onDestroy();
    }

    private int dp2px(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }
}
