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
 * The programme column: the guide for the selected channel, by day.
 *
 * <p>A file of its own rather than a nested class: the list component was 1492 lines, and the
 * three adapters account for about 600 of them without sharing any state with it.
 */
public class ProgramListAdapter extends RecyclerView.Adapter<ProgramListAdapter.ProgramViewHolder> {
    private final Context mContext;
    private final List<EpgManager.Program> mPrograms;
    private Channel mChannel;
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

    public void setChannel(Channel channel) {
        mChannel = channel;
        notifyDataSetChanged();
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
        view.setGravity(Gravity.CENTER_VERTICAL);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                dp2px(ChannelListComponent.PROGRAM_ROW_HEIGHT_DP));
        lp.setMargins(dp2px(6), dp2px(2), dp2px(6), dp2px(2));
        view.setLayoutParams(lp);
        view.setPadding(dp2px(10), dp2px(4), dp2px(10), dp2px(4));
        return new ProgramViewHolder(view, mContext, mScroller);
    }

    @Override
    public void onBindViewHolder(@NonNull ProgramViewHolder holder, int position) {
        EpgManager.Program program = mPrograms.get(position);
        holder.setProgram(program, position == mSelectedIndex, mFocused, mChannel);
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
        private final TextView liveBadgeView;
        private final TextView catchupBadgeView;
        private final Context context;

        private final RowLabelScroller mLabelScroller;

        ProgramViewHolder(LinearLayout itemView, Context context, RowLabelScroller scroller) {
            super(itemView);
            mLabelScroller = scroller;
            this.context = context;
            this.container = itemView;
            container.setGravity(Gravity.CENTER_VERTICAL);

            timeView = new TextView(context);
            timeView.setTextColor(ProgramViewHolder.TIME_NORMAL);
            timeView.setTextSize(12);
            timeView.setSingleLine(true);
            timeView.setLayoutParams(new LinearLayout.LayoutParams(
                    dp2px(78),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            container.addView(timeView);

            titleView = new TextView(context);
            titleView.setTextColor(0xFFE2E8F0);
            titleView.setTextSize(14);
            titleView.setMaxLines(1);
            titleView.setLayoutParams(new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.0f));
            titleView.setPadding(dp2px(4), 0, dp2px(6), 0);
            container.addView(titleView);

            liveBadgeView = new TextView(context);
            liveBadgeView.setText("LIVE");
            liveBadgeView.setTextColor(0xFF00E676);
            liveBadgeView.setTextSize(10);
            liveBadgeView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            liveBadgeView.setBackgroundResource(R.drawable.bg_badge_live);
            liveBadgeView.setPadding(dp2px(6), dp2px(1), dp2px(6), dp2px(1));
            liveBadgeView.setVisibility(View.GONE);
            container.addView(liveBadgeView);

            catchupBadgeView = new TextView(context);
            catchupBadgeView.setText("回看 ↺");
            catchupBadgeView.setTextColor(0xFFFF7A00);
            catchupBadgeView.setTextSize(10);
            catchupBadgeView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            catchupBadgeView.setBackgroundResource(R.drawable.bg_badge_catchup);
            catchupBadgeView.setPadding(dp2px(6), dp2px(1), dp2px(6), dp2px(1));
            catchupBadgeView.setVisibility(View.GONE);
            container.addView(catchupBadgeView);
        }

        /** Whether this holder's title should be scrolling right now. */
        private boolean mTitleMarqueeWanted;

        /** See {@code ChannelViewHolder.applyEpgMarquee}; the guide rows need the same. */
        private void applyTitleMarquee(boolean wanted) {
            mTitleMarqueeWanted = wanted;
            ChannelListComponent.applyMarquee(mLabelScroller, titleView, wanted);
        }

        void setProgram(EpgManager.Program program, boolean isSelected, boolean focused) {
            setProgram(program, isSelected, focused, null);
        }

        void setProgram(EpgManager.Program program, boolean isSelected, boolean focused, @Nullable Channel channel) {
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

            // A title that does not fit scrolls while this row is the highlighted one
            applyTitleMarquee(isSelected && focused);

            // Highlight current/next program based on wall-clock time
            long currentTime = System.currentTimeMillis();
            boolean isCurrentProgram = currentTime >= program.startTime && currentTime < program.endTime;
            boolean isPastProgram = program.endTime <= currentTime;

            if (isCurrentProgram) {
                liveBadgeView.setVisibility(View.VISIBLE);
                catchupBadgeView.setVisibility(View.GONE);
            } else if (isPastProgram && channel != null && channel.hasCatchup() && AppConfig.isCatchupBadgeEnabled()) {
                liveBadgeView.setVisibility(View.GONE);
                catchupBadgeView.setVisibility(View.VISIBLE);
            } else {
                liveBadgeView.setVisibility(View.GONE);
                catchupBadgeView.setVisibility(View.GONE);
            }

            // Apply cursor highlight if selected
            if (isSelected && focused) {
                container.setBackgroundResource(R.drawable.channel_item_selected_bg);
                titleView.setTextColor(0xFFFFFFFF);
                container.animate()
                        .scaleX(1.03f)
                        .scaleY(1.03f)
                        .setDuration(150)
                        .start();
            } else if (isSelected && !focused) {
                // Selected but focus not on program list
                container.setBackgroundResource(R.drawable.channel_item_selected_dim_bg);
                titleView.setTextColor(0xFF66E5FF);
                container.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(150)
                        .start();
            } else {
                container.setBackgroundColor(0x00000000);
                titleView.setTextColor(0xFFE2E8F0);
                container.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(150)
                        .start();
            }

            // The time is coloured on every bind, not only when the programme is current
            timeView.setTextColor(isCurrentProgram ? TIME_CURRENT : TIME_NORMAL);
        }

        /** Colour of a time stamp: the programme on air is picked out; the rest stay quiet. */
        static final int TIME_CURRENT = 0xFF00E5FF;
        static final int TIME_NORMAL = 0xFF94A3B8;

        private int dp2px(int dp) {
            return Math.round(context.getResources().getDisplayMetrics().density * dp);
        }
    }

    private int dp2px(int dp) {
        return Math.round(mContext.getResources().getDisplayMetrics().density * dp);
    }
}
