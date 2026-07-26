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

import com.cabletv.player.config.AppConfig;
import com.cabletv.player.epg.EpgManager;
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
    private int mSelectedChannelIndex = 0;
    private OnChannelSelectedListener mSelectionListener;
    private EpgManager mEpgManager;

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

    public void setEpgManager(EpgManager epgManager) {
        mEpgManager = epgManager;
        mAdapter.setEpgManager(epgManager);
    }

    public void setOnChannelSelectedListener(OnChannelSelectedListener listener) {
        mSelectionListener = listener;
    }

    public void updateChannels(List<Channel> channels, int currentIndex) {
        mChannels = new ArrayList<>(channels);
        mCurrentChannelIndex = currentIndex;
        mSelectedChannelIndex = currentIndex;
        mAdapter.updateData(mChannels, currentIndex, currentIndex);
        mRecyclerView.scrollToPosition(currentIndex);
    }

    public void setCurrentChannel(int index) {
        mCurrentChannelIndex = index;
        mAdapter.setCurrentChannel(index);
        mRecyclerView.scrollToPosition(index);
    }

    public void selectChannel(int index) {
        if (index < 0 || index >= mChannels.size()) return;
        int oldIndex = mSelectedChannelIndex;
        mSelectedChannelIndex = index;
        mAdapter.setSelectedChannel(oldIndex, index);
        mRecyclerView.scrollToPosition(index);
    }

    public void confirmSelection() {
        if (mSelectionListener != null && mSelectedChannelIndex < mChannels.size()) {
            mSelectionListener.onChannelSelected(mSelectedChannelIndex, mChannels.get(mSelectedChannelIndex));
        }
    }

    public int getSelectedChannelIndex() {
        return mSelectedChannelIndex;
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

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }

    // Adapter for channel list
    public static class ChannelListAdapter extends RecyclerView.Adapter<ChannelListAdapter.ChannelViewHolder> {
        private final Context mContext;
        private List<Channel> mChannels;
        private int mCurrentIndex = 0;
        private int mSelectedIndex = 0;
        private EpgManager mEpgManager;

        public ChannelListAdapter(Context context, List<Channel> channels) {
            mContext = context;
            mChannels = new ArrayList<>(channels);
        }

        public void setEpgManager(EpgManager epgManager) {
            mEpgManager = epgManager;
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
            notifyItemChanged(oldIndex);
            notifyItemChanged(newIndex);
        }

        @NonNull
        @Override
        public ChannelViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            LinearLayout view = new LinearLayout(mContext);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    dp2px(60)));
            view.setPadding(dp2px(12), dp2px(8), dp2px(12), dp2px(8));
            return new ChannelViewHolder(view, mContext);
        }

        @Override
        public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
            Channel channel = mChannels.get(position);
            holder.setChannel(channel, position == mSelectedIndex, position == mCurrentIndex, mEpgManager);
        }

        @Override
        public int getItemCount() {
            return mChannels.size();
        }

        static class ChannelViewHolder extends RecyclerView.ViewHolder {
            private final LinearLayout container;
            private final TextView nameView;
            private final TextView epgView;
            private final Context context;

            ChannelViewHolder(LinearLayout itemView, Context context) {
                super(itemView);
                this.context = context;
                this.container = itemView;

                nameView = new TextView(context);
                nameView.setTextColor(0xFFCCCCCC);
                nameView.setTextSize(14);
                nameView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                container.addView(nameView);

                epgView = new TextView(context);
                epgView.setTextColor(0xFF888888);
                epgView.setTextSize(12);
                epgView.setMaxLines(1);
                epgView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                container.addView(epgView);
            }

            void setChannel(Channel channel, boolean isSelected, boolean isCurrent, EpgManager epgManager) {
                nameView.setText(channel.name);

                if (isSelected) {
                    nameView.setTextColor(0xFFFF6B35);
                    nameView.setTextSize(15);
                    container.setBackgroundColor(0x33FF6B35);
                } else if (isCurrent) {
                    nameView.setTextColor(0xFFFFFFFF);
                    nameView.setTextSize(14);
                    container.setBackgroundColor(0x00000000);
                } else {
                    nameView.setTextColor(0xFFCCCCCC);
                    nameView.setTextSize(14);
                    container.setBackgroundColor(0x00000000);
                }

                if (epgManager != null) {
                    String epgInfo = epgManager.getCurrentProgramInfo(channel);
                    epgView.setText(epgInfo != null ? epgInfo : "No EPG");
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
