package com.cabletv.player.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.cabletv.player.R;

/**
 * The digits typed on the remote, shown top-right the way a TV does, with the name of the
 * channel they point at so the user can see what is about to be tuned.
 */
public class ChannelNumberView extends LinearLayout {
    private final TextView mDigitsView;
    private final TextView mNameView;

    public ChannelNumberView(@NonNull Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.bg_playback_status);
        setPadding(dp2px(20), dp2px(12), dp2px(20), dp2px(12));
        setVisibility(GONE);

        mDigitsView = new TextView(context);
        mDigitsView.setTextColor(0xFFFFFFFF);
        mDigitsView.setTextSize(40);
        mDigitsView.setGravity(Gravity.CENTER);
        addView(mDigitsView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        mNameView = new TextView(context);
        mNameView.setTextColor(0xFFFF9966);
        mNameView.setTextSize(14);
        mNameView.setMaxLines(1);
        mNameView.setGravity(Gravity.CENTER);
        addView(mNameView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /** Layout params for adding this view to a FrameLayout, in the top-right corner. */
    public FrameLayout.LayoutParams createTopEndLayoutParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = dp2px(24);
        lp.rightMargin = dp2px(24);
        return lp;
    }

    /**
     * @param digits      what the user has typed so far
     * @param channelName the channel that number resolves to, or null when it resolves to none
     */
    public void showDigits(String digits, String channelName) {
        mDigitsView.setText(digits);
        if (channelName == null || channelName.isEmpty()) {
            mNameView.setText(getContext().getString(R.string.channel_number_unknown));
            mNameView.setTextColor(0xFF999999);
        } else {
            mNameView.setText(channelName);
            mNameView.setTextColor(0xFFFF9966);
        }
        setVisibility(VISIBLE);
        bringToFront();
    }

    public void hide() {
        setVisibility(GONE);
    }

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }
}
