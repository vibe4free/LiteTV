package com.cabletv.player.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;

/**
 * A switch drawn the way Apple TV draws it: a pill that lights up in the accent colour.
 *
 * <p>The platform check box is a small square on the left of its label, which reads as a form
 * field rather than as a switch, and on a television at three metres it is the hardest thing on
 * the screen to see. This one is a full-width pill, and it grows a little when it takes the
 * focus so the viewer can see where the remote is pointing.
 *
 * <p>It answers the remote as well as the finger: OK (or ENTER) flips it, exactly like the
 * check box it replaces.
 */
public class ToggleSwitchView extends View {

    /** Told whenever the switch is flipped, whoever flipped it. */
    public interface OnCheckedChangeListener {
        void onCheckedChanged(ToggleSwitchView view, boolean isChecked);
    }

    private static final int TRACK_HEIGHT_DP = 26;
    private static final int TRACK_WIDTH_DP = 44;
    private static final int KNOB_MARGIN_DP = 3;
    private static final int KNOB_SIZE_DP = 20;
    private static final float FOCUS_SCALE = 1.08f;

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKnobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFocusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTrackRect = new RectF();
    private final RectF mKnobRect = new RectF();
    private final RectF mFocusRect = new RectF();

    private boolean mChecked;
    private OnCheckedChangeListener mListener;
    private float mScale = 1f;

    public ToggleSwitchView(Context context) {
        this(context, null);
    }

    public ToggleSwitchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setClickable(true);
        mTrackPaint.setColor(Color.parseColor("#3A3A3C"));
        mKnobPaint.setColor(Color.WHITE);
        mFocusPaint.setStyle(Paint.Style.STROKE);
        mFocusPaint.setStrokeWidth(dp2px(2));
        // White, not the accent colour: the track is already orange when the switch is on, and a
        // ring in the same colour would be invisible exactly when it matters most.
        mFocusPaint.setColor(Color.WHITE);
    }

    public boolean isChecked() {
        return mChecked;
    }

    public void setChecked(boolean checked) {
        if (mChecked == checked) {
            return;
        }
        mChecked = checked;
        invalidate();
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        mListener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(dp2px(TRACK_WIDTH_DP), dp2px(TRACK_HEIGHT_DP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        float pivotX = getWidth() / 2f;
        float pivotY = getHeight() / 2f;
        canvas.scale(mScale, mScale, pivotX, pivotY);

        float inset = dp2px(1);
        mTrackRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        mTrackPaint.setColor(mChecked
                ? Color.parseColor("#FF6B35")
                : Color.parseColor("#3A3A3C"));
        canvas.drawRoundRect(mTrackRect, getHeight() / 2f, getHeight() / 2f, mTrackPaint);

        float knobSize = dp2px(KNOB_SIZE_DP);
        float margin = dp2px(KNOB_MARGIN_DP);
        float left = mChecked ? mTrackRect.right - margin - knobSize : mTrackRect.left + margin;
        mKnobRect.set(left, mTrackRect.top + margin, left + knobSize,
                mTrackRect.top + margin + knobSize);
        canvas.drawOval(mKnobRect, mKnobPaint);

        if (isFocused()) {
            // A ring just outside the track, so it stays visible on both the on and the off state.
            float grow = dp2px(3);
            mFocusRect.set(mTrackRect.left - grow, mTrackRect.top - grow,
                    mTrackRect.right + grow, mTrackRect.bottom + grow);
            canvas.drawRoundRect(mFocusRect, mFocusRect.height() / 2f, mFocusRect.height() / 2f,
                    mFocusPaint);
        }
        canvas.restoreToCount(save);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        animateScale(gainFocus ? FOCUS_SCALE : 1f);
    }

    private void animateScale(float target) {
        if (Math.abs(mScale - target) < 0.001f) {
            mScale = target;
            invalidate();
            return;
        }
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(mScale, target);
        animator.setDuration(120L);
        animator.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                mScale = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        mChecked = !mChecked;
        invalidate();
        if (mListener != null) {
            mListener.onCheckedChanged(this, mChecked);
        }
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // The remote's OK key: the switch has to answer it, like the check box it replaces.
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_SPACE) {
            performClick();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private int dp2px(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }
}
