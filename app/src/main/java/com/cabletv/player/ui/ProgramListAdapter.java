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
                dp2px(ChannelListComponent.PROGRAM_ROW_HEIGHT_DP)));
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
            ChannelListComponent.applyMarquee(mLabelScroller, titleView, wanted);
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
