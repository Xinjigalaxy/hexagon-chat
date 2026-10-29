package com.dsh.llamahub;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Minimal Material 3 tokens + component factories (no AndroidX dependency). */
public final class M3 {

    public static boolean dark;
    public static int primary;
    public static int onPrimary;
    public static int primaryContainer;
    public static int onPrimaryContainer;
    public static int secondaryContainer;
    public static int onSecondaryContainer;
    public static int surface;
    public static int surfaceContainer;
    public static int surfaceContainerHigh;
    public static int onSurface;
    public static int onSurfaceVariant;
    public static int outline;
    public static int outlineVariant;
    public static int error;

    public static final int FILLED = 0;
    public static final int TONAL = 1;
    public static final int OUTLINED = 2;
    public static final int TEXT = 3;

    private M3() {
    }

    public static void init(Context c) {
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            primary = 0xFFD0BCFF;
            onPrimary = 0xFF381E72;
            primaryContainer = 0xFF4F378B;
            onPrimaryContainer = 0xFFEADDFF;
            secondaryContainer = 0xFF4A4458;
            onSecondaryContainer = 0xFFE8DEF8;
            surface = 0xFF141218;
            surfaceContainer = 0xFF211F26;
            surfaceContainerHigh = 0xFF2B2930;
            onSurface = 0xFFE6E0E9;
            onSurfaceVariant = 0xFFCAC4D0;
            outline = 0xFF938F99;
            outlineVariant = 0xFF49454F;
            error = 0xFFF2B8B5;
        } else {
            primary = 0xFF6750A4;
            onPrimary = 0xFFFFFFFF;
            primaryContainer = 0xFFEADDFF;
            onPrimaryContainer = 0xFF21005D;
            secondaryContainer = 0xFFE8DEF8;
            onSecondaryContainer = 0xFF1D192B;
            surface = 0xFFFEF7FF;
            surfaceContainer = 0xFFF3EDF7;
            surfaceContainerHigh = 0xFFECE6F0;
            onSurface = 0xFF1D1B20;
            onSurfaceVariant = 0xFF49454F;
            outline = 0xFF79747E;
            outlineVariant = 0xFFCAC4D0;
            error = 0xFFB3261E;
        }
    }

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable bg(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, (int) radiusDp));
        return d;
    }

    public static GradientDrawable stroke(int fill, int strokeColor, float radiusDp, Context c) {
        GradientDrawable d = bg(fill, radiusDp, c);
        d.setStroke(dp(c, 1), strokeColor);
        return d;
    }

    public static Drawable ripple(int color, float radiusDp, int rippleColor, Context c) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), bg(color, radiusDp, c), null);
    }

    public static Button button(Context c, String text, int style) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 40)));
        switch (style) {
            case FILLED:
                b.setBackground(ripple(primary, 20, onPrimary & 0x33FFFFFF, c));
                b.setTextColor(onPrimary);
                break;
            case OUTLINED:
                b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary & 0x22FFFFFF),
                        stroke(0x00000000, outline, 20, c), null));
                b.setTextColor(primary);
                break;
            case TEXT:
                b.setBackground(ripple(0x00000000, 20, primary & 0x22FFFFFF, c));
                b.setTextColor(primary);
                break;
            default:
                b.setBackground(ripple(secondaryContainer, 20, onSecondaryContainer & 0x1FFFFFFF, c));
                b.setTextColor(onSecondaryContainer);
                break;
        }
        return b;
    }

    public static TextView navItem(Context c, String label) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 4), dp(c, 10), dp(c, 4), dp(c, 10));
        t.setTextColor(onSurfaceVariant);
        return t;
    }
}
