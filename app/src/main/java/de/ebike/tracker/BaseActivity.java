package de.ebike.tracker;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared look and service lifetime: sensors run while any screen of the app is visible. */
public abstract class BaseActivity extends Activity {
    private static int visible;
    protected final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible++;
        TrackerService.ensureStarted(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible--;
        ui.postDelayed(() -> {
            if (visible <= 0 && TrackerService.instance != null) TrackerService.instance.shutdownIfIdle();
        }, 4000);
    }

    /** Header with back arrow and title, used by the secondary screens. */
    protected LinearLayout header(String title) {
        LinearLayout h = Ui.row(this);
        h.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 8);
        h.setPadding(p, p, p, p);
        TextView back = Ui.text(this, "‹", 34, Ui.FG, false);
        back.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 14), Ui.dp(this, 4));
        back.setContentDescription("Zurück");
        back.setOnClickListener(v -> finish());
        TextView t = Ui.text(this, title, 21, Ui.FG, true);
        h.addView(back);
        h.addView(t);
        return h;
    }

    protected TextView sectionTitle(String s) {
        TextView t = Ui.text(this, s.toUpperCase(Ui.DE), 12, Ui.MUTED, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(this, 6), Ui.dp(this, 18), 0, Ui.dp(this, 2));
        return t;
    }

    protected static void gone(View v, boolean g) { v.setVisibility(g ? View.GONE : View.VISIBLE); }

    protected ViewGroup.LayoutParams match() {
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
