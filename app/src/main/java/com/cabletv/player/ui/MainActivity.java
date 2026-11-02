package com.cabletv.player.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;
import com.cabletv.player.config.ChannelNavigator;
import com.cabletv.player.config.ChannelRepository;
import com.cabletv.player.config.KeyMapping;
import com.cabletv.player.model.Channel;
import com.cabletv.player.epg.EpgManager;
import com.cabletv.player.server.ConfigWebServer;
import com.cabletv.player.util.CatchupUrlBuilder;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.exo.ExoMediaPlayerFactory;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    /** The request code the settings panel uses for the system document picker. */
    private static final int REQUEST_PICK_M3U_FILE = 1001;
    /** Left inset the playback strip needs while the replay badge is on screen. */
    private static final int BADGE_INSET_DP = 52;

    private VideoView mVideoView;
    private ChannelRepository mChannelRepository;
    private ChannelNavigator mNavigator;
    private EpgManager mEpgManager;
    private ChannelListComponent mChannelListComponent;
    private PlaybackInfoComponent mPlaybackInfoComponent;
    private PlaybackStatusView mPlaybackStatusView;
    private ChannelNumberView mChannelNumberView;
    private TimeShiftBarComponent mTimeShiftBar;
    /** The settings, as a panel that slides in over the picture. */
    private SettingsPanel mSettingsPanel;
    /** The corner mark that says "this is a replay, not live TV". */
    private android.widget.ImageView mCatchupBadge;
    private GestureDetector mGestureDetector;

    // Tap and double-tap bookkeeping for the touch handler above.
    private long mLastVideoTapUpMs = 0;
    private final Runnable mSingleTapRunnable = this::handleVideoTap;
    private final android.os.Handler mTapHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mTimeShiftTicker = this::tickTimeShiftBar;
    /** How far back the live time-shift bar may rewind when no EPG is known. */
    private static final long TIMESHIFT_NO_EPG_WINDOW_MS = 2 * 60 * 60 * 1000L;
    /** Dragging to within this of "now" keeps (or returns to) live. */
    private static final long TIMESHIFT_LIVE_MARGIN_MS = 5 * 1000L;
    private ChannelRepository.OnChannelsChangedListener mChannelsChangedListener;
    private int mCurrentChannelIndex = 0;
    private long mLastChannelSwitchTime = 0;
    private boolean mChannelListVisible = false;
    private boolean mIsInitialLoad = true;
    private static final long CHANNEL_SWITCH_DEBOUNCE_MS = 250;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mReconnectRunnable = this::reconnectCurrentChannel;
    private int mReconnectAttempt = 0;
    /** Which of the current channel's URLs is playing; the rest are fallbacks. */
    private int mCurrentUrlIndex = 0;
    private boolean mDestroyed = false;

    /** Everything about the replay that is running, cleared as one. */
    private final CatchupState mCatchup = new CatchupState();
    /** Told whenever a setting is written, so the player can follow it immediately. */
    private AppConfig.OnSettingsChangedListener mSettingsListener;
    /** Keys written but not yet acted on; guarded because writes arrive from other threads. */
    private final java.util.Set<String> mPendingSettingKeys = new java.util.HashSet<>();
    private final Runnable mApplySettingsRunnable = this::applyPendingSettings;
    /** Long enough to merge a page save, short enough to feel immediate. */
    private static final long SETTINGS_DEBOUNCE_MS = 80;
    private static final long CATCHUP_SEEK_STEP_MS = SeekKeyPolicy.STEP_MS;
    /** How far to rewind from "now" when live LEFT enters 回看 without EPG data. */
    private static final long CATCHUP_REWIND_MS = 2 * 60 * 1000L;
    /**
     * How far back a replay may reach. The window is not limited to the programme that is on
     * air: stepping back past its start continues into the programme before it, which is what
     * the viewer expects from a remote that keeps moving.
     *
     * <p>Three hours, not a whole day: the strip places the thumb inside this window, so a very
     * long window makes one press look like a huge jump. Three hours holds several programmes
     * and keeps the thumb roughly where it was when live playback handed over to the replay.
     */
    private static final long CATCHUP_MAX_REWIND_MS = 3 * 60 * 60 * 1000L;
    /** How far back a replay reaches when the guide knows nothing about the channel. */
    private static final long NO_GUIDE_LOOKBACK_MS = 2 * 60 * 60 * 1000L;
    /** How far past "now" a still-airing programme's window may extend before the server catches up. */
    private static final long CATCHUP_LIVE_TAIL_MS = 8 * 60 * 60 * 1000L;
    private final Runnable mCatchupSeekHideRunnable = this::hideCatchupSeekIndicator;

    /** How long the digits typed on the remote stay on screen before they tune a channel. */
    private static final long NUMBER_INPUT_TIMEOUT_MS = 2_000;
    private static final int MAX_CHANNEL_DIGITS = 4;
    private final StringBuilder mNumberInput = new StringBuilder();
    private final Runnable mNumberCommitRunnable = this::commitNumberInput;

    /** How long OK must be held to mean "long press" rather than "select". */
    private static final long OK_LONG_PRESS_MS = 600;
    private final Runnable mOkLongPressRunnable = this::onOkLongPress;

    // Seeking with the remote: a press steps, a held press accelerates (the Apple TV remote
    // cycles 2x, 3x, 4x while it is held).
    /**
     * The platform's own long-press timeout, so a remote that reports a long press is believed.
     *
     * <p>It is read when the activity starts, not in a static initializer: the framework call is
     * not available to plain unit tests, and a failing static initializer would take the whole
     * class down with it.
     */
    private long mSeekLongPressMs = 500L;
    private static final int[] SEEK_RATES = SeekKeyPolicy.RATES;
    /** How long a rate is held before the seek speeds up to the next one. */
    private static final long SEEK_RATE_STEP_MS = SeekKeyPolicy.RATE_STEP_MS;
    private static final long SEEK_RATE_TICK_MS = SeekKeyPolicy.TICK_MS;
    private boolean mSeekLongPressFired;
    private int mSeekRateIndex;
    /** When the current seek rate was entered, used to climb to the next one. */
    private long mSeekRateStepAtMs;
    private long mSeekAccumulatedMs;
    private long mSeekHoldStartMs;
    private long mSeekPressAtMs;
    /** True between the down and the up of a press this handler took. */
    private boolean mSeekPressActive;
    private int mSeekDirection;
    private final Runnable mSeekRateRunnable = this::onSeekRateTick;
    /** Fires once if the key is still down: that is a hold, so the seek accelerates. */
    private final Runnable mSeekLongPressRunnable = this::startSeekAcceleration;
    private boolean mOkLongPressFired = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The framework decides how long a key counts as "held"; a remote that reports a long
        // press of its own is believed either way.
        mSeekLongPressMs = android.view.ViewConfiguration.getLongPressTimeout();
        ImmersiveMode.apply(this);
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
        // On phones the info bar is the doorway to the channel list: tap it to open the menu.
        mPlaybackInfoComponent.setClickable(true);
        mPlaybackInfoComponent.setOnClickListener(v -> {
            if (!mChannelListVisible) {
                mChannelListVisible = true;
                showChannelList();
            }
        });

        // Centred status message for buffering / reconnect / playback failure
        mPlaybackStatusView = new PlaybackStatusView(this);
        rootView.addView(mPlaybackStatusView, mPlaybackStatusView.createCenteredLayoutParams());

        // Draggable time-shift seek bar, floating just above the playback info strip
        mTimeShiftBar = new TimeShiftBarComponent(this);
        mTimeShiftBar.setOnSeekListener(new TimeShiftBarComponent.OnSeekListener() {
            @Override
            public void onSeekTo(long targetMs) {
                handleTimeShiftSeek(targetMs);
            }

            @Override
            public void onBackToLive() {
                exitCatchupToLive();
            }
        });
        rootView.addView(mTimeShiftBar, mTimeShiftBar.createBottomLayoutParams());

        // The replay badge: a corner mark, so a paused viewer coming back to the television can
        // tell a replay from live TV at a glance.
        mCatchupBadge = new android.widget.ImageView(this);
        mCatchupBadge.setImageResource(R.drawable.ic_replay_badge);
        // The disc keeps the mark readable on a bright picture; on dark scenes it is invisible.
        mCatchupBadge.setBackgroundResource(R.drawable.replay_badge_bg);
        int badgePad = dp2px(2);
        mCatchupBadge.setPadding(badgePad, badgePad, badgePad, badgePad);
        mCatchupBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(dp2px(22), dp2px(22));
        badgeLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        badgeLp.leftMargin = dp2px(18);
        badgeLp.setMarginStart(dp2px(18));
        // In the bottom-left corner of the picture, inside the playback strip: the strip makes
        // room for it by indenting its text, so nothing is covered.
        badgeLp.bottomMargin = dp2px(10);
        rootView.addView(mCatchupBadge, badgeLp);

        // The settings slide in from the right over the picture, which keeps playing behind them.
        mSettingsPanel = new SettingsPanel(this, new SettingsPanel.Host() {
            @Override
            public void startFilePicker(Intent intent) {
                startActivityForResult(intent, REQUEST_PICK_M3U_FILE);
            }

            @Override
            public void requestStoragePermission(String[] permissions, int requestCode) {
                requestPermissions(permissions, requestCode);
            }

            @Override
            public void openSystemScreen(Intent intent) {
                startActivity(intent);
            }

            @Override
            public void onConfigurationChanged() {
                applyConfigurationChanges();
            }
        });
        rootView.addView(mSettingsPanel, mSettingsPanel.createPanelLayoutParams());

        // Phone/touch: tap video to show controls, double-tap to cycle sources, long-press
        // to replay the current programme, vertical fling to change channel, horizontal
        // fling for LEFT/RIGHT semantics (day paging / catchup seeking).
        mGestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        // Returning true keeps the detector tracking the gesture for
                        // long-press and fling.
                        return true;
                    }

                    // Tap and double-tap are decided in the touch listener above, which sees the
                    // same events but is not subject to the detector's double-tap timer, which
                    // proved unreliable with some event sources (taps arrived as DOWN + UP and the
                    // detector never confirmed either).

                    @Override
                    public void onLongPress(MotionEvent e) {
                        onOkLongPress();
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        handleVideoFling(Math.abs(velocityX) > Math.abs(velocityY),
                                velocityX > 0, velocityY < 0);
                        return true;
                    }
                });
        mGestureDetector.setIsLongpressEnabled(true);
        // Tap and double-tap are decided here rather than by GestureDetector: with the events a
        // device actually delivers (some arrive as SOURCE_TOUCH_NAVIGATION, e.g. injected taps on
        // TV remotes with touchpads), the detector's double-tap timer proved unreliable -- onDown
        // and the UP were seen, but onSingleTapConfirmed/onDoubleTap never fired. The events still
        // go through the detector below for long-press and fling.
        rootView.setOnTouchListener((v, event) -> {
            boolean handled = mGestureDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                onVideoTapUp();
            }
            return handled;
        });

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
                // Both the first load and every reload follow the channel that was playing. A
                // reload happens when the playlist itself changes (edited source, merge switch,
                // web page), and jumping to the first channel there left the viewer on a
                // different programme than the one they were watching.
                mIsInitialLoad = false;
                String playingUrl = AppConfig.getLastChannelUrl();
                int restored = mChannelRepository.findChannelIndex(playingUrl);
                mCurrentChannelIndex = restored >= 0 ? restored : 0;
                Log.d(TAG, "Channels (re)loaded: url=" + playingUrl + " → index="
                        + mCurrentChannelIndex + (restored >= 0 ? "" : " (not in playlist, using 0)"));
                // Group the new playlist, and start the channel keys in the group holding whatever
                // is about to play
                mNavigator.setChannels(channels, mChannelRepository.getAllChannels());
                mNavigator.setCurrentGroupIndex(mNavigator.homeGroupFor(mCurrentChannelIndex));
                // Refresh the list after the index is known, so the highlight matches what plays
                if (mChannelListComponent != null) {
                    mChannelListComponent.refreshChannels(mCurrentChannelIndex);
                    syncGuideFocusTime();
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

        // Follow settings as they are written (settings panel, web page, updater).
        mSettingsListener = this::onSettingsChanged;
        AppConfig.addSettingsListener(mSettingsListener);

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

        // The web page's settings arrive through AppConfig's change bus, the same path the
        // settings panel uses. Only the uploaded playlist file needs a direct callback, because
        // the file is written before its path is stored.
        ConfigWebServer.setConfigChangeListener(newPath -> {
            Log.d(TAG, "Uploaded playlist stored, reloading channels");
            if (mChannelRepository != null) {
                mChannelRepository.reload();
            }
        });
        if (AppConfig.isWebServerEnabled()) {
            ConfigWebServer.startServer(this);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Diagnostics for the reported "touch does nothing on phones": log the first DOWN only,
        // so a stream of MOVEs does not flood the log.
        return super.dispatchTouchEvent(event);
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        KeyMapping.Action action = KeyMapping.resolve(event.getKeyCode());
        // While the settings panel is open it owns the remote: the arrow keys move the focus
        // between its controls instead of changing channels, and OK presses the focused control.
        // Only the menu and the back key still belong to the player.
        if (mSettingsPanel != null && mSettingsPanel.isPanelVisible() && action != null) {
            if (action == KeyMapping.Action.MENU) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleMenu();
                }
                return true;
            }
            if (action == KeyMapping.Action.BACK) {
                if (SettingsPanel.backRouteFor(mSettingsPanel.isEditingText())
                        == SettingsPanel.BackRoute.LEAVE_FIELD) {
                    // Typing wins: BACK leaves the field (closing the keyboard and putting the row
                    // back to "press OK to edit") instead of taking the whole panel away. Handled
                    // here rather than by the field, because the input method takes BACK first to
                    // close the keyboard -- the field never sees the key.
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        mSettingsPanel.stopEditing();
                    }
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleBack();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
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
        if (action == KeyMapping.Action.LEFT || action == KeyMapping.Action.RIGHT) {
            // A seek needs the whole press-and-release sequence, so that a held press can
            // accelerate. Outside a seek (the list is open and the cursor moves between columns)
            // the keys keep their plain meaning.
            if (handleSeekDirectionKey(event, action)) {
                return true;
            }
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
            case SOURCE_SWITCH:
                handleSourceSwitch();
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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        ImmersiveMode.onWindowFocusChanged(this, hasFocus);
    }

    /**
     * Cycles through the channel's own source URLs, the same list the automatic failover
     * walks on error. A no-op for channels with a single source.
     */
    private void handleSourceSwitch() {
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null || channel.urlCount() < 2) {
            if (channel != null) {
                android.widget.Toast.makeText(this, R.string.source_only_one,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        }
        mCurrentUrlIndex = (mCurrentUrlIndex + 1) % channel.urlCount();
        String url = channel.urlAt(mCurrentUrlIndex);
        Log.i(TAG, "Manual source switch: " + channel.name + " → source "
                + (mCurrentUrlIndex + 1) + "/" + channel.urlCount());
        android.widget.Toast.makeText(this,
                getString(R.string.source_switched, channel.name,
                        mCurrentUrlIndex + 1, channel.urlCount()),
                android.widget.Toast.LENGTH_SHORT).show();
        if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
        }
        // The new source is a live stream, so leave any 回看 window behind.
        mCatchup.clear();
        cancelReconnect();
        runOnUiThread(() -> {
            mVideoView.switchUrl(url, channel.headers);
            mVideoView.start();
            refreshTimeShiftBar();
        });
    }

    /** Tap on the video: toggle the control overlay (or the channel list if it is open). */
    private void handleVideoTap() {
        if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
            return;
        }
        if (mTimeShiftBar.getVisibility() == View.VISIBLE) {
            mTimeShiftBar.hideSmooth();
            return;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel != null && mPlaybackInfoComponent != null) {
            mPlaybackInfoComponent.showChannelInfo(channel);
        }
        refreshTimeShiftBar(true);
    }

    /**
     * Fling on the video. Horizontal maps to LEFT/RIGHT (day paging inside the programme
     * column, catchup seeking full screen); vertical changes channel (or ignores the fling
     * while the list is open, where the list itself scrolls).
     */
    private void handleVideoFling(boolean horizontal, boolean positiveX, boolean upwardY) {
        if (horizontal) {
            if (positiveX) {
                handleFocusRight();
            } else {
                handleFocusLeft();
            }
            return;
        }
        if (mChannelListVisible) {
            return; // the channel list RecyclerViews own vertical swipes
        }
        if (upwardY) {
            handleChannelUp();
        } else {
            handleChannelDown();
        }
    }

    /**
     * Commit a time-shift bar drag. In catchup the window is the replay window itself; in
     * live playback dragging back enters 回看 at that time, and dropping near "now" stays live.
     */
    private void handleTimeShiftSeek(long targetMs) {
        long now = System.currentTimeMillis();
        if (mCatchup.active) {
            seekCatchupTo(targetMs);
            return;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null || !channel.hasCatchup()) {
            mTimeShiftBar.hide();
            return;
        }
        if (targetMs >= now - TIMESHIFT_LIVE_MARGIN_MS) {
            // Let go at the live edge: stay on live TV, nothing to rewind.
            mTimeShiftBar.setWindow(timeShiftWindowStart(channel), now, now);
            return;
        }
        enterCatchupFromLive(channel, targetMs);
    }

    /** Rewinds straight from the live bar to the given time (回看 at that moment). */
    private void enterCatchupFromLive(Channel channel, long targetMs) {
        long now = System.currentTimeMillis();
        // The window runs to the live edge plus a tail, so a replay can be wound forward again
        // without another round trip, and it starts far enough back to hold the preceding
        // programmes: stepping back past this programme's start must keep going.
        long end = now + CATCHUP_LIVE_TAIL_MS;
        long start = catchupWindowStart(channel, targetMs);
        EpgManager.Program program = programAt(channel, targetMs);
        if (program == null) {
            // No guide: replay a fixed window from the drop point.
            program = new EpgManager.Program(channel.name, targetMs, end);
        }
        startCatchup(channel, targetMs, start, end, program);
    }

    /**
     * The left edge of a replay window: back to the earliest programme the guide still holds,
     * within a fixed limit.
     *
     * <p>Deliberately not the start of the programme on air: that made the rewind stop dead at
     * the programme boundary, and the viewer stepping back had no way to reach what aired
     * before it.
     */
    private long catchupWindowStart(Channel channel, long targetMs) {
        return catchupWindowStart(mEpgManager.getAllPrograms(channel), targetMs,
                CATCHUP_MAX_REWIND_MS);
    }

    /**
     * The left edge of a replay window, from the guide's programmes.
     *
     * <p>Package-visible and free of Android types so the boundaries can be pinned down by
     * tests: this is what decides whether stepping back past a programme's start keeps going.
     *
     * @param programs the guide's programmes for the channel, in any order
     * @param targetMs where the playhead is about to land
     * @param now      the current time
     * @param maxRewindMs how far back the window may reach at most
     */
    static long catchupWindowStart(List<EpgManager.Program> programs, long targetMs,
            long maxRewindMs) {
        // Anchored on the playhead, not on the clock. Picking a programme from the guide is an
        // explicit request for that moment; measuring the limit back from "now" pushed the window
        // past it, so the thumb stuck to the left edge and the strip named whatever aired later
        // until the viewer nudged it forward.
        long limit = targetMs - maxRewindMs;
        long earliest = targetMs;
        boolean found = false;
        if (programs != null) {
            for (EpgManager.Program program : programs) {
                if (program == null || program.endTime <= limit) {
                    continue;
                }
                if (program.startTime > 0 && program.startTime < earliest) {
                    earliest = program.startTime;
                    found = true;
                }
            }
        }
        if (!found) {
            // No guide data before the playhead: keep a sensible span rather than a zero-width one.
            earliest = targetMs - NO_GUIDE_LOOKBACK_MS;
        }
        // Never start after the playhead: the window has to contain it.
        return Math.min(earliest, targetMs);
    }

    /** The programme covering a moment, or null when the guide does not reach that far. */
    private EpgManager.Program programAt(Channel channel, long timeMs) {
        List<EpgManager.Program> programs = mEpgManager.getAllPrograms(channel);
        if (programs == null) {
            return null;
        }
        for (EpgManager.Program program : programs) {
            if (program != null && timeMs >= program.startTime && timeMs < program.endTime) {
                return program;
            }
        }
        return null;
    }

    /** The left edge of the live time-shift window: today's programme or a fixed lookback. */
    private long timeShiftWindowStart(Channel channel) {
        long now = System.currentTimeMillis();
        EpgManager.Program current = mEpgManager.getCurrentProgram(channel);
        if (current != null && current.startTime > 0 && current.startTime < now) {
            return current.startTime;
        }
        return now - TIMESHIFT_NO_EPG_WINDOW_MS;
    }

    /** Keeps the live thumb glued to "now" while the bar is open. */
    private void tickTimeShiftBar() {
        if (mTimeShiftBar != null && mTimeShiftBar.getVisibility() == View.VISIBLE) {
            mTimeShiftBar.tickIfIdle();
            if (mTimeShiftBar.isPastLastProgramme()) {
                // Live playback crossed into a programme the track had not been told about:
                // without this the strip kept the old blocks and the programme name vanished.
                Log.d(TAG, "Time-shift bar passed the last known programme, refreshing");
                refreshTimeShiftBar();
            }
            mMainHandler.postDelayed(mTimeShiftTicker, 1000);
        }
    }

    private void startTimeShiftTicker() {
        mMainHandler.removeCallbacks(mTimeShiftTicker);
        mMainHandler.postDelayed(mTimeShiftTicker, 1000);
    }

    /** Repaints the time-shift bar for the current playback position, showing it when useful. */
    private void refreshTimeShiftBar() {
        refreshTimeShiftBar(false);
    }

    /**
     * A replay in progress: which channel, where the playhead is, and how far the strip reaches.
     *
     * <p>One object rather than six parallel fields, so leaving a replay is a single
     * {@link #clear()} and cannot leave a stale window behind for the next one.
     */
    private static final class CatchupState {
        boolean active;
        Channel channel;
        EpgManager.Program program;
        /** Programme start plus the accumulated seek offset, in epoch millis. */
        long playheadMs;
        /** The span of the replay window; it can cover several programmes. */
        long windowStartMs;
        long windowEndMs;

        void clear() {
            active = false;
            channel = null;
            program = null;
            playheadMs = 0;
            windowStartMs = 0;
            windowEndMs = 0;
        }
    }

    /**
     * Shows the replay badge exactly while a replay is running and the viewer has not switched
     * the mark off.
     */
    private void updateCatchupBadge() {
        if (mCatchupBadge == null) {
            return;
        }
        boolean show = mCatchup.active && AppConfig.isCatchupBadgeEnabled();
        mCatchupBadge.setVisibility(show ? View.VISIBLE : View.GONE);
        if (mPlaybackInfoComponent != null) {
            // The strip makes room for the badge instead of being covered by it.
            mPlaybackInfoComponent.setLeadingInsetDp(show ? BADGE_INSET_DP : 16);
        }
    }

    /**
     * Repaints the time-shift bar for the current playback position.
     *
     * @param forceShow live mode only: the bar normally stays hidden until the user taps the
     *                  video, so it never sits over TV playback uninvited. Catchup always shows
     *                  it because the drag IS the way to seek a replay.
     */
    private void refreshTimeShiftBar(boolean forceShow) {
        updateCatchupBadge();
        if (mTimeShiftBar == null) {
            return;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null) {
            mTimeShiftBar.hide();
            return;
        }
        long now = System.currentTimeMillis();
        if (mCatchup.active && mCatchup.program != null) {
            // The strip shows the whole replay window, not just the programme under the
            // playhead: that is what makes stepping into the neighbouring programme visible.
            long start = mCatchup.windowStartMs > 0
                    ? Math.min(mCatchup.windowStartMs, mCatchup.playheadMs)
                    : mCatchup.program.startTime;
            // The strip ends at "now", the same edge live playback ends at. The eight hour tail
            // in the playseek URL is about keeping the stream flowing, not about the window the
            // viewer is looking at -- including it here made the thumb jump to the middle of the
            // screen the moment a replay started.
            long end = Math.max(now, mCatchup.playheadMs);
            mTimeShiftBar.setChannelLabel(channel.name);
            mTimeShiftBar.setBackToLiveVisible(true);
            mTimeShiftBar.setWindow(start, end, mCatchup.playheadMs);
            mTimeShiftBar.setPrograms(timelineSegments(channel, start, end));
            // A replay does not follow the broadcast, so the ticker must not move the playhead.
            mTimeShiftBar.setLiveMode(false, now);
            mTimeShiftBar.show();
            startTimeShiftTicker();
            return;
        }
        if (!channel.hasCatchup()) {
            mTimeShiftBar.hide();
            return;
        }
        // Live: the window runs from the programme start to now.
        long start = timeShiftWindowStart(channel);
        mTimeShiftBar.setChannelLabel(channel.name);
        mTimeShiftBar.setBackToLiveVisible(false);
        mTimeShiftBar.setWindow(start, now, now);
        mTimeShiftBar.setPrograms(timelineSegments(channel, start, now + 6 * 60 * 60 * 1000L));
        // Watching live: the playhead follows the broadcast and the right end is the live edge.
        mTimeShiftBar.setLiveMode(true, now);
        if (forceShow) {
            mTimeShiftBar.show();
            startTimeShiftTicker();
        }
    }

    /**
     * The guide's programmes that fall inside the time-shift window, as timeline segments.
     *
     * <p>Only the parts inside the window are drawn, so the track shows what the viewer can
     * actually reach with the thumb.
     */
    private List<ProgramTimelineView.Segment> timelineSegments(Channel channel, long fromMs,
            long toMs) {
        List<ProgramTimelineView.Segment> segments = new ArrayList<>();
        List<EpgManager.Program> programs = mEpgManager.getAllPrograms(channel);
        if (programs == null || programs.isEmpty()) {
            return segments;
        }
        for (EpgManager.Program program : programs) {
            if (program == null || program.endTime <= fromMs || program.startTime >= toMs) {
                continue;
            }
            // Listed up to the window end, but the segments themselves run to their own end; a
            // programme that starts later is still needed the moment live playback crosses into
            // it, so the cut-off is generous rather than exact.
            // The programme's own boundaries, not clipped to the window: clipping here made the
            // current block shrink every time the window end moved, which read as the progress
            // bar changing length while nothing had changed.
            segments.add(new ProgramTimelineView.Segment(program.title,
                    program.startTime, program.endTime));
        }
        return segments;
    }

    private void stopTimeShiftTicker() {
        mMainHandler.removeCallbacks(mTimeShiftTicker);
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
     * Whether the horizontal keys currently mean "move the playhead".
     *
     * <p>Yes while the time-shift bar is on screen (the viewer is adjusting the playhead, not
     * browsing the list) and while a catchup is running. Otherwise the keys keep their plain
     * meaning: switching columns in the list, paging the guide by a day.
     */
    private boolean seekKeysApply() {
        // The open list owns the horizontal keys; full screen they belong to the playhead.
        return SeekKeyPolicy.appliesOnScreen(mChannelListVisible);
    }

    /**
     * Handles LEFT and RIGHT while they mean "seek".
     *
     * <p>A press steps ten seconds; holding the key accelerates through 2x, 3x and 4x and the
     * accumulated distance is applied when the key is released -- the way the Apple TV remote
     * behaves, and far easier than nudging a ten-second step to reach a missed passage.
     *
     * @return true when the event was consumed here
     */
    private boolean handleSeekDirectionKey(KeyEvent event, KeyMapping.Action action) {
        if (!seekKeysApply()) {
            return false;
        }
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                if (!mSeekPressActive) {
                    // A new press. Everything that describes "this hold" is reset here and only
                    // here: a repeated down event must never restart the clock, or a remote (or a
                    // synthetic key source) that reports its repeats with a zero repeat count
                    // would keep postponing the hold and the seek would never accelerate.
                    mSeekPressActive = true;
                    mSeekLongPressFired = false;
                    mSeekAccumulatedMs = 0;
                    // Every press starts over at the slowest rate, the way the Apple TV remote
                    // behaves: hold once for 2x, keep holding to climb to 3x and 4x.
                    mSeekRateIndex = 0;
                    mSeekRateStepAtMs = 0;
                    mSeekPressAtMs = android.os.SystemClock.uptimeMillis();
                    mSeekHoldStartMs = mSeekPressAtMs;
                    mSeekDirection = action == KeyMapping.Action.LEFT ? -1 : 1;
                    // One shot: it decides whether the key is being held. The accelerating
                    // ticker is started by startSeekAcceleration() alone -- two tickers running at
                    // once would each count the same elapsed time and double the distance.
                    mMainHandler.removeCallbacks(mSeekLongPressRunnable);
                    mMainHandler.postDelayed(mSeekLongPressRunnable, mSeekLongPressMs);
                } else if (event.getRepeatCount() > 0 || event.isLongPress()) {
                    // Still the same press: this is the keyboard repeating, so the key is being
                    // held. The timer above is on its way anyway; whichever arrives first wins.
                    startSeekAcceleration();
                }
                return true;
            case KeyEvent.ACTION_UP:
                if (!mSeekPressActive) {
                    // The press that opened the catchup was handled elsewhere; its release must
                    // not be treated as a seek with an unknown direction.
                    return true;
                }
                mSeekPressActive = false;
                mMainHandler.removeCallbacks(mSeekRateRunnable);
                mMainHandler.removeCallbacks(mSeekLongPressRunnable);
                // How long the key was held decides, rather than whether a timer happened to fire
                // first: a remote that reports its own long press, and an injected hold, both end
                // up here.
                boolean heldLong = mSeekLongPressFired
                        || android.os.SystemClock.uptimeMillis() - mSeekPressAtMs >= mSeekLongPressMs;
                if (heldLong) {
                    // A hold always moves at least one step, even when it was too brief for the
                    // accelerated distance to add up to anything. The comparison has to follow the
                    // direction: Math.max would pick the smaller magnitude on a rewind and drop
                    // everything the hold had accumulated.
                    long distance = SeekKeyPolicy.holdDistance(mSeekAccumulatedMs,
                            CATCHUP_SEEK_STEP_MS, mSeekDirection);
                    Log.i(TAG, "Held seek: " + (distance / 1000) + "s");
                    applySeekOffset(distance);
                    mSeekAccumulatedMs = 0;
                } else {
                    applySeekOffset((long) mSeekDirection * CATCHUP_SEEK_STEP_MS);
                }
                mSeekLongPressFired = false;
                if (mTimeShiftBar != null) {
                    mTimeShiftBar.endSeeking();
                }
                return true;
            default:
                return true;
        }
    }

    /** Starts the accelerated seek; each held press moves on to the next rate. */
    private void startSeekAcceleration() {
        if (mSeekLongPressFired) {
            return;
        }
        mSeekLongPressFired = true;
        if (mTimeShiftBar != null) {
            // The strip normally fades away; during a seek it has to stay so the rate is visible.
            mTimeShiftBar.showWhileSeeking();
        }
        mSeekRateIndex = 0;
        mSeekRateStepAtMs = android.os.SystemClock.uptimeMillis();
        mSeekHoldStartMs = android.os.SystemClock.uptimeMillis();
        if (mTimeShiftBar != null) {
            mTimeShiftBar.showSeekRate(SEEK_RATES[mSeekRateIndex]);
        }
        // The ticker is the only thing that counts the distance; make sure there is exactly one.
        mMainHandler.removeCallbacks(mSeekRateRunnable);
        mMainHandler.postDelayed(mSeekRateRunnable, SEEK_RATE_TICK_MS);
    }

    /** Adds the distance covered since the last tick and keeps the bar in step. */
    private void onSeekRateTick() {
        if (!mSeekLongPressFired) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        long elapsed = now - mSeekHoldStartMs;
        mSeekHoldStartMs = now;
        mSeekAccumulatedMs += SeekKeyPolicy.distance(elapsed,
                SEEK_RATES[mSeekRateIndex], mSeekDirection);
        // Keep holding and the speed climbs: 2x, then 3x, then 4x.
        if (now - mSeekRateStepAtMs >= SEEK_RATE_STEP_MS) {
            mSeekRateStepAtMs = now;
            mSeekRateIndex = SeekKeyPolicy.nextRateIndex(mSeekRateIndex);
            if (mTimeShiftBar != null) {
                mTimeShiftBar.showSeekRate(SEEK_RATES[mSeekRateIndex]);
            }
        }
        if (mTimeShiftBar != null) {
            mTimeShiftBar.showSeekPreview(SEEK_RATES[mSeekRateIndex], mSeekAccumulatedMs);
        }
        mMainHandler.postDelayed(mSeekRateRunnable, SEEK_RATE_TICK_MS);
    }

    /**
     * Moves the playhead by a signed distance.
     *
     * <p>In a catchup that is a plain step. On live it enters the catchup window at the requested
     * position, which is what a rewind from live means.
     */
    private void applySeekOffset(long deltaMs) {
        if (deltaMs == 0) {
            return;
        }
        if (mCatchup.active) {
            seekCatchup(deltaMs);
            return;
        }
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null || !channel.hasCatchup()) {
            return;
        }
        long now = System.currentTimeMillis();
        handleTimeShiftSeek(now + deltaMs);
    }

    /**
     * Long-press OK has two meanings depending on where the cursor is: over a programme it
     * replays (回看), over a channel it toggles the favourite star, and on the full-screen
     * player it replays the programme currently on air.
     */
    private void onOkLongPress() {
        if (mOkLongPressFired) {
            return;
        }
        mOkLongPressFired = true;
        mMainHandler.removeCallbacks(mOkLongPressRunnable);

        if (mChannelListVisible && mChannelListComponent != null) {
            if (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.PROGRAMS) {
                Channel channel = mChannelListComponent.getSelectedChannel();
                EpgManager.Program program = mChannelListComponent.getSelectedProgram();
                if (channel == null || program == null) {
                    android.widget.Toast.makeText(this, R.string.catchup_no_program,
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                // The replayed programme belongs to the highlighted channel, which becomes the
                // current one: BACK from catchup must land on *its* live stream, not the one
                // that was playing before the list opened.
                mCurrentChannelIndex = mChannelListComponent.getSelectedChannelIndex();
                mNavigator.setCurrentGroupIndex(mChannelListComponent.getBrowsedGroupIndex());
                playCatchup(channel, program);
                return;
            }
            // Channel / group column: long-press OK manages favourites.
            Channel target = mChannelListComponent.getSelectedChannel();
            if (target != null) {
                toggleFavorite(target);
            }
            return;
        }

        // Full-screen playback: long-press OK replays the programme currently on air.
        Channel current = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (current == null) {
            return;
        }
        EpgManager.Program program = mEpgManager.getCurrentProgram(current);
        if (program == null) {
            android.widget.Toast.makeText(this, R.string.catchup_no_program,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        playCatchup(current, program);
    }

    private void toggleFavorite(Channel target) {
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

    /** Starts time-shifted playback of the given programme, or explains why it cannot. */
    private void playCatchup(Channel channel, EpgManager.Program program) {
        if (channel == null || program == null) {
            android.widget.Toast.makeText(this, R.string.catchup_no_program,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!channel.hasCatchup()) {
            android.widget.Toast.makeText(this, R.string.catchup_not_available,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now = System.currentTimeMillis();
        long start = program.startTime;
        if (start >= now) {
            start = now - CATCHUP_SEEK_STEP_MS; // never ask for a window that starts in the future
        }
        startCatchup(channel, start, catchupWindowStart(channel, start),
                now + CATCHUP_LIVE_TAIL_MS, program);
    }

    /**
     * Live-mode LEFT enters 回看: replay the current programme from its start, or rewind a fixed
     * amount when no EPG data is known. No-op on channels without catchup.
     */
    private void enterCatchupFromLive() {
        Channel channel = mChannelRepository.getChannel(mCurrentChannelIndex);
        if (channel == null || !channel.hasCatchup()) {
            return;
        }
        EpgManager.Program program = mEpgManager.getCurrentProgram(channel);
        if (program != null) {
            playCatchup(channel, program);
            return;
        }
        // No EPG: rewind a fixed amount from "now" so the viewer can still step back.
        long now = System.currentTimeMillis();
        EpgManager.Program synthetic = new EpgManager.Program(
                channel.name, now - CATCHUP_REWIND_MS, now + CATCHUP_LIVE_TAIL_MS);
        startCatchup(channel, synthetic.startTime, synthetic.startTime, synthetic.endTime,
                synthetic);
    }

    /** Common catchup start: build the playseek URL, arm the state, switch the player. */
    private void startCatchup(Channel channel, long playheadMs, long windowStartMs, long windowEndMs,
            EpgManager.Program program) {
        String url = CatchupUrlBuilder.buildUrl(channel, playheadMs, windowEndMs);
        if (url == null) {
            android.widget.Toast.makeText(this, R.string.catchup_not_available,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        cancelReconnect();
        mCatchup.active = true;
        mCatchup.channel = channel;
        mCatchup.program = program;
        mCatchup.playheadMs = playheadMs;
        // The strip draws the playhead inside this span, so it must contain it by construction.
        mCatchup.windowStartMs = Math.min(windowStartMs, playheadMs);
        mCatchup.windowEndMs = Math.max(windowEndMs, playheadMs);
        mCurrentUrlIndex = 0;
        // The guide now belongs to the replayed programme, not to whatever airs now.
        syncGuideFocusTime();
        // Leave the list so the replayed stream is visible; short OK reopens it.
        if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
        }
        Log.i(TAG, "Catchup: " + channel.name + " · " + program.title + " → " + url);
        runOnUiThread(() -> {
            mVideoView.switchUrl(url, channel.headers);
            mVideoView.start();
            if (mChannelListComponent != null) {
                mChannelListComponent.setCurrentChannel(mCurrentChannelIndex);
            }
        });
        if (mPlaybackInfoComponent != null) {
            mPlaybackInfoComponent.showChannelInfo(channel);
        }
        refreshTimeShiftBar();
        android.widget.Toast.makeText(this,
                getString(R.string.catchup_started, program.title),
                android.widget.Toast.LENGTH_SHORT).show();
        mEpgManager.loadEpg(channel);
    }

    /** Rewinds or fast-forwards the catchup playhead by rebuilding the playseek window. */
    private void seekCatchup(long deltaMs) {
        if (!mCatchup.active || mCatchup.channel == null || mCatchup.program == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long target = Math.max(mCatchup.windowStartMs,
                Math.min(now - CATCHUP_SEEK_STEP_MS, mCatchup.playheadMs + deltaMs));
        seekCatchupTo(target);
    }

    /** Seeks the catchup playhead to an absolute position within the programme window. */
    private void seekCatchupTo(long targetMs) {
        if (!mCatchup.active || mCatchup.channel == null || mCatchup.program == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long minPlayhead = mCatchup.windowStartMs > 0 ? mCatchup.windowStartMs
                : mCatchup.program.startTime;
        long maxPlayhead = now - CATCHUP_SEEK_STEP_MS;
        long target = Math.max(minPlayhead, Math.min(targetMs, maxPlayhead));
        if (target == mCatchup.playheadMs) {
            return;
        }
        mCatchup.playheadMs = target;
        // Stepping into the programme next door makes that one the replay being watched, so the
        // name on screen and the guide focus follow the playhead.
        EpgManager.Program covering = programAt(mCatchup.channel, target);
        if (covering != null) {
            mCatchup.program = covering;
        }
        String url = CatchupUrlBuilder.buildUrl(mCatchup.channel, target,
                Math.max(mCatchup.windowEndMs, now + CATCHUP_LIVE_TAIL_MS));
        if (url == null) {
            return;
        }
        Log.i(TAG, "Catchup seek to +" + ((mCatchup.playheadMs - mCatchup.program.startTime) / 1000) + "s");
        runOnUiThread(() -> {
            mVideoView.switchUrl(url, mCatchup.channel.headers);
            refreshTimeShiftBar();
        });
        showCatchupSeekIndicator((mCatchup.playheadMs - mCatchup.program.startTime) / 1000);
    }

    private void showCatchupSeekIndicator(long offsetSec) {
        String label = (offsetSec >= 0 ? "+" : "-") + Math.abs(offsetSec) + "s";
        mChannelNumberView.showDigits(label, mCatchup.channel.name);
        mMainHandler.removeCallbacks(mCatchupSeekHideRunnable);
        mMainHandler.postDelayed(mCatchupSeekHideRunnable, 1500);
    }

    private void hideCatchupSeekIndicator() {
        if (!isNumberInputActive()) {
            mChannelNumberView.hide();
        }
    }

    /** Leaves catchup and returns to the live stream of the channel being watched. */
    private void exitCatchupToLive() {
        exitCatchupToLive(R.string.catchup_exited);
    }

    /** @param messageRes what to tell the viewer about why the replay stopped */
    private void exitCatchupToLive(int messageRes) {
        if (!mCatchup.active) {
            return;
        }
        Log.i(TAG, "Exiting catchup, returning to live");
        mCatchup.clear();
        syncGuideFocusTime();
        android.widget.Toast.makeText(this, messageRes,
                android.widget.Toast.LENGTH_SHORT).show();
        playChannel(mCurrentChannelIndex);
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

    /** Identity of the EPG settings the user controls directly (URL + priority). */
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
                if (!mChannelListComponent.moveProgramSelection(down)) {
                    // The cursor ran off an end of the day: page the guide that way, which is how
                    // the viewer reaches earlier days now that LEFT belongs to the column layout.
                    if (mChannelListComponent.moveProgramDay(down ? 1 : -1)) {
                        Log.d(TAG, "moveSelectionInList: paged the guide to another day");
                        // Land where the movement was heading: stepping up past the top of a day
                        // continues at the bottom of the previous one, and the other way round.
                        mChannelListComponent.selectProgramEdge(down);
                    }
                }
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
            // Full-screen LEFT moves the playhead back from where it is now; while watching live
            // that also enters 回看. It is handled by the seek path, so this is only a fallback.
            applySeekOffset(-CATCHUP_SEEK_STEP_MS);
            return;
        }
        // Inside the programme column, LEFT steps back a day. When there is no earlier day (most
        // feeds carry today alone) it folds back to the channels instead of doing nothing, so the
        // key is never a dead end; the ends of the list page the days as well (moveSelectionInList).
        if (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.PROGRAMS) {
            if (!mChannelListComponent.moveProgramDay(-1)) {
                Log.d(TAG, "handleFocusLeft: no earlier day, folding back to channels");
                mChannelListComponent.moveFocusToChannels();
            }
            return;
        }
        Log.d(TAG, "handleFocusLeft: Moving focus to groups");
        mChannelListComponent.moveFocusToGroups();
    }

    /** Right steps forward through the three columns: groups, channels, programmes. */
    private void handleFocusRight() {
        if (!mChannelListVisible || mChannelListComponent == null) {
            // Full-screen RIGHT moves the playhead forward; nothing happens while watching live,
            // where it is already at the live edge.
            applySeekOffset(CATCHUP_SEEK_STEP_MS);
            return;
        }
        switch (mChannelListComponent.getFocusPanel()) {
            case GROUPS:
                Log.d(TAG, "handleFocusRight: Moving focus to channels");
                mChannelListComponent.moveFocusToChannels();
                break;
            case PROGRAMS:
                // Inside the programme column, RIGHT pages the guide one day forward.
                mChannelListComponent.moveProgramDay(1);
                break;
            default:
                Log.d(TAG, "handleFocusRight: Moving focus to programs");
                mChannelListComponent.moveFocusToPrograms();
                break;
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
                if (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.PROGRAMS) {
                    // OK on a programme replays it (回看); this is the natural "select" action, so
                    // it must not play the live channel the way a channel-row click does.
                    Channel channel = mChannelListComponent.getSelectedChannel();
                    EpgManager.Program program = mChannelListComponent.getSelectedProgram();
                    if (channel != null && program != null && channel.hasCatchup()) {
                        // Remember the highlighted channel as current so exiting the replay lands
                        // on this channel's live stream, not the previously playing one.
                        mCurrentChannelIndex = mChannelListComponent.getSelectedChannelIndex();
                        mNavigator.setCurrentGroupIndex(mChannelListComponent.getBrowsedGroupIndex());
                        playCatchup(channel, program);
                        return;
                    }
                    // No catchup or no programme: fall through so OK still does something useful.
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

    /**
     * Tells the guide which moment it should land on: now while watching live, or the programme
     * being replayed during catchup. Without this the guide always aimed at the current time, so
     * opening it during a replay pointed at a programme the viewer was not watching.
     */
    private void syncGuideFocusTime() {
        if (mChannelListComponent == null) {
            return;
        }
        long focusTime = (mCatchup.active && mCatchup.program != null)
                ? mCatchup.program.startTime : System.currentTimeMillis();
        mChannelListComponent.setProgramFocusTime(focusTime);
    }

    /**
     * The group the channel list should open on.
     *
     * @param browsedGroup            the group last browsed in the list, or -1 for none
     * @param browsedHoldsPlayingChan whether that group still contains the channel being watched
     * @param walkingGroup            the group the channel up/down keys are stepping through
     */
    static int groupToOpenOn(int browsedGroup, boolean browsedHoldsPlayingChan, int walkingGroup) {
        return browsedGroup >= 0 && browsedHoldsPlayingChan ? browsedGroup : walkingGroup;
    }

    private void showChannelList() {
        if (mChannelListComponent == null) {
            mChannelListComponent = new ChannelListComponent(this, mNavigator);
            mChannelListComponent.setEpgManager(mEpgManager);

            // Set channel selection listener
            mChannelListComponent.setOnChannelSelectedListener((index, channel) -> {
                mCurrentChannelIndex = index;
                playChannel(index);
                // A row tap is a direct command: close the list, tune and play.
                if (mChannelListVisible) {
                    hideChannelList();
                    mChannelListVisible = false;
                }
            });
            // Tapping a programme row replays it (回看), the way long-pressing OK does.
            mChannelListComponent.setOnProgramClickListener((channel, program) -> {
                if (channel == null || program == null || !channel.hasCatchup()) {
                    android.widget.Toast.makeText(this, R.string.catchup_not_available,
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                mCurrentChannelIndex = mChannelListComponent.getSelectedChannelIndex();
                mNavigator.setCurrentGroupIndex(mChannelListComponent.getBrowsedGroupIndex());
                playCatchup(channel, program);
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
            // Reopen where the viewer left off: the group they last browsed while it still holds the
            // playing channel (so the highlight is there to see), otherwise the group the channel
            // keys are walking. Always using the walking group threw away a group the viewer had
            // deliberately picked — choosing 全部频道 and reopening dropped them back into CCTV.
            int browsedGroup = mChannelListComponent.getBrowsedGroupIndex();
            int groupToOpen = groupToOpenOn(browsedGroup,
                    browsedGroup >= 0 && mNavigator.positionOf(browsedGroup, mCurrentChannelIndex) >= 0,
                    mNavigator.currentGroupIndex());
            mChannelListComponent.showGroup(groupToOpen, mCurrentChannelIndex);
            mChannelListComponent.moveFocusToChannels();
            // Re-aim the guide every time the list opens: the programme that aired when the list
            // was last prepared may be over by now, which is what made the guide open on the wrong
            // programme (reported for both live and catchup).
            syncGuideFocusTime();
            mChannelListComponent.setVisibility(android.view.View.VISIBLE);
        }
    }

    private void hideChannelList() {
        if (mChannelListComponent != null) {
            mChannelListComponent.setVisibility(android.view.View.GONE);
        }
    }

    /**
     * A tap on the video just ended (ACTION_UP). A second tap within the double-tap window is a
     * source switch; otherwise the pending single-tap fires on a short delay, so the first of a
     * pair is not mistaken for a single.
     */
    private void onVideoTapUp() {
        long now = android.os.SystemClock.uptimeMillis();
        mTapHandler.removeCallbacks(mSingleTapRunnable);
        if (now - mLastVideoTapUpMs <= android.view.ViewConfiguration.getDoubleTapTimeout()) {
            mLastVideoTapUpMs = 0;
            handleSourceSwitch();
            return;
        }
        mLastVideoTapUpMs = now;
        mTapHandler.postDelayed(mSingleTapRunnable,
                android.view.ViewConfiguration.getDoubleTapTimeout() + 10);
    }

    /**
     * MENU opens the settings as a panel over the picture.
     *
     * <p>When the programme list is open, the first press folds the list away and stops there:
     * closing the list and sliding in the panel in one go is jarring, and the viewer who meant
     * to leave the list would find the settings in the way. A second press opens the panel.
     */
    private void handleMenu() {
        if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
            return;
        }
        if (mSettingsPanel == null) {
            return;
        }
        if (mSettingsPanel.isPanelVisible()) {
            mSettingsPanel.hidePanel();
        } else {
            mSettingsPanel.showPanel();
        }
    }

    private void handleBack() {
        if (mSettingsPanel != null && mSettingsPanel.isPanelVisible()) {
            // The panel is a layer over the player, so BACK takes it off before anything else.
            mSettingsPanel.hidePanel();
            return;
        }
        if (isNumberInputActive()) {
            // BACK abandons a half-typed channel number instead of leaving the app.
            cancelNumberInput();
        } else if (mChannelListVisible && mChannelListComponent != null
                && (mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.GROUPS
                || mChannelListComponent.getFocusPanel() == ChannelListComponent.FocusPanel.PROGRAMS)) {
            // The groups column was a detour, and so is the programmes (day) column: BACK folds
            // either away again rather than closing the menu.
            mChannelListComponent.moveFocusToChannels();
        } else if (mChannelListVisible) {
            hideChannelList();
            mChannelListVisible = false;
        } else if (mCatchup.active) {
            exitCatchupToLive();
        } else {
            showExitConfirmDialog();
        }
    }

    /** Asks before leaving the player, with the confirm button focused by default. */
    private void showExitConfirmDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.exit_confirm_title)
                .setMessage(R.string.exit_confirm_message)
                .setPositiveButton(R.string.exit_confirm, (d, which) -> finish())
                .setNegativeButton(R.string.exit_cancel, (d, which) -> d.dismiss())
                .create();
        dialog.show();
        // Default the focus to "同意退出" so a single OK confirms; LEFT/RIGHT moves to
        // 取消 and BACK dismisses the dialog without leaving the app.
        Button confirm = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (confirm != null) {
            confirm.requestFocus();
        }
    }

    private void playChannel(int index) {
        Log.d(TAG, "playChannel called with index: " + index);
        // Switching channels (or returning to live) always leaves catchup.
        mCatchup.clear();
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
            // Show bottom playback info bar
            runOnUiThread(() -> {
                mVideoView.switchUrl(url, channel.headers);
                mVideoView.start();
                if (mChannelListComponent != null) {
                    mChannelListComponent.setCurrentChannel(index);
                }
                if (mPlaybackInfoComponent != null) {
                    mPlaybackInfoComponent.showChannelInfo(channel);
                }
                refreshTimeShiftBar();
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
                if (mCatchup.active) {
                    // The catchup server refused this moment (most operators only keep a limited
                    // window). Say so: being dropped back to live with no explanation reads as
                    // the remote having failed.
                    Log.w(TAG, "Catchup failed, returning to live");
                    exitCatchupToLive(R.string.catchup_unavailable);
                    break;
                }
                if (!tryNextUrl()) {
                    scheduleReconnect();
                }
                break;
            case VideoView.STATE_PLAYBACK_COMPLETED:
                if (mCatchup.active) {
                    // A past programme simply finished; nothing went wrong.
                    exitCatchupToLive();
                    break;
                }
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
        if (!ReconnectPolicy.canRetry(mReconnectAttempt)) {
            Log.w(TAG, "Giving up on " + channel.name + " after " + mReconnectAttempt + " attempts");
            mPlaybackStatusView.showMessage(getString(R.string.playback_failed, channel.name));
            return;
        }
        long delay = ReconnectPolicy.delayFor(mReconnectAttempt);
        mReconnectAttempt++;
        mPlaybackStatusView.showMessage(getString(R.string.playback_reconnecting,
                ReconnectPolicy.attemptNumber(mReconnectAttempt - 1), ReconnectPolicy.maxAttempts()));
        Log.w(TAG, "Scheduling reconnect " + mReconnectAttempt + "/"
                + ReconnectPolicy.maxAttempts() + " for " + channel.name + " in " + delay + "ms");
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (mSettingsPanel != null) {
            mSettingsPanel.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (mSettingsPanel != null) {
            mSettingsPanel.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyConfigurationChanges();
        if (mVideoView != null) {
            mVideoView.resume();
        }
    }

    /**
     * Reloads whatever the settings changed.
     *
     * <p>Called when the player comes back to the front and when the settings panel saves
     * something: the panel sits inside the player, so the player is never resumed and would
     * otherwise keep playing the old playlist.
     */
    private void applyConfigurationChanges() {
        // The settings now announce themselves (AppConfig.OnSettingsChangedListener), so there is
        // nothing to compare here; this is only the "player came back to the front" path.
        updateCatchupBadge();
    }

    /**
     * Follows a setting the moment it is written.
     *
     * <p>Replaces comparing fingerprints of the playlist and guide settings on resume: that
     * scheme could only notice an edit when the player happened to come back to the front, and
     * silently missed everything written while it stayed visible (the settings panel, the web
     * page).
     */
    private void onSettingsChanged(java.util.Set<String> keys) {
        // Settings can be written from the web server's thread; the work below touches views and
        // the player, so it has to happen on the main thread.
        synchronized (mPendingSettingKeys) {
            mPendingSettingKeys.addAll(keys);
        }
        // Saving a page writes several keys in a row: collapse them into one reload.
        mMainHandler.removeCallbacks(mApplySettingsRunnable);
        mMainHandler.postDelayed(mApplySettingsRunnable, SETTINGS_DEBOUNCE_MS);
    }

    private void applyPendingSettings() {
        java.util.Set<String> keys;
        synchronized (mPendingSettingKeys) {
            keys = new java.util.HashSet<>(mPendingSettingKeys);
            mPendingSettingKeys.clear();
        }
        if (mDestroyed) {
            return;
        }
        boolean m3u = AppConfig.affectsPlaylist(keys);
        boolean epg = AppConfig.affectsGuide(keys);
        if (m3u && mChannelRepository != null) {
            Log.d(TAG, "Playlist settings changed, reloading channels");
            mChannelRepository.reload();
        }
        if (epg && mEpgManager != null) {
            Log.d(TAG, "Guide settings changed, reloading the guide");
            mEpgManager.onEpgSourceChanged();
            Channel current = mChannelRepository.getChannel(mCurrentChannelIndex);
            if (current != null) {
                mEpgManager.loadEpg(current);
            }
        }
        updateCatchupBadge();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTimeShiftTicker();
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
        stopTimeShiftTicker();
        // The web server keeps its listener in a static field; leaving ours there would leak
        // this Activity for the whole process lifetime.
        ConfigWebServer.setConfigChangeListener(null);
        ConfigWebServer.stopServer();
        if (mSettingsListener != null) {
            AppConfig.removeSettingsListener(mSettingsListener);
            mSettingsListener = null;
        }
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
