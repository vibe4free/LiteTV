package com.cabletv.player.ui;

import android.content.Context;
import android.view.View;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;
import com.cabletv.player.model.Channel;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;

import java.util.ArrayList;
import java.util.List;

public class ChannelListComponent extends FrameLayout implements IControlComponent {
    private final ControlWrapper mControlWrapper;
    private final ChannelListAdapter mAdapter;
    private boolean mIsVisible = false;
    private RecyclerView mRecyclerView;
    private List<Channel> mChannels = new ArrayList<>();
    private int mCurrentChannelIndex = 0;

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
        setLayoutParams(new LayoutParams(dp2px(240), LayoutParams.MATCH_PARENT));
        setBackgroundColor(0xCC000000);
        setAlpha(AppConfig.getSidebarAlpha());

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        TextView titleView = new TextView(context);
        titleView.setText("Channels");
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(16);
        titleView.setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12));
        titleView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        container.addView(titleView);

        mRecyclerView = new RecyclerView(context);
        mRecyclerView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1.0f));
        mRecyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        mRecyclerView.setBackgroundColor(0x00000000);
        container.addView(mRecyclerView);

        addView(container);
    }

    public void updateChannels(List<Channel> channels, int currentIndex) {
        mChannels = new ArrayList<>(channels);
        mCurrentChannelIndex = currentIndex;
        mAdapter.updateData(mChannels, currentIndex);
        mRecyclerView.scrollToPosition(currentIndex);
    }

    public void setCurrentChannel(int index) {
        mCurrentChannelIndex = index;
        mAdapter.setCurrentChannel(index);
        mRecyclerView.scrollToPosition(index);
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
        setVisibility(isVisible ? VISIBLE : GONE);
        if (anim != null) {
            startAnimation(anim);
        }
    }

    @Override
    public void onPlayStateChanged(int playState) {
        // Not needed for channel list
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
        // Not needed for channel list
    }

    @Override
    public void setProgress(int duration, int position) {
        // Not needed for channel list
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
        // Not needed for channel list
    }

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }

    // Adapter for channel list
    public static class ChannelListAdapter extends RecyclerView.Adapter<ChannelListAdapter.ChannelViewHolder> {
        private final Context mContext;
        private List<Channel> mChannels;
        private int mCurrentIndex = 0;

        public ChannelListAdapter(Context context, List<Channel> channels) {
            mContext = context;
            mChannels = new ArrayList<>(channels);
        }

        public void updateData(List<Channel> channels, int currentIndex) {
            mChannels = new ArrayList<>(channels);
            mCurrentIndex = currentIndex;
            notifyDataSetChanged();
        }

        public void setCurrentChannel(int index) {
            int oldIndex = mCurrentIndex;
            mCurrentIndex = index;
            notifyItemChanged(oldIndex);
            notifyItemChanged(mCurrentIndex);
        }

        @NonNull
        @Override
        public ChannelViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            TextView view = new TextView(mContext);
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    dp2px(48)));
            view.setTextColor(0xFFCCCCCC);
            view.setTextSize(14);
            view.setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(8));
            return new ChannelViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
            Channel channel = mChannels.get(position);
            holder.textView.setText(channel.name);
            if (position == mCurrentIndex) {
                holder.textView.setTextColor(0xFFFF6B35);
                holder.textView.setTextSize(15);
            } else {
                holder.textView.setTextColor(0xFFCCCCCC);
                holder.textView.setTextSize(14);
            }
        }

        @Override
        public int getItemCount() {
            return mChannels.size();
        }

        static class ChannelViewHolder extends RecyclerView.ViewHolder {
            TextView textView;

            ChannelViewHolder(TextView itemView) {
                super(itemView);
                textView = itemView;
            }
        }

        private int dp2px(int dp) {
            return Math.round(mContext.getResources().getDisplayMetrics().density * dp);
        }
    }
}
