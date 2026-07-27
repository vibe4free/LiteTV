package com.cabletv.player.ui;

import android.content.Context;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;
import com.cabletv.player.epg.EpgManager;
import com.cabletv.player.model.Channel;
import android.util.Log;
import com.bumptech.glide.Glide;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChannelListComponent extends FrameLayout implements IControlComponent {
    private final ControlWrapper mControlWrapper;
    private final ChannelListAdapter mAdapter;
    private boolean mIsVisible = false;
    private RecyclerView mRecyclerView;
    private LinearLayout mProgramListContainer;
    private RecyclerView mProgramRecyclerView;
    private List<Channel> mChannels = new ArrayList<>();
    private int mCurrentChannelIndex = 0;
    private int mSelectedChannelIndex = 0;
    private int mSelectedProgramIndex = 0;
    private OnChannelSelectedListener mSelectionListener;
    private EpgManager mEpgManager;
    private boolean mProgramListVisible = false;
    private static final long EPG_REFRESH_INTERVAL = 1000;
    private static final String TAG = "ChannelListComponent";

    public enum FocusPanel { CHANNELS, PROGRAMS }
    private FocusPanel mFocusPanel = FocusPanel.CHANNELS;

    static final int CHANNEL_ROW_HEIGHT_DP = 60;
    static final int PROGRAM_ROW_HEIGHT_DP = 40;
    /** Cap per-row animation time: holding the D-pad down must not queue up a long scroll. */
    private static final int MAX_SCROLL_MS_PER_INCH = 40;

    public interface OnChannelSelectedListener {
        void onChannelSelected(int index, Channel channel);
    }

    public ChannelListComponent(@NonNull Context context, List<Channel> channels) {
        super(context);
        mControlWrapper = null;
        mChannels = channels;
        initView(context);
        mAdapter = new ChannelListAdapter(context, mChannels);
        mRecyclerView.setAdapter(mAdapter);
    }

    public ChannelListComponent(@NonNull Context context, ControlWrapper controlWrapper, List<Channel> channels) {
        super(context);
        mControlWrapper = controlWrapper;
        mChannels = channels;
        initView(context);
        mAdapter = new ChannelListAdapter(context, mChannels);
        mRecyclerView.setAdapter(mAdapter);
    }

    private void initView(Context context) {
        // Expanded menu: 240dp channels on left + expandable programs on right
        setLayoutParams(new LayoutParams(dp2px(240), LayoutParams.MATCH_PARENT));
        setBackgroundResource(R.drawable.gradient_sidebar_bg);
        setAlpha(AppConfig.getSidebarAlpha());

        LinearLayout mainContainer = new LinearLayout(context);
        mainContainer.setOrientation(LinearLayout.HORIZONTAL);
        mainContainer.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Channel list section (left side, fixed width)
        LinearLayout channelSection = new LinearLayout(context);
        channelSection.setOrientation(LinearLayout.VERTICAL);
        channelSection.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1.0f));

        TextView titleView = new TextView(context);
        titleView.setText(R.string.channel_list);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(16);
        titleView.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12));
        titleView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        channelSection.addView(titleView);

        mRecyclerView = new RecyclerView(context);
        mRecyclerView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1.0f));
        mRecyclerView.setLayoutManager(new CenteringLayoutManager(context));
        mRecyclerView.setBackgroundColor(0x00000000);
        mRecyclerView.setFocusable(false);
        channelSection.addView(mRecyclerView);

        mainContainer.addView(channelSection);

        // Program list section (right side, initially hidden)
        mProgramListContainer = new LinearLayout(context);
        mProgramListContainer.setOrientation(LinearLayout.VERTICAL);
        mProgramListContainer.setLayoutParams(new LinearLayout.LayoutParams(dp2px(300), LayoutParams.MATCH_PARENT));
        mProgramListContainer.setBackgroundColor(0x66000000);
        mProgramListContainer.setVisibility(GONE);

        TextView programTitle = new TextView(context);
        programTitle.setText(R.string.todays_programs);
        programTitle.setTextColor(0xFFFFFFFF);
        programTitle.setTextSize(14);
        programTitle.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(8));
        programTitle.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        mProgramListContainer.addView(programTitle);

        mProgramRecyclerView = new RecyclerView(context);
        mProgramRecyclerView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1.0f));
        mProgramRecyclerView.setLayoutManager(new CenteringLayoutManager(context));
        mProgramRecyclerView.setBackgroundColor(0x00000000);
        mProgramRecyclerView.setFocusable(false);
        mProgramListContainer.addView(mProgramRecyclerView);

        mainContainer.addView(mProgramListContainer);
        addView(mainContainer);
    }

    public void setEpgManager(EpgManager epgManager) {
        mEpgManager = epgManager;
        mAdapter.setEpgManager(epgManager);
        if (mEpgManager != null) {
            mEpgManager.addOnEpgUpdatedListener(() -> {
                Log.d(TAG, "EPG updated, refreshing adapter and program list");
                if (mAdapter != null) {
                    mAdapter.notifyDataSetChanged();
                }
                refreshProgramListForCurrentSelection();
            });
        }
    }

    public void setOnChannelSelectedListener(OnChannelSelectedListener listener) {
        mSelectionListener = listener;
    }

    public void updateChannels(List<Channel> channels, int currentIndex) {
        mChannels = new ArrayList<>(channels);
        mCurrentChannelIndex = currentIndex;
        mSelectedChannelIndex = currentIndex;
        mAdapter.updateData(mChannels, currentIndex, currentIndex);
        scrollToCentered(mRecyclerView, currentIndex, CHANNEL_ROW_HEIGHT_DP);
    }

    public void setCurrentChannel(int index) {
        mCurrentChannelIndex = index;
        mAdapter.setCurrentChannel(index);
        scrollToCentered(mRecyclerView, index, CHANNEL_ROW_HEIGHT_DP);
    }

    private void refreshProgramListForCurrentSelection() {
        if (mSelectedChannelIndex < 0 || mSelectedChannelIndex >= mChannels.size()) return;
        if (mEpgManager == null || mProgramListContainer == null) return;

        Channel selectedChannel = mChannels.get(mSelectedChannelIndex);
        List<EpgManager.Program> programs = mEpgManager.getAllPrograms(selectedChannel);
        if (!programs.isEmpty()) {
            ProgramListAdapter programAdapter = new ProgramListAdapter(getContext(), programs);
            mProgramRecyclerView.setAdapter(programAdapter);
            mProgramListContainer.setVisibility(VISIBLE);
            mProgramListVisible = true;

            // Find and select the currently playing program
            long currentTime = System.currentTimeMillis();
            int currentProgramIndex = 0;
            for (int i = 0; i < programs.size(); i++) {
                EpgManager.Program prog = programs.get(i);
                if (currentTime >= prog.startTime && currentTime < prog.endTime) {
                    currentProgramIndex = i;
                    break;
                }
            }
            mSelectedProgramIndex = currentProgramIndex;
            programAdapter.setSelectedIndex(currentProgramIndex);
            programAdapter.setFocused(mFocusPanel == FocusPanel.PROGRAMS);
            scrollToCentered(mProgramRecyclerView, currentProgramIndex, PROGRAM_ROW_HEIGHT_DP);
        } else {
            mProgramListContainer.setVisibility(GONE);
            mProgramListVisible = false;
        }
    }

    public void selectChannel(int index) {
        if (index < 0 || index >= mChannels.size()) return;
        int oldIndex = mSelectedChannelIndex;
        mSelectedChannelIndex = index;
        mAdapter.setSelectedChannel(oldIndex, index);
        scrollToCentered(mRecyclerView, index, CHANNEL_ROW_HEIGHT_DP);

        // Reset focus to channels panel when selecting a channel
        mFocusPanel = FocusPanel.CHANNELS;
        mAdapter.setFocusOnChannels(true);

        // Trigger EPG load if needed (dedup and throttling built into loadEpg now)
        if (mEpgManager != null) {
            mEpgManager.loadEpg(mChannels.get(index));
        }

        // Refresh program list for current selection
        refreshProgramListForCurrentSelection();
    }

    public void confirmSelection() {
        if (mSelectionListener != null && mSelectedChannelIndex < mChannels.size()) {
            mSelectionListener.onChannelSelected(mSelectedChannelIndex, mChannels.get(mSelectedChannelIndex));
        }
    }

    public int getSelectedChannelIndex() {
        return mSelectedChannelIndex;
    }

    public FocusPanel getFocusPanel() {
        return mFocusPanel;
    }

    public void moveFocusToPrograms() {
        if (!mProgramListVisible) return;
        mFocusPanel = FocusPanel.PROGRAMS;
        mAdapter.setFocusOnChannels(false);
        if (mProgramRecyclerView.getAdapter() != null) {
            ((ProgramListAdapter) mProgramRecyclerView.getAdapter()).setFocused(true);
        }
    }

    public void moveFocusToChannels() {
        mFocusPanel = FocusPanel.CHANNELS;
        mAdapter.setFocusOnChannels(true);
        if (mProgramRecyclerView.getAdapter() != null) {
            ((ProgramListAdapter) mProgramRecyclerView.getAdapter()).setFocused(false);
        }
    }

    public void moveProgramSelection(boolean down) {
        RecyclerView.Adapter<?> adapter = mProgramRecyclerView.getAdapter();
        if (mFocusPanel != FocusPanel.PROGRAMS || adapter == null) return;
        int count = adapter.getItemCount();
        if (count == 0) return;
        int newIndex = Math.max(0, Math.min(count - 1, mSelectedProgramIndex + (down ? 1 : -1)));
        if (newIndex == mSelectedProgramIndex) return;
        mSelectedProgramIndex = newIndex;
        ((ProgramListAdapter) adapter).setSelectedIndex(newIndex);
        scrollToCentered(mProgramRecyclerView, newIndex, PROGRAM_ROW_HEIGHT_DP);
    }

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        // Already attached in constructor
    }

    @Nullable
    @Override
    public View getView() {
        return this;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        mIsVisible = isVisible;
        if (isVisible) {
            setVisibility(VISIBLE);
            animate()
                    .translationX(0)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            // Refresh adapter when menu becomes visible to show all EPG data
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
        } else {
            animate()
                    .translationX(-getWidth())
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> setVisibility(GONE))
                    .start();
        }
        if (anim != null) {
            startAnimation(anim);
        }
    }

    @Override
    public void onPlayStateChanged(int playState) {
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
    }

    @Override
    public void setProgress(int duration, int position) {
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
    }

    /**
     * Puts the highlighted row in the middle of its panel rather than letting it sit at the very
     * edge. A neighbouring row animates; a jump of a whole screen or more lands instantly,
     * because animating it would only make the user wait.
     */
    private void scrollToCentered(RecyclerView list, int position, int rowHeightDp) {
        RecyclerView.LayoutManager manager = list.getLayoutManager();
        if (position < 0 || !(manager instanceof LinearLayoutManager)) {
            return;
        }
        final LinearLayoutManager layoutManager = (LinearLayoutManager) manager;
        if (list.getHeight() == 0) {
            // Not laid out yet: centre once it is, otherwise the offset would be meaningless.
            list.post(() -> scrollToCentered(list, position, rowHeightDp));
            return;
        }
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        boolean nearby = first != RecyclerView.NO_POSITION
                && position >= first - 1 && position <= last + 1;
        if (nearby) {
            list.smoothScrollToPosition(position);
        } else {
            layoutManager.scrollToPositionWithOffset(
                    position, Math.max(0, (list.getHeight() - dp2px(rowHeightDp)) / 2));
        }
    }

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }

    /** A LinearLayoutManager whose smooth scrolls centre the target instead of just revealing it. */
    private static class CenteringLayoutManager extends LinearLayoutManager {
        CenteringLayoutManager(Context context) {
            super(context, LinearLayoutManager.VERTICAL, false);
        }

        @Override
        public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state,
                                           int position) {
            LinearSmoothScroller scroller = new LinearSmoothScroller(recyclerView.getContext()) {
                @Override
                public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd,
                                            int snapPreference) {
                    return (boxStart + (boxEnd - boxStart) / 2)
                            - (viewStart + (viewEnd - viewStart) / 2);
                }

                @Override
                protected float calculateSpeedPerPixel(android.util.DisplayMetrics metrics) {
                    // Float division on purpose: integer division here rounds to zero, which
                    // gives the scroller a zero duration and it silently never moves.
                    return (float) MAX_SCROLL_MS_PER_INCH / metrics.densityDpi;
                }
            };
            scroller.setTargetPosition(position);
            startSmoothScroll(scroller);
        }
    }

    // Adapter for channel list
    public static class ChannelListAdapter extends RecyclerView.Adapter<ChannelListAdapter.ChannelViewHolder> {
        private final Context mContext;
        private List<Channel> mChannels;
        private int mCurrentIndex = 0;
        private int mSelectedIndex = 0;
        private boolean mFocusOnChannels = true;
        private EpgManager mEpgManager;

        public ChannelListAdapter(Context context, List<Channel> channels) {
            mContext = context;
            mChannels = new ArrayList<>(channels);
        }

        public void setEpgManager(EpgManager epgManager) {
            mEpgManager = epgManager;
            notifyDataSetChanged();
        }

        public void updateData(List<Channel> channels, int currentIndex, int selectedIndex) {
            mChannels = new ArrayList<>(channels);
            mCurrentIndex = currentIndex;
            mSelectedIndex = selectedIndex;
            notifyDataSetChanged();
        }

        public void setCurrentChannel(int index) {
            int oldIndex = mCurrentIndex;
            mCurrentIndex = index;
            notifyItemChanged(oldIndex);
            notifyItemChanged(mCurrentIndex);
        }

        public void setSelectedChannel(int oldIndex, int newIndex) {
            mSelectedIndex = newIndex;
            notifyItemChanged(oldIndex);
            notifyItemChanged(newIndex);
        }

        public void setFocusOnChannels(boolean focus) {
            mFocusOnChannels = focus;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ChannelViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            LinearLayout view = new LinearLayout(mContext);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    dp2px(CHANNEL_ROW_HEIGHT_DP)));
            view.setPadding(dp2px(12), dp2px(8), dp2px(12), dp2px(8));
            return new ChannelViewHolder(view, mContext);
        }

        @Override
        public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
            Channel channel = mChannels.get(position);
            holder.setChannel(channel, position == mSelectedIndex, position == mCurrentIndex, mEpgManager, mFocusOnChannels);
        }

        @Override
        public int getItemCount() {
            return mChannels.size();
        }

        static class ChannelViewHolder extends RecyclerView.ViewHolder {
            private final LinearLayout container;
            private final ImageView logoView;
            private final LinearLayout textContainer;
            private final TextView nameView;
            private final TextView epgView;
            private final Context context;

            ChannelViewHolder(LinearLayout itemView, Context context) {
                super(itemView);
                this.context = context;
                this.container = itemView;
                container.setOrientation(LinearLayout.HORIZONTAL);

                // Logo
                logoView = new ImageView(context);
                logoView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                logoView.setLayoutParams(new LinearLayout.LayoutParams(
                        dp2px(36),
                        dp2px(36)));
                container.addView(logoView);

                // Text container
                textContainer = new LinearLayout(context);
                textContainer.setOrientation(LinearLayout.VERTICAL);
                textContainer.setLayoutParams(new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1.0f));
                textContainer.setPadding(dp2px(8), 0, 0, 0);

                nameView = new TextView(context);
                nameView.setTextColor(0xFFCCCCCC);
                nameView.setTextSize(14);
                nameView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                textContainer.addView(nameView);

                epgView = new TextView(context);
                epgView.setTextColor(0xFF888888);
                epgView.setTextSize(12);
                epgView.setMaxLines(1);
                epgView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                textContainer.addView(epgView);

                container.addView(textContainer);
            }

            void setChannel(Channel channel, boolean isSelected, boolean isCurrent, EpgManager epgManager, boolean focusOnChannels) {
                nameView.setText(channel.name);

                // Load logo
                if (channel.logo != null && !channel.logo.isEmpty()) {
                    Glide.with(context)
                            .load(channel.logo)
                            .centerInside()
                            .into(logoView);
                } else {
                    logoView.setImageDrawable(null);
                }

                if (isSelected && focusOnChannels) {
                    nameView.setTextColor(0xFFFF6B35);
                    nameView.setTextSize(15);
                    container.setBackgroundResource(R.drawable.channel_item_selected_bg);
                    container.animate()
                            .scaleX(1.04f)
                            .scaleY(1.04f)
                            .setDuration(150)
                            .start();
                } else if (isSelected && !focusOnChannels) {
                    // Selected but focus on program list - dimmer highlight
                    nameView.setTextColor(0xFFFF9966);
                    nameView.setTextSize(14);
                    container.setBackgroundResource(R.drawable.channel_item_selected_dim_bg);
                    container.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(150)
                            .start();
                } else if (isCurrent) {
                    nameView.setTextColor(0xFFFFFFFF);
                    nameView.setTextSize(14);
                    container.setBackgroundColor(0x00000000);
                    container.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(150)
                            .start();
                } else {
                    nameView.setTextColor(0xFFCCCCCC);
                    nameView.setTextSize(14);
                    container.setBackgroundColor(0x00000000);
                    container.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(150)
                            .start();
                }

                // Show EPG info if enabled
                if (AppConfig.isEpgDisplayEnabled() && epgManager != null) {
                    String epgInfo = epgManager.getCurrentProgramInfo(channel);
                    if (epgInfo != null && !epgInfo.isEmpty()) {
                        epgView.setText(epgInfo);
                        epgView.setVisibility(android.view.View.VISIBLE);
                    } else {
                        epgView.setVisibility(android.view.View.GONE);
                    }
                } else {
                    epgView.setVisibility(android.view.View.GONE);
                }
            }

            private int dp2px(int dp) {
                return Math.round(context.getResources().getDisplayMetrics().density * dp);
            }
        }

        private int dp2px(int dp) {
            return Math.round(mContext.getResources().getDisplayMetrics().density * dp);
        }
    }

    // Adapter for program list
    public static class ProgramListAdapter extends RecyclerView.Adapter<ProgramListAdapter.ProgramViewHolder> {
        private final Context mContext;
        private final List<EpgManager.Program> mPrograms;
        private int mSelectedIndex = -1;
        private boolean mFocused = false;

        public ProgramListAdapter(Context context, List<EpgManager.Program> programs) {
            mContext = context;
            mPrograms = new ArrayList<>(programs);
        }

        public void setSelectedIndex(int index) {
            if (mSelectedIndex == index) return;
            int oldIndex = mSelectedIndex;
            mSelectedIndex = index;
            if (oldIndex >= 0 && oldIndex < mPrograms.size()) {
                notifyItemChanged(oldIndex);
            }
            if (mSelectedIndex >= 0 && mSelectedIndex < mPrograms.size()) {
                notifyItemChanged(mSelectedIndex);
            }
        }

        public void setFocused(boolean focused) {
            if (mFocused != focused) {
                mFocused = focused;
                notifyDataSetChanged();
            }
        }

        @NonNull
        @Override
        public ProgramViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            LinearLayout view = new LinearLayout(mContext);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    dp2px(PROGRAM_ROW_HEIGHT_DP)));
            view.setPadding(dp2px(12), dp2px(6), dp2px(12), dp2px(6));
            return new ProgramViewHolder(view, mContext);
        }

        @Override
        public void onBindViewHolder(@NonNull ProgramViewHolder holder, int position) {
            EpgManager.Program program = mPrograms.get(position);
            holder.setProgram(program, position == mSelectedIndex, mFocused);
        }

        @Override
        public int getItemCount() {
            return mPrograms.size();
        }

        static class ProgramViewHolder extends RecyclerView.ViewHolder {
            private final LinearLayout container;
            private final TextView timeView;
            private final TextView titleView;
            private final Context context;

            ProgramViewHolder(LinearLayout itemView, Context context) {
                super(itemView);
                this.context = context;
                this.container = itemView;

                timeView = new TextView(context);
                timeView.setTextColor(0xFF888888);
                timeView.setTextSize(11);
                timeView.setSingleLine(true);
                timeView.setLayoutParams(new LinearLayout.LayoutParams(
                        dp2px(85),
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                container.addView(timeView);

                titleView = new TextView(context);
                titleView.setTextColor(0xFFCCCCCC);
                titleView.setTextSize(12);
                titleView.setMaxLines(1);
                titleView.setLayoutParams(new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1.0f));
                titleView.setPadding(dp2px(8), 0, 0, 0);
                container.addView(titleView);
            }

            void setProgram(EpgManager.Program program, boolean isSelected, boolean focused) {
                titleView.setText(program.title);

                // Format time display
                if (program.startTime > 0 && program.endTime > 0) {
                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
                    String timeStr = sdf.format(new java.util.Date(program.startTime)) + "-" +
                                   sdf.format(new java.util.Date(program.endTime));
                    timeView.setText(timeStr);
                } else {
                    timeView.setText("");
                }

                // Highlight current/next program based on wall-clock time
                long currentTime = System.currentTimeMillis();
                boolean isCurrentProgram = currentTime >= program.startTime && currentTime < program.endTime;

                // Apply cursor highlight if selected
                if (isSelected && focused) {
                    container.setBackgroundResource(R.drawable.channel_item_selected_bg);
                    titleView.setTextColor(0xFFFFFFFF);
                    container.animate()
                            .scaleX(1.04f)
                            .scaleY(1.04f)
                            .setDuration(150)
                            .start();
                } else if (isSelected && !focused) {
                    // Selected but focus not on program list
                    container.setBackgroundResource(R.drawable.channel_item_selected_dim_bg);
                    titleView.setTextColor(0xFFDDCCCC);
                    container.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(150)
                            .start();
                } else {
                    container.setBackgroundColor(0x00000000);
                    titleView.setTextColor(0xFFCCCCCC);
                    container.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(150)
                            .start();
                }

                // Mark currently broadcasting program with visual indicator (optional prefix or color)
                if (isCurrentProgram) {
                    timeView.setTextColor(0xFFFF9966);
                }
            }

            private int dp2px(int dp) {
                return Math.round(context.getResources().getDisplayMetrics().density * dp);
            }
        }

        private int dp2px(int dp) {
            return Math.round(mContext.getResources().getDisplayMetrics().density * dp);
        }
    }
}
