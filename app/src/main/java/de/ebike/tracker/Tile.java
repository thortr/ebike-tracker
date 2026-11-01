package de.ebike.tracker;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One value on the dashboard: coloured label, big number, unit. */
final class Tile extends LinearLayout {
    final TextView label, value, unit;

    Tile(Context c, String lab, String un, int color, float valueSp) {
        super(c);
        setOrientation(VERTICAL);
        setBackground(Ui.round(c, Ui.CARD, 16));
        int p = Ui.dp(c, 14);
        setPadding(p, Ui.dp(c, 10), p, Ui.dp(c, 12));

        label = Ui.text(c, lab.toUpperCase(Ui.DE), 11, color, true);
        label.setLetterSpacing(0.08f);

        LinearLayout r = Ui.row(c);
        r.setGravity(Gravity.BOTTOM);
        value = Ui.text(c, "–", valueSp, Ui.FG, true);
        value.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        value.setFontFeatureSettings("tnum");
        value.setIncludeFontPadding(false);
        value.setSingleLine(true);
        unit = Ui.text(c, un, 14, Ui.MUTED, false);
        unit.setPadding(Ui.dp(c, 6), 0, 0, Ui.dp(c, 3));
        r.addView(value);
        r.addView(unit);

        addView(label);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 4);
        addView(r, lp);
    }

    void set(String v) {
        if (!v.contentEquals(value.getText())) value.setText(v);
    }

    void setValueSize(float sp) {
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }
}
