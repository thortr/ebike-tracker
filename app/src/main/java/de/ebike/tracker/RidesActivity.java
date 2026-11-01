package de.ebike.tracker;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RidesActivity extends BaseActivity {
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(header("Fahrten"));
        list = Ui.col(this);
        int p = Ui.dp(this, 10);
        list.setPadding(p, 0, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        list.removeAllViews();
        TextView loading = Ui.text(this, "Lade Fahrten …", 15, Ui.MUTED, false);
        loading.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), 0, 0);
        list.addView(loading);
        long skip = State.I.recording ? State.I.startTime : -1;
        new Thread(() -> {
            List<RideStore.Ride> rides = RideStore.list(this, skip);
            runOnUiThread(() -> show(rides));
        }).start();
    }

    private void show(List<RideStore.Ride> rides) {
        list.removeAllViews();
        if (rides.isEmpty()) {
            TextView t = Ui.text(this, "Noch keine Fahrten gespeichert.\n\nStarte auf dem Hauptbildschirm eine Aufzeichnung. Nach dem Beenden erscheint die Fahrt hier.", 15, Ui.MUTED, false);
            t.setPadding(Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8), 0);
            list.addView(t);
            return;
        }
        SimpleDateFormat day = new SimpleDateFormat("EEE, d. MMM yyyy · HH:mm", Locale.GERMANY);
        double totalKm = 0;
        for (RideStore.Ride r : rides) totalKm += r.distM / 1000;
        TextView sum = Ui.text(this, rides.size() + (rides.size() == 1 ? " Fahrt · " : " Fahrten · ") + Ui.f1(totalKm) + " km gesamt", 13, Ui.MUTED, false);
        sum.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 6));
        list.addView(sum);

        for (RideStore.Ride r : rides) {
            LinearLayout card = Ui.col(this);
            card.setBackground(Ui.round(this, Ui.CARD, 16));
            int p = Ui.dp(this, 14);
            card.setPadding(p, p, p, p);
            card.addView(Ui.text(this, day.format(new Date(r.start)), 15, Ui.FG, true));

            LinearLayout vals = Ui.row(this);
            vals.setGravity(Gravity.BOTTOM);
            vals.addView(stat(Ui.f2(r.distM / 1000), "km", Ui.FG));
            vals.addView(stat(Ui.dur(r.movingMs > 0 ? r.movingMs : r.durationMs), "", Ui.FG));
            vals.addView(stat(Ui.f0(r.gainM), "hm", Ui.ALT));
            card.addView(vals);

            StringBuilder more = new StringBuilder();
            more.append("Ø ").append(Ui.f1(r.avgSpeed)).append(" km/h");
            if (r.avgPower >= 0) more.append("  ·  Ø ").append(r.avgPower).append(" W");
            if (r.avgHr > 0) more.append("  ·  Ø ").append(r.avgHr).append(" bpm");
            if (r.socStart >= 0 && r.socEnd >= 0) more.append("  ·  Akku −").append(Math.max(0, r.socStart - r.socEnd)).append(" %");
            TextView m = Ui.text(this, more.toString(), 13, Ui.MUTED, false);
            m.setPadding(0, Ui.dp(this, 6), 0, 0);
            card.addView(m);

            card.setOnClickListener(v -> startActivity(new Intent(this, RideDetailActivity.class).putExtra("file", r.csv.getAbsolutePath())));
            list.addView(card, Ui.full(this, 5));
        }
    }

    private LinearLayout stat(String v, String unit, int color) {
        LinearLayout l = Ui.row(this);
        l.setGravity(Gravity.BOTTOM);
        TextView a = Ui.text(this, v, 24, color, true);
        a.setFontFeatureSettings("tnum");
        l.addView(a);
        if (!unit.isEmpty()) {
            TextView u = Ui.text(this, unit, 13, Ui.MUTED, false);
            u.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 3));
            l.addView(u);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.topMargin = Ui.dp(this, 6);
        l.setLayoutParams(lp);
        return l;
    }
}
