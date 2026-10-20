package com.leoaudio.hifi.singlevolume;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** DAC level indicator drawn as an accessibility overlay.  A Toast is not used:
 * NotificationManagerService suppresses toasts from a package whose
 * notifications are off, and this app's are (importance NONE on the device).
 * TYPE_ACCESSIBILITY_OVERLAY needs no extra permission for a bound
 * accessibility service and never takes focus or touches.  Main thread only. */
final class LeoVolumeOverlay {
    private static final long SHOW_MS = 1500;
    private final Context context;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hide;
    private LinearLayout root;
    private TextView label;
    private ProgressBar bar;
    private boolean attached;

    LeoVolumeOverlay(Context serviceContext) {
        context = serviceContext;
        wm = serviceContext.getSystemService(WindowManager.class);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics()));
    }

    private void build() {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6202124);
        bg.setCornerRadius(dp(16));
        root.setBackground(bg);

        label = new TextView(context);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(LeoHifiState.LEVEL_MAX);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(8));
        lp.topMargin = dp(10);
        root.addView(bar, lp);
    }

    /** @param level DAC level 0..LEVEL_MAX; 0 is -60 dB, LEVEL_MAX is the -9 dB cap. */
    void show(int level) {
        if (root == null) build();
        int db = level - 60;
        String text = context.getString(R.string.leo_dac_level, db);
        if (level >= LeoHifiState.LEVEL_MAX) text += context.getString(R.string.leo_dac_at_max);
        else if (level <= 0) text += context.getString(R.string.leo_dac_at_min);
        label.setText(text);
        bar.setProgress(level);
        if (!attached) {
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            p.y = dp(72);
            try {
                wm.addView(root, p);
                attached = true;
            } catch (RuntimeException e) {
                android.util.Log.w("LeoVolumeOverlay", "overlay unavailable", e);
                return;
            }
        }
        root.setVisibility(View.VISIBLE);
        main.removeCallbacks(hide);
        main.postDelayed(hide, SHOW_MS);
    }

    void hide() {
        main.removeCallbacks(hide);
        if (attached) {
            try { wm.removeViewImmediate(root); } catch (RuntimeException ignored) { }
            attached = false;
        }
    }
}
