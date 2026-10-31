package com.cabletv.player.ui;

import android.content.Context;
import android.view.Gravity;
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
import com.cabletv.player.config.ChannelNavigator;
import com.cabletv.player.epg.EpgCache;
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

/**
 * The sidebar: groups on the left, the selected group's channels in the middle, the selected
 * channel's programmes on the right. Rows are never focusable — the highlight is moved by
 * MainActivity's key handling, so the three columns cannot disagree about where the cursor is.
 */
public class ChannelListComponent extends FrameLayout implements IControlComponent {
    private final ChannelNavigator mNavigator;
    private final GroupListAdapter mGroupAdapter;
    private final ChannelListAdapter mAdapter;
    private boolean mIsVisible = false;
    private LinearLayout mGroupSection;
    private LinearLayout mChannelSection;
    private TextView mTitleView;
    private RecyclerView mGroupRecyclerView;
    private RecyclerView mRecyclerView;
    private TextView mEmptyView;
    private LinearLayout mProgramListContainer;
    private RecyclerView mProgramRecyclerView;
    /** Channels of the group being browsed, and the flat index of each of them. */
    private List<Channel> mChannels = new ArrayList<>();
    private List<Integer> mVisibleIndices = new ArrayList<>();
    private int mBrowsedGroup = ChannelNavigator.FAVORITES_GROUP;
    /** Flat playlist indices, as used by the rest of the app; -1 when the group is empty. */
    private int mCurrentChannelIndex = 0;
    private int mSelectedChannelIndex = 0;
    private int mSelectedProgramIndex = 0;
    private TextView mProgramTitleView;
    /**
     * Guide day currently shown, relative to today: 0 = today, -1 = yesterday, ... -6.
     * Navigated with LEFT/RIGHT while the programme column has focus.
     */
    /** One scroller for the whole list: only the highlighted row's label moves. */
    private final RowLabelScroller mLabelScroller = new RowLabelScroller();
    private ProgramListAdapter mProgramAdapterRef;

    private int mDayOffset = 0;
    /**
     * The moment the guide should land on: the current time for live television, or the start of
     * the programme being replayed during catchup. 0 means "follow the clock".
     */
    private long mProgramFocusTimeMs = 0;
    private static final int MAX_DAY_BACK = 6;  // today plus six days back = the 7-day window
    private static final int MAX_DAY_AHEAD = 1; // feeds (51zmt etc.) often carry tomorrow
    /** Day offsets (within the window) that the selected channel actually has data for. */
    private java.util.List<Integer> mAvailableDayOffsets = new ArrayList<>();
    private OnChannelSelectedListener mSelectionListener;
    private OnProgramClickListener mProgramClickListener;
    private EpgManager mEpgManager;
    private boolean mProgramListVisible = false;
    private static final String TAG = "ChannelListComponent";

    public enum FocusPanel { GROUPS, CHANNELS, PROGRAMS }
    private FocusPanel mFocusPanel = FocusPanel.CHANNELS;

    static final int GROUP_ROW_HEIGHT_DP = 44;
    static final int CHANNEL_ROW_HEIGHT_DP = 60;
    static final int PROGRAM_ROW_HEIGHT_DP = 40;
    /** Cap per-row animation time: holding the D-pad down must not queue up a long scroll. */
    private static final int MAX_SCROLL_MS_PER_INCH = 40;

    public interface OnChannelSelectedListener {
        void onChannelSelected(int index, Channel channel);
    }

    /** Fired when the user taps a programme row (回看). */
    public interface OnProgramClickListener {
        void onProgramClick(Channel channel, EpgManager.Program program);
    }

    public ChannelListComponent(@NonNull Context context, ChannelNavigator navigator) {
        super(context);
        mNavigator = navigator;
        initView(context);
        mGroupAdapter = new GroupListAdapter(context, navigator);
        mGroupRecyclerView.setAdapter(mGroupAdapter);
        mAdapter = new ChannelListAdapter(context, navigator, mLabelScroller);
        mRecyclerView.setAdapter(mAdapter);
        // Touch: tapping a row acts like the D-pad cursor + OK on that row.
        mGroupAdapter.setRowClickListener(this::onGroupRowTap);
        mAdapter.setRowClickListener(this::onChannelRowTap);
    }

    private void initView(Context context) {
        // The menu is only as wide as the columns currently on screen: the group column is folded
        // away until asked for, and the programme column comes and goes with the EPG data.
        setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        setBackgroundResource(R.drawable.gradient_sidebar_bg);

        LinearLayout mainContainer = new LinearLayout(context);
        mainContainer.setOrientation(LinearLayout.HORIZONTAL);
        mainContainer.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

        // Group list section (far left, fixed width, hidden until LEFT is pressed)
        LinearLayout groupSection = new LinearLayout(context);
        mGroupSection = groupSection;
        groupSection.setOrientation(LinearLayout.VERTICAL);
        groupSection.setLayoutParams(new LinearLayout.LayoutParams(dp2px(130), LayoutParams.MATCH_PARENT));
        groupSection.setVisibility(GONE);

        TextView groupTitle = new TextView(context);
        groupTitle.setText(R.string.groups);
        groupTitle.setTextColor(0xFFFFFFFF);
        groupTitle.setTextSize(16);
        groupTitle.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12));
        groupTitle.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        groupSection.addView(groupTitle);

        mGroupRecyclerView = new RecyclerView(context);
        mGroupRecyclerView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1.0f));
        mGroupRecyclerView.setLayoutManager(new CenteringLayoutManager(context));
        mGroupRecyclerView.setBackgroundColor(0x00000000);
        mGroupRecyclerView.setFocusable(false);
        groupSection.addView(mGroupRecyclerView);

        mainContainer.addView(groupSection);

        // Channel list section (middle). Fixed width rather than weighted: the programme panel
        // comes and goes, and a weighted column would stretch every row each time it did.
        LinearLayout channelSection = new LinearLayout(context);
        mChannelSection = channelSection;
        channelSection.setOrientation(LinearLayout.VERTICAL);
        channelSection.setLayoutParams(new LinearLayout.LayoutParams(dp2px(240), LayoutParams.MATCH_PARENT));

        // Names the group being browsed: the group column is usually folded away, so this is the
        // only thing telling the viewer which list they are looking at.
        mTitleView = new TextView(context);
        mTitleView.setText(R.string.channel_list);
        mTitleView.setTextColor(0xFFFFFFFF);
        mTitleView.setTextSize(16);
        mTitleView.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12));
        mTitleView.setSingleLine();
        mTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mTitleView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        channelSection.addView(mTitleView);

        mRecyclerView = new RecyclerView(context);
        mRecyclerView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1.0f));
        mRecyclerView.setLayoutManager(new CenteringLayoutManager(context));
        mRecyclerView.setBackgroundColor(0x00000000);
        mRecyclerView.setFocusable(false);
        channelSection.addView(mRecyclerView);

        // Shown in place of the list when the selected group has nothing in it, so an empty
        // favourites group explains itself instead of looking broken.
        mEmptyView = new TextView(context);
        mEmptyView.setTextColor(0xFF999999);
        mEmptyView.setTextSize(13);
        mEmptyView.setGravity(Gravity.CENTER);
        mEmptyView.setPadding(dp2px(16), dp2px(24), dp2px(16), dp2px(16));
        mEmptyView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1.0f));
        mEmptyView.setVisibility(GONE);
        channelSection.addView(mEmptyView);

        mainContainer.addView(channelSection);

        // Program list section (right side, initially hidden)
        mProgramListContainer = new LinearLayout(context);
        mProgramListContainer.setOrientation(LinearLayout.VERTICAL);
        mProgramListContainer.setLayoutParams(new LinearLayout.LayoutParams(dp2px(372), LayoutParams.MATCH_PARENT));
        mProgramListContainer.setVisibility(GONE);

        TextView programTitle = new TextView(context);
        programTitle.setText(R.string.todays_programs);
        programTitle.setTextColor(0xFFFFFFFF);
        programTitle.setTextSize(14);
        programTitle.setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(8));
        programTitle.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        mProgramTitleView = programTitle;
        mProgramListContainer.addView(programTitle);

        mProgramRecyclerView = new RecyclerView(context);
        mProgramRecyclerView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1.0f));
        mProgramRecyclerView.setLayoutManager(new CenteringLayoutManager(context));
        mProgramRecyclerView.setBackgroundColor(0x00000000);
        mProgramRecyclerView.setFocusable(false);
        mProgramListContainer.addView(mProgramRecyclerView);

        mainContainer.addView(mProgramListContainer);
        addView(mainContainer);
        applyOpacity();
    }

    /**
     * Repaints the panels at the opacity the viewer picked in the settings. The translucent level is
     * the original look — a faded panel over the video — while the other two keep the panels at full
     * strength and darken what shows through, so the text stays crisp.
     */
    public void applyOpacity() {
        int level = AppConfig.getSidebarOpacity();
        setAlpha(level == AppConfig.SIDEBAR_TRANSLUCENT ? 0.75f : 1f);
        int channelBg;
        int programBg;
        switch (level) {
            case AppConfig.SIDEBAR_OPAQUE:
                channelBg = 0xFF0D1117;
                programBg = 0xFF080B0F;
                break;
            case AppConfig.SIDEBAR_SEMI_OPAQUE:
                channelBg = 0x990D1117;
                programBg = 0xB3080B0F;
                break;
            default:
                channelBg = 0x33000000;
                programBg = 0x66000000;
                break;
        }
        if (mChannelSection != null) {
            mChannelSection.setBackgroundColor(channelBg);
        }
        if (mProgramListContainer != null) {
            mProgramListContainer.setBackgroundColor(programBg);
        }
        if (mGroupSection != null) {
            // The group column has no colour of its own; at full opacity it needs one, or the video
            // would show through the one panel that is meant to sit furthest from it.
            mGroupSection.setBackgroundColor(level == AppConfig.SIDEBAR_TRANSLUCENT ? 0x00000000
                    : (level == AppConfig.SIDEBAR_OPAQUE ? 0xFF11161D : 0x9911161D));
        }
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

    public void setOnProgramClickListener(OnProgramClickListener listener) {
        mProgramClickListener = listener;
    }

    /** Touch tap on a group row: open that group, the way OK does on the groups column. */
    private void onGroupRowTap(int position) {
        if (position < 0 || position >= mNavigator.groupCount()) {
            return;
        }
        showGroup(position, mSelectedChannelIndex);
        moveFocusToChannels();
    }

    /** Touch tap on a channel row: select and play it straight away. */
    private void onChannelRowTap(int position) {
        selectPosition(position);
        confirmSelection();
    }

    /** Touch tap on a programme row: replay it, the way OK does on the programmes column. */
    private void onProgramRowTap(int position) {
        RecyclerView.Adapter<?> adapter = mProgramRecyclerView.getAdapter();
        if (!(adapter instanceof ProgramListAdapter)) {
            return;
        }
        ProgramListAdapter programAdapter = (ProgramListAdapter) adapter;
        if (position < 0 || position >= programAdapter.getItemCount()) {
            return;
        }
        // Keep the row highlight in sync with what is about to be replayed, then fire the
        // callback so MainActivity can start the catchup stream.
        mSelectedProgramIndex = position;
        programAdapter.setSelectedIndex(position);
        if (mProgramClickListener != null) {
            mProgramClickListener.onProgramClick(getSelectedChannel(),
                    programAdapter.getProgram(position));
        }
    }

    /** Rebuilds both columns after the playlist changed. */
    public void refreshChannels(int currentChannelIndex) {
        mCurrentChannelIndex = currentChannelIndex;
        mGroupAdapter.notifyDataSetChanged();
        showGroup(mNavigator.currentGroupIndex(), currentChannelIndex);
    }

    /**
     * Lists one group and highlights a channel inside it. Browsing a group is only a preview:
     * nothing is played until the viewer confirms a channel.
     *
     * @param channelIndexToSelect flat index to highlight; the first row when it is not in the group
     */
    public void showGroup(int groupIndex, int channelIndexToSelect) {
        ChannelNavigator.Group group = mNavigator.group(groupIndex);
        if (group == null) {
            groupIndex = ChannelNavigator.ALL_GROUP;
            group = mNavigator.group(groupIndex);
            if (group == null) {
                return;
            }
        }
        mBrowsedGroup = groupIndex;
        mChannels = mNavigator.channelsOf(groupIndex);
        mVisibleIndices = new ArrayList<>(group.channels);
        mTitleView.setText(groupLabel(getContext(), group));
        mGroupAdapter.setSelectedGroup(groupIndex);
        scrollToCentered(mGroupRecyclerView, groupIndex, GROUP_ROW_HEIGHT_DP);

        int position = mVisibleIndices.indexOf(channelIndexToSelect);
        if (position < 0) {
            position = 0;
        }
        boolean empty = mChannels.isEmpty();
        mSelectedChannelIndex = empty ? -1 : mVisibleIndices.get(position);
        mAdapter.updateData(mChannels, mVisibleIndices,
                mVisibleIndices.indexOf(mCurrentChannelIndex), empty ? -1 : position);

        mEmptyView.setText(group.favorites ? R.string.favorites_empty : R.string.group_empty);
        mEmptyView.setVisibility(empty ? VISIBLE : GONE);
        mRecyclerView.setVisibility(empty ? GONE : VISIBLE);
        if (empty) {
            hideProgramList();
        } else {
            scrollToCentered(mRecyclerView, position, CHANNEL_ROW_HEIGHT_DP);
            loadAndShowPrograms();
        }
    }

    /** Moves the group highlight, wrapping at the ends, and lists the group it lands on. */
    public void moveGroupSelection(boolean down) {
        int count = mNavigator.groupCount();
        if (count == 0) {
            return;
        }
        int next = ((mBrowsedGroup + (down ? 1 : -1)) % count + count) % count;
        if (next == mBrowsedGroup) {
            return;
        }
        // Land on the playing channel when it is in the group the viewer walked into: it is the
        // row they are most likely looking for.
        showGroup(next, mCurrentChannelIndex);
    }

    /** Moves the channel highlight inside the group being browsed, wrapping at its ends. */
    public void moveChannelSelection(boolean down) {
        if (mChannels.isEmpty()) {
            return;
        }
        int count = mChannels.size();
        int position = mVisibleIndices.indexOf(mSelectedChannelIndex);
        if (position < 0) {
            position = 0;
        }
        selectPosition(((position + (down ? 1 : -1)) % count + count) % count);
    }

    private void selectPosition(int position) {
        if (position < 0 || position >= mVisibleIndices.size()) {
            return;
        }
        int oldPosition = mVisibleIndices.indexOf(mSelectedChannelIndex);
        mSelectedChannelIndex = mVisibleIndices.get(position);
        mAdapter.setSelectedChannel(oldPosition, position);
        scrollToCentered(mRecyclerView, position, CHANNEL_ROW_HEIGHT_DP);
        loadAndShowPrograms();
    }

    private void loadAndShowPrograms() {
        Channel selected = getSelectedChannel();
        if (selected == null) {
            hideProgramList();
            return;
        }
        // Deduplication and throttling live in loadEpg, so asking on every cursor move is cheap.
        if (mEpgManager != null) {
            mEpgManager.loadEpg(selected);
        }
        refreshProgramListForCurrentSelection();
    }

    private void refreshProgramListForCurrentSelection() {
        Channel selectedChannel = getSelectedChannel();
        if (selectedChannel == null || mEpgManager == null || mProgramListContainer == null) {
            return;
        }

        List<EpgManager.Program> programs = mEpgManager.getAllPrograms(selectedChannel);
        if (programs.isEmpty()) {
            hideProgramList();
            return;
        }

        // Days with data for this channel, relative to today, within the rolling 7-day window.
        long todayDay = EpgCache.localDayNumber(System.currentTimeMillis());
        java.util.TreeSet<Integer> available = new java.util.TreeSet<>();
        for (EpgManager.Program program : programs) {
            int offset = (int) (EpgCache.localDayNumber(program.startTime) - todayDay);
            if (offset >= -MAX_DAY_BACK && offset <= MAX_DAY_AHEAD) {
                available.add(offset);
            }
        }
        mAvailableDayOffsets = new ArrayList<>(available);
        if (!mAvailableDayOffsets.contains(mDayOffset)) {
            mDayOffset = nearestAvailableDayOffset();
        }
        List<EpgManager.Program> dayPrograms = filterProgramsForDay(programs, mDayOffset);
        ProgramListAdapter programAdapter = new ProgramListAdapter(getContext(), dayPrograms, mLabelScroller);
        mProgramAdapterRef = programAdapter;
        programAdapter.setRowClickListener(this::onProgramRowTap);
        mProgramRecyclerView.setAdapter(programAdapter);
        mProgramListContainer.setVisibility(VISIBLE);
        mProgramListVisible = true;

        // Land on the programme the viewer is watching — the one airing now, or the one being
        // replayed — but only on the day that moment belongs to. Other days start at the top.
        int selectIndex = 0;
        long focusTime = mProgramFocusTimeMs > 0 ? mProgramFocusTimeMs : System.currentTimeMillis();
        if (mDayOffset == (int) (EpgCache.localDayNumber(focusTime) - todayDay)) {
            selectIndex = nearestProgramIndex(dayPrograms, focusTime);
        }
        mSelectedProgramIndex = selectIndex;
        programAdapter.setSelectedIndex(selectIndex);
        programAdapter.setFocused(mFocusPanel == FocusPanel.PROGRAMS);
        scrollToCentered(mProgramRecyclerView, selectIndex, PROGRAM_ROW_HEIGHT_DP);
        updateProgramTitle();
    }

    /**
     * Points the guide at a moment: the current time while watching live, or the start of the
     * replayed programme during catchup. The day shown follows that moment, so opening the list
     * during a replay lands on the replayed programme rather than on today's schedule.
     */
    public void setProgramFocusTime(long timeMs) {
        mProgramFocusTimeMs = timeMs;
        long today = EpgCache.localDayNumber(System.currentTimeMillis());
        int offset = (int) (EpgCache.localDayNumber(timeMs) - today);
        if (offset >= -MAX_DAY_BACK && offset <= MAX_DAY_AHEAD) {
            mDayOffset = offset;
        }
        refreshProgramListForCurrentSelection();
    }

    /**
     * Index of the programme covering the moment, falling back to the most recent one that has
     * already started.
     *
     * <p>The exact-match-only version left the cursor on the first programme of the day whenever
     * the guide had a gap at the current time (a stale feed, or a channel whose data starts later),
     * which is what viewers saw as "the list opens on the wrong programme".
     */
    static int nearestProgramIndex(List<EpgManager.Program> dayPrograms, long timeMs) {
        int best = 0;
        for (int i = 0; i < dayPrograms.size(); i++) {
            EpgManager.Program program = dayPrograms.get(i);
            if (timeMs >= program.startTime && timeMs < program.endTime) {
                return i;
            }
            if (program.startTime <= timeMs) {
                best = i;
            } else {
                // Programmes are ordered by start time; nothing later can be nearer to the moment.
                break;
            }
        }
        return best;
    }

    /** Programmes whose start falls on the given day (relative to today). */
    private List<EpgManager.Program> filterProgramsForDay(List<EpgManager.Program> all, int offset) {
        long targetDay = EpgCache.localDayNumber(System.currentTimeMillis()) + offset;
        List<EpgManager.Program> dayPrograms = new ArrayList<>();
        for (EpgManager.Program program : all) {
            if (EpgCache.localDayNumber(program.startTime) == targetDay) {
                dayPrograms.add(program);
            }
        }
        return dayPrograms;
    }

    /** Nearest offset to today among the available ones (prefers today itself). */
    private int nearestAvailableDayOffset() {
        if (mAvailableDayOffsets.isEmpty()) {
            return 0;
        }
        if (mAvailableDayOffsets.contains(0)) {
            return 0;
        }
        int best = mAvailableDayOffsets.get(0);
        for (Integer offset : mAvailableDayOffsets) {
            if (Math.abs(offset) < Math.abs(best)) {
                best = offset;
            }
        }
        return best;
    }

    /**
     * Moves the guide to the previous/next day that has data for the selected channel.
     *
     * @return true when the day actually changed, false when there is no day that way (the caller
     *         lets the focus leave the column then, instead of leaving LEFT looking like a dead key)
     */
    public boolean moveProgramDay(int delta) {
        int next = nextDayIndex(mAvailableDayOffsets, mDayOffset, delta);
        if (next < 0) {
            return false;
        }
        mDayOffset = mAvailableDayOffsets.get(next);
        refreshProgramListForCurrentSelection();
        return true;
    }

    /**
     * Index of the day a step would land on, or -1 when the step changes nothing (no days at all,
     * or already at that end). Kept separate from the list handling so the boundary is testable.
     */
    static int nextDayIndex(java.util.List<Integer> availableDays, int currentOffset, int delta) {
        if (availableDays == null || availableDays.isEmpty()) {
            return -1;
        }
        int index = availableDays.indexOf(currentOffset);
        if (index < 0) {
            // The selection is not one of the days on offer (the data moved on); stepping has no
            // meaningful target, so it reports "no move" rather than jumping somewhere.
            return -1;
        }
        int next = Math.max(0, Math.min(availableDays.size() - 1, index + delta));
        return next == index ? -1 : next;
    }

    private void updateProgramTitle() {
        if (mProgramTitleView == null) {
            return;
        }
        if (mDayOffset == 0) {
            mProgramTitleView.setText(R.string.todays_programs);
        } else if (mDayOffset == -1) {
            mProgramTitleView.setText(R.string.yesterday_programs);
        } else {
            java.util.Calendar day = java.util.Calendar.getInstance();
            day.add(java.util.Calendar.DAY_OF_YEAR, mDayOffset);
            mProgramTitleView.setText(
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(day.getTime()));
        }
    }

    private void hideProgramList() {
        if (mProgramListContainer == null) {
            return;
        }
        mProgramListContainer.setVisibility(GONE);
        mProgramListVisible = false;
        if (mFocusPanel == FocusPanel.PROGRAMS) {
            // The panel the cursor was in just disappeared; it cannot stay there.
            moveFocusToChannels();
        }
    }

    public void setCurrentChannel(int channelIndex) {
        mCurrentChannelIndex = channelIndex;
        mAdapter.setCurrentChannel(mVisibleIndices.indexOf(channelIndex));
    }

    public void confirmSelection() {
        Channel selected = getSelectedChannel();
        if (mSelectionListener != null && selected != null) {
            mSelectionListener.onChannelSelected(mSelectedChannelIndex, selected);
        }
    }

    /** @return the highlighted channel's flat index, or -1 when the group being browsed is empty. */
    public int getSelectedChannelIndex() {
        return mSelectedChannelIndex;
    }

    @Nullable
    public Channel getSelectedChannel() {
        int position = mVisibleIndices.indexOf(mSelectedChannelIndex);
        return position >= 0 && position < mChannels.size() ? mChannels.get(position) : null;
    }

    /** @return the programme highlighted in the right-hand column, or null when none is shown. */
    @Nullable
    public EpgManager.Program getSelectedProgram() {
        RecyclerView.Adapter<?> adapter = mProgramRecyclerView.getAdapter();
        if (adapter instanceof ProgramListAdapter) {
            return ((ProgramListAdapter) adapter).getProgram(mSelectedProgramIndex);
        }
        return null;
    }

    public int getBrowsedGroupIndex() {
        return mBrowsedGroup;
    }

    public FocusPanel getFocusPanel() {
        return mFocusPanel;
    }

    /** Re-reads the favourites: the marked channel's star, and the favourites group's contents. */
    public void onFavoritesChanged() {
        ChannelNavigator.Group group = mNavigator.group(mBrowsedGroup);
        if (group != null && group.favorites) {
            // The highlighted row may have just left the list it was standing in.
            showGroup(mBrowsedGroup, mSelectedChannelIndex);
        } else {
            mAdapter.notifyDataSetChanged();
        }
        // The group column shows how many channels each group holds, so it is out of date too
        mGroupAdapter.notifyDataSetChanged();
    }

    /** Unfolds the group column and puts the cursor in it. */
    public void moveFocusToGroups() {
        mFocusPanel = FocusPanel.GROUPS;
        setGroupColumnVisible(true);
        mGroupAdapter.setFocused(true);
        mAdapter.setFocusOnChannels(false);
        setProgramsFocused(false);
    }

    public void moveFocusToPrograms() {
        if (!mProgramListVisible) return;
        mFocusPanel = FocusPanel.PROGRAMS;
        setGroupColumnVisible(false);
        mGroupAdapter.setFocused(false);
        mAdapter.setFocusOnChannels(false);
        setProgramsFocused(true);
        mProgramAdapterRef.rebindAfterLayout(mProgramRecyclerView);
    }

    public void moveFocusToChannels() {
        mFocusPanel = FocusPanel.CHANNELS;
        setGroupColumnVisible(false);
        mGroupAdapter.setFocused(false);
        mAdapter.setFocusOnChannels(true);
        setProgramsFocused(false);
        mAdapter.rebindAfterLayout(mRecyclerView);
    }

    /**
     * The group column is a detour, not a permanent fixture: it folds away again as soon as the
     * cursor leaves it, so the channels stay where the viewer expects them.
     */
    private void setGroupColumnVisible(boolean visible) {
        if (mGroupSection == null || (mGroupSection.getVisibility() == VISIBLE) == visible) {
            return;
        }
        mGroupSection.setVisibility(visible ? VISIBLE : GONE);
        if (visible) {
            scrollToCentered(mGroupRecyclerView, mBrowsedGroup, GROUP_ROW_HEIGHT_DP);
        }
    }

    public boolean isGroupColumnVisible() {
        return mGroupSection != null && mGroupSection.getVisibility() == VISIBLE;
    }

    /** The built-in groups are named by the app; the playlist's own groups name themselves. */
    static String groupLabel(Context context, ChannelNavigator.Group group) {
        if (group.favorites) {
            return "★ " + context.getString(R.string.group_favorites);
        }
        if (group.all) {
            return context.getString(R.string.group_all);
        }
        return group.name;
    }

    private void setProgramsFocused(boolean focused) {
        RecyclerView.Adapter<?> adapter = mProgramRecyclerView.getAdapter();
        if (adapter instanceof ProgramListAdapter) {
            ((ProgramListAdapter) adapter).setFocused(focused);
        }
    }

    /**
     * Moves the cursor inside the programme column.
     *
     * @return true when the cursor moved, false when it was already at that end — the caller then
     *         treats the step as running off the list (which pages the guide to another day)
     */
    public boolean moveProgramSelection(boolean down) {
        RecyclerView.Adapter<?> adapter = mProgramRecyclerView.getAdapter();
        if (mFocusPanel != FocusPanel.PROGRAMS || adapter == null) return false;
        int count = adapter.getItemCount();
        if (count == 0) return false;
        int newIndex = Math.max(0, Math.min(count - 1, mSelectedProgramIndex + (down ? 1 : -1)));
        if (newIndex == mSelectedProgramIndex) return false;
        mSelectedProgramIndex = newIndex;
        ((ProgramListAdapter) adapter).setSelectedIndex(newIndex);
        scrollToCentered(mProgramRecyclerView, newIndex, PROGRAM_ROW_HEIGHT_DP);
        return true;
    }

    /**
     * Scrolls a single-line label while its row is the highlighted one.
     *
     * <p>A name that does not fit is normally cut off, and a viewer across the room cannot do
     * anything about that. The highlighted row — the one the cursor is on — scrolls instead, so the
     * whole text passes by. Only that row moves, and only when there is something hidden: a list
     * where every row scrolls at once is unreadable.
     */
    static void applyMarquee(RowLabelScroller scroller, android.widget.TextView view, boolean scrolling) {
        if (scroller == null) {
            return;
        }
        scroller.attach(view, scrolling);
    }

    /** Puts the cursor on the first or the last programme of the day currently shown. */
    public void selectProgramEdge(boolean first) {
        RecyclerView.Adapter<?> adapter = mProgramRecyclerView.getAdapter();
        if (adapter == null || adapter.getItemCount() == 0) return;
        int index = first ? 0 : adapter.getItemCount() - 1;
        mSelectedProgramIndex = index;
        ((ProgramListAdapter) adapter).setSelectedIndex(index);
        scrollToCentered(mProgramRecyclerView, index, PROGRAM_ROW_HEIGHT_DP);
    }

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        // Nothing to attach: this component is driven by MainActivity, not by the player
    }

    @Nullable
    @Override
    public View getView() {
        return this;
    }

    @Override
    protected void onVisibilityChanged(android.view.View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            // Nothing is being read while the list is away; a running ticker would just redraw.
            mLabelScroller.stop();
        }
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

    // Adapter for the group column
    /** Touch row tap callback: receives the adapter row position that was tapped. */
    interface RowClickCallback {
        void onRowClick(int position);
    }

    public static class GroupListAdapter extends RecyclerView.Adapter<GroupListAdapter.GroupViewHolder> {
        private final Context mContext;
        private final ChannelNavigator mNavigator;
        private int mSelectedIndex = ChannelNavigator.FAVORITES_GROUP;
        private boolean mFocused = false;
        private RowClickCallback mRowClickCallback;

        GroupListAdapter(Context context, ChannelNavigator navigator) {
            mContext = context;
            mNavigator = navigator;
        }

        void setRowClickListener(RowClickCallback callback) {
            mRowClickCallback = callback;
        }

        void setSelectedGroup(int index) {
            if (mSelectedIndex == index) return;
            int oldIndex = mSelectedIndex;
            mSelectedIndex = index;
            notifyItemChanged(oldIndex);
            notifyItemChanged(mSelectedIndex);
        }

        void setFocused(boolean focused) {
            if (mFocused != focused) {
                mFocused = focused;
                notifyDataSetChanged();
            }
        }

        @NonNull
        @Override
        public GroupViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            // Name and count are separate views: the column is narrow, and one TextView holding
            // "全部频道 (155)" wrapped the count onto a second line and stretched the row.
            LinearLayout view = new LinearLayout(mContext);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    dp2px(GROUP_ROW_HEIGHT_DP)));
            view.setPadding(dp2px(12), 0, dp2px(8), 0);
            return new GroupViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull GroupViewHolder holder, int position) {
            ChannelNavigator.Group group = mNavigator.group(position);
            if (group == null) {
                return;
            }
            holder.setGroup(groupLabel(mContext, group), group.channels.size(),
                    position == mSelectedIndex, mFocused);
            holder.itemView.setOnClickListener(v -> {
                if (mRowClickCallback != null) {
                    mRowClickCallback.onRowClick(position);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mNavigator.groupCount();
        }

        static class GroupViewHolder extends RecyclerView.ViewHolder {
            private final LinearLayout container;
            private final TextView nameView;
            private final TextView countView;

            GroupViewHolder(LinearLayout itemView) {
                super(itemView);
                container = itemView;

                // The name may be shortened, the count may not: it is the number the viewer is
                // looking for, so it keeps its own space and never wraps.
                nameView = new TextView(itemView.getContext());
                nameView.setSingleLine(true);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                itemView.addView(nameView, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                countView = new TextView(itemView.getContext());
                countView.setSingleLine(true);
                countView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                // Sized by its content: a fixed column would take the width the name needs, and
                // "全部频道" would end up as "全部…".
                countView.setPadding((int) (6 * itemView.getResources()
                        .getDisplayMetrics().density + 0.5f), 0, 0, 0);
                itemView.addView(countView, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            void setGroup(String label, int channelCount, boolean isSelected, boolean focused) {
                nameView.setText(label);
                countView.setText("(" + channelCount + ")");
                int nameColor;
                int countColor;
                if (isSelected && focused) {
                    nameColor = 0xFFFF6B35;
                    countColor = 0xFFFF9966;
                    // The focused row keeps the same size: growing it was what pushed
                    // "★ 我的收藏" past the narrow column and into an ellipsis.
                    nameView.setTextSize(16);
                    countView.setTextSize(13);
                    container.setBackgroundResource(R.drawable.channel_item_selected_bg);
                } else if (isSelected) {
                    nameColor = 0xFFFF9966;
                    countColor = 0xFFFF9966;
                    nameView.setTextSize(16);
                    countView.setTextSize(12);
                    container.setBackgroundResource(R.drawable.channel_item_selected_dim_bg);
                } else {
                    nameColor = 0xFFCCCCCC;
                    countColor = 0xFF8892A0;
                    nameView.setTextSize(16);
                    countView.setTextSize(12);
                    container.setBackgroundColor(0x00000000);
                }
                nameView.setTextColor(nameColor);
                countView.setTextColor(countColor);
            }
        }

        private int dp2px(int dp) {
            return Math.round(mContext.getResources().getDisplayMetrics().density * dp);
        }
    }

    // Adapter for channel list
    public static class ChannelListAdapter extends RecyclerView.Adapter<ChannelListAdapter.ChannelViewHolder> {
        private final Context mContext;
        private final ChannelNavigator mNavigator;
        private List<Channel> mChannels = new ArrayList<>();
        /** Flat playlist index of each row, which is also the number the viewer can type. */
        private List<Integer> mChannelNumbers = new ArrayList<>();
        private int mCurrentIndex = -1;
        private int mSelectedIndex = 0;
        private boolean mFocusOnChannels = true;
        private EpgManager mEpgManager;
        private RowClickCallback mRowClickCallback;

        private final RowLabelScroller mScroller;

        public ChannelListAdapter(Context context, ChannelNavigator navigator,
                                  RowLabelScroller scroller) {
            mScroller = scroller;
            mContext = context;
            mNavigator = navigator;
        }

        public void setRowClickListener(RowClickCallback callback) {
            mRowClickCallback = callback;
        }

        public void setEpgManager(EpgManager epgManager) {
            mEpgManager = epgManager;
            notifyDataSetChanged();
        }

        public void updateData(List<Channel> channels, List<Integer> flatIndices,
                               int currentPosition, int selectedPosition) {
            mChannels = new ArrayList<>(channels);
            mChannelNumbers = new ArrayList<>(flatIndices);
            mCurrentIndex = currentPosition;
            mSelectedIndex = selectedPosition;
            notifyDataSetChanged();
        }

        public void setCurrentChannel(int position) {
            int oldIndex = mCurrentIndex;
            mCurrentIndex = position;
            if (oldIndex >= 0) {
                notifyItemChanged(oldIndex);
            }
            if (mCurrentIndex >= 0) {
                notifyItemChanged(mCurrentIndex);
            }
        }

        public void setSelectedChannel(int oldPosition, int newPosition) {
            mSelectedIndex = newPosition;
            if (oldPosition >= 0) {
                notifyItemChanged(oldPosition);
            }
            if (newPosition >= 0) {
                notifyItemChanged(newPosition);
            }
        }

        /** Re-runs the bind pass on the next frame, so the subtitle marquee starts once the rows
         *  know how wide they are. */
        void rebindAfterLayout(android.view.ViewGroup list) {
            list.post(() -> notifyDataSetChanged());
        }

        public void setFocusOnChannels(boolean focus) {
            if (mFocusOnChannels != focus) {
                mFocusOnChannels = focus;
                notifyDataSetChanged();
            }
        }

        @NonNull
        @Override
        public ChannelViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            LinearLayout view = new LinearLayout(mContext);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    dp2px(CHANNEL_ROW_HEIGHT_DP)));
            view.setPadding(dp2px(8), dp2px(8), dp2px(8), dp2px(8));
            return new ChannelViewHolder(view, mContext, mScroller);
        }

        @Override
        public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
            Channel channel = mChannels.get(position);
            int number = position < mChannelNumbers.size() ? mChannelNumbers.get(position) + 1 : 0;
            holder.setChannel(channel, number, position == mSelectedIndex, position == mCurrentIndex,
                    mEpgManager, mFocusOnChannels,
                    mNavigator != null && mNavigator.isFavorite(channel));
            holder.itemView.setOnClickListener(v -> {
                if (mRowClickCallback != null) {
                    mRowClickCallback.onRowClick(position);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mChannels.size();
        }

        static class ChannelViewHolder extends RecyclerView.ViewHolder {
            private final LinearLayout container;
            private final TextView numberView;
            private final ImageView logoView;
            private final LinearLayout textContainer;
            private final TextView nameView;
            private final TextView epgView;
            private final TextView favoriteView;
            private final Context context;

            private final RowLabelScroller mLabelScroller;

            ChannelViewHolder(LinearLayout itemView, Context context, RowLabelScroller scroller) {
                super(itemView);
                mLabelScroller = scroller;
                this.context = context;
                this.container = itemView;
                container.setOrientation(LinearLayout.HORIZONTAL);

                // The number the viewer can type on the remote to reach this channel
                numberView = new TextView(context);
                numberView.setTextColor(0xFF888888);
                numberView.setTextSize(12);
                numberView.setSingleLine(true);
                numberView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                numberView.setLayoutParams(new LinearLayout.LayoutParams(
                        dp2px(26),
                        LinearLayout.LayoutParams.MATCH_PARENT));
                container.addView(numberView);

                // Logo
                logoView = new ImageView(context);
                logoView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                        dp2px(36),
                        dp2px(36));
                logoParams.leftMargin = dp2px(6);
                logoView.setLayoutParams(logoParams);
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
                nameView.setTextSize(16);
                nameView.setSingleLine(true);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                nameView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                textContainer.addView(nameView);

                epgView = new TextView(context);
                epgView.setTextColor(0xFF888888);
                epgView.setTextSize(13);
                epgView.setMaxLines(1);
                epgView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                epgView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                textContainer.addView(epgView);

                container.addView(textContainer);

                favoriteView = new TextView(context);
                favoriteView.setText("★");
                favoriteView.setTextColor(0xFFFFC04D);
                favoriteView.setTextSize(13);
                favoriteView.setGravity(Gravity.CENTER_VERTICAL);
                favoriteView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT));
                container.addView(favoriteView);
            }

                /** Whether this holder's subtitle should be scrolling right now. */
                private boolean mEpgMarqueeWanted;

                /**
                 * Starts or stops the subtitle marquee, and re-applies it once the row has been
                 * measured: a marquee started before the view knows its width never runs, which is
                 * what left a truncated subtitle sitting still.
                 */
                private void applyEpgMarquee(boolean wanted) {
                    mEpgMarqueeWanted = wanted;
                    applyMarquee(mLabelScroller, epgView, wanted);
                }

            void setChannel(Channel channel, int number, boolean isSelected, boolean isCurrent,
                            EpgManager epgManager, boolean focusOnChannels, boolean isFavorite) {
                nameView.setText(channel.name);
                numberView.setText(number > 0 ? String.valueOf(number) : "");
                favoriteView.setVisibility(isFavorite ? View.VISIBLE : View.GONE);

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
                    nameView.setTextSize(17);
                    container.setBackgroundResource(R.drawable.channel_item_selected_bg);
                    container.animate()
                            .scaleX(1.04f)
                            .scaleY(1.04f)
                            .setDuration(150)
                            .start();
                } else if (isSelected && !focusOnChannels) {
                    // Selected but focus on another panel - dimmer highlight
                    nameView.setTextColor(0xFFFF9966);
                    nameView.setTextSize(16);
                    container.setBackgroundResource(R.drawable.channel_item_selected_dim_bg);
                    container.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(150)
                            .start();
                } else if (isCurrent) {
                    nameView.setTextColor(0xFFFFFFFF);
                    nameView.setTextSize(16);
                    container.setBackgroundColor(0x00000000);
                    container.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(150)
                            .start();
                } else {
                    nameView.setTextColor(0xFFCCCCCC);
                    nameView.setTextSize(16);
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
                        // The programme name under the highlighted channel scrolls when it does not
                        // fit, which is the row the viewer is reading.
                        applyEpgMarquee(isSelected && focusOnChannels);
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
        private RowClickCallback mRowClickCallback;

        private final RowLabelScroller mScroller;

        public ProgramListAdapter(Context context, List<EpgManager.Program> programs,
                                  RowLabelScroller scroller) {
            mScroller = scroller;
            mContext = context;
            mPrograms = new ArrayList<>(programs);
        }

        void setRowClickListener(RowClickCallback callback) {
            mRowClickCallback = callback;
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

        /** Re-runs the bind pass on the next frame, so the subtitle marquee starts once the rows
         *  know how wide they are. */
        void rebindAfterLayout(android.view.ViewGroup list) {
            list.post(() -> notifyDataSetChanged());
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
            return new ProgramViewHolder(view, mContext, mScroller);
        }

        @Override
        public void onBindViewHolder(@NonNull ProgramViewHolder holder, int position) {
            EpgManager.Program program = mPrograms.get(position);
            holder.setProgram(program, position == mSelectedIndex, mFocused);
            holder.itemView.setOnClickListener(v -> {
                if (mRowClickCallback != null) {
                    mRowClickCallback.onRowClick(position);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mPrograms.size();
        }

        @Nullable
        EpgManager.Program getProgram(int index) {
            return index >= 0 && index < mPrograms.size() ? mPrograms.get(index) : null;
        }

        static class ProgramViewHolder extends RecyclerView.ViewHolder {
            private final LinearLayout container;
            private final TextView timeView;
            private final TextView titleView;
            private final Context context;

            private final RowLabelScroller mLabelScroller;

            ProgramViewHolder(LinearLayout itemView, Context context, RowLabelScroller scroller) {
                super(itemView);
                mLabelScroller = scroller;
                this.context = context;
                this.container = itemView;

                timeView = new TextView(context);
                timeView.setTextColor(ProgramViewHolder.TIME_NORMAL);
                timeView.setTextSize(12);
                timeView.setSingleLine(true);
                timeView.setLayoutParams(new LinearLayout.LayoutParams(
                        // Wide enough for the longest stamp at this size ("23:23-23:36"); the column
                        // is fixed so the titles of every row line up.
                        dp2px(80),
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                container.addView(timeView);

                titleView = new TextView(context);
                titleView.setTextColor(0xFFCCCCCC);
                titleView.setTextSize(14);
                titleView.setMaxLines(1);
                titleView.setLayoutParams(new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1.0f));
                // Tighter than the leading gap used elsewhere: the time and the title belong
                // together, and the column above already leaves a little slack of its own.
                titleView.setPadding(dp2px(4), 0, 0, 0);
                container.addView(titleView);
            }

            /** Whether this holder's title should be scrolling right now. */
            private boolean mTitleMarqueeWanted;

            /** See {@code ChannelViewHolder.applyEpgMarquee}; the guide rows need the same. */
            private void applyTitleMarquee(boolean wanted) {
                mTitleMarqueeWanted = wanted;
                applyMarquee(mLabelScroller, titleView, wanted);
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

                // A title that does not fit scrolls while this row is the highlighted one; every
                // other row keeps the ellipsis.
                applyTitleMarquee(isSelected && focused);

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

                // The time is coloured on every bind, not only when the programme is current: rows
                // come from a recycled pool, so a view that once showed the programme airing now
                // would keep the highlight for whatever it is bound to next — which is how several
                // rows in one guide ended up looking "current" at once.
                timeView.setTextColor(isCurrentProgram ? TIME_CURRENT : TIME_NORMAL);
            }

            /** Colour of a time stamp: the programme on air is picked out; the rest stay quiet. */
            static final int TIME_CURRENT = 0xFFFF9966;
            static final int TIME_NORMAL = 0xFF888888;

            private int dp2px(int dp) {
                return Math.round(context.getResources().getDisplayMetrics().density * dp);
            }
        }

        private int dp2px(int dp) {
            return Math.round(mContext.getResources().getDisplayMetrics().density * dp);
        }
    }
}
