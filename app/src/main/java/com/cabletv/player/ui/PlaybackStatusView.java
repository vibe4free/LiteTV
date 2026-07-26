package com.cabletv.player.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.cabletv.player.R;

/**
 * Centred one-line message shown over the video: buffering, reconnect progress, or a
 * playback failure. Without it a dead stream just looks like a black screen.
 */
public class PlaybackStatusView extends TextView {

    public PlaybackStatusView(@NonNull Context context) {
        super(context);
        setBackgroundResource(R.drawable.bg_playback_status);
        setTextColor(0xFFFFFFFF);
        setTextSize(15);
        setGravity(Gravity.CENTER);
        setPadding(dp2px(20), dp2px(12), dp2px(20), dp2px(12));
        setVisibility(GONE);
    }

    /** Layout params for adding this view to a FrameLayout, centred on screen. */
    public FrameLayout.LayoutParams createCenteredLayoutParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    public void showMessage(CharSequence message) {
        setText(message);
        setVisibility(VISIBLE);
        bringToFront();
    }

    public void hideMessage() {
        setText("");
        setVisibility(GONE);
    }

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }
}
