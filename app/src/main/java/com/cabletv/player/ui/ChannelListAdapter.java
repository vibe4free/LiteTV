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
 * The channel column: numbers, names, the programme on air and its next.
 *
 * <p>A file of its own rather than a nested class: the list component was 1492 lines, and the
 * three adapters account for about 600 of them without sharing any state with it.
 */
public class ChannelListAdapter extends RecyclerView.Adapter<ChannelListAdapter.ChannelViewHolder> {
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
                dp2px(ChannelListComponent.CHANNEL_ROW_HEIGHT_DP)));
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
                ChannelListComponent.applyMarquee(mLabelScroller, epgView, wanted);
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
