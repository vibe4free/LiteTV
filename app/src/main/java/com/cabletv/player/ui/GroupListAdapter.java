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
 * The group column: one row per group, with its channel count.
 *
 * <p>A file of its own rather than a nested class: the list component was 1492 lines, and the
 * three adapters account for about 600 of them without sharing any state with it.
 */
public class GroupListAdapter extends RecyclerView.Adapter<GroupListAdapter.GroupViewHolder> {
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
                dp2px(ChannelListComponent.GROUP_ROW_HEIGHT_DP)));
        view.setPadding(dp2px(12), 0, dp2px(8), 0);
        return new GroupViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull GroupViewHolder holder, int position) {
        ChannelNavigator.Group group = mNavigator.group(position);
        if (group == null) {
            return;
        }
        holder.setGroup(ChannelListComponent.groupLabel(mContext, group), group.channels.size(),
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
