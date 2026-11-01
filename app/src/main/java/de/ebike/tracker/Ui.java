package de.ebike.tracker;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

final class Ui {
    private Ui() {}

    static final int BG = 0xFF0E1116, CARD = 0xFF181D25, CARD2 = 0xFF232A35, FG = 0xFFF2F4F7,
            MUTED = 0xFF8B95A5, LINE = 0xFF2C3440, ACCENT = 0xFFFF7A1A,
            SPEED = 0xFF4DA8FF, HR = 0xFFFF5468, POWER = 0xFFFFB020, CAD = 0xFFB794FF,
            ALT = 0xFF5FD39A, BATT = 0xFF7FD3E8, OK = 0xFF3DDC84, WARN = 0xFFFFB020, OFF = 0xFF5A6372,
            DANGER = 0xFFE5484D;

    static final Locale DE = Locale.GERMANY;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static Button button(Context c, String label, int bg, int fg) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(c, bg, 14), null));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 56));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    static LinearLayout.LayoutParams weight(Context c, float w, int marginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        int m = dp(c, marginDp);
        p.setMargins(m, m, m, m);
        return p;
    }

    static LinearLayout.LayoutParams full(Context c, int marginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = dp(c, marginDp);
        p.setMargins(m, m, m, m);
        return p;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static String f0(double v) { return Double.isNaN(v) ? "–" : String.format(DE, "%.0f", v); }

    static String f1(double v) { return Double.isNaN(v) ? "–" : String.format(DE, "%.1f", v); }

    static String f2(double v) { return Double.isNaN(v) ? "–" : String.format(DE, "%.2f", v); }

    static String dur(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(DE, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    static int statusColor(int st) {
        return st == State.ON ? OK : st == State.SEARCH ? WARN : OFF;
    }
}
