package com.cabletv.player.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.EditText;

/**
 * A text field that lets the settings panel see BACK while the keyboard is up.
 *
 * <p>Normally the input method takes BACK first to close itself, and the activity never learns
 * the key was pressed: the field stayed in its editing state, so the panel needed a second press
 * to close. {@link #onKeyPreIme} runs before the input method does, which makes one press close
 * the keyboard and leave the field at the same time.
 */
public class SettingsEditText extends EditText {

    /** Told when BACK is pressed while this field has the keyboard. */
    public interface OnBackPressed {
        void onBackPressed();
    }

    private OnBackPressed mOnBackPressed;

    public SettingsEditText(Context context) {
        super(context);
    }

    public SettingsEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SettingsEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnBackPressed(OnBackPressed listener) {
        mOnBackPressed = listener;
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Consume both the press and the release: returning false would let the input method
            // close the keyboard afterwards, which is exactly the state we are leaving.
            if (event.getAction() == KeyEvent.ACTION_UP && mOnBackPressed != null) {
                mOnBackPressed.onBackPressed();
            }
            return true;
        }
        return super.onKeyPreIme(keyCode, event);
    }
}
