package de.ebike.tracker;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RideDetailActivity extends BaseActivity {
    private LinearLayout body;
    private RideStore.Ride ride;
    private RideStore.Samples samples;
    private MapView map;
    private LinearLayout chartBox;
    private TextView statsLine;
    private java.util.List<Metric> metrics;
    private final java.util.List<TextView> chips = new java.util.ArrayList<>();
    private float[] xKm;
    private int[] levels;   // assist level per sample, or null if the ride has none

    /** One selectable series: name, unit, colour, decimals and the per-sample values. */
    private static final class Metric {
        final String name, unit;
        final int color, dec;
        final float[] y;
        Metric(String name, String unit, int color, int dec, float[] y) {
            this.name = name; this.unit = unit; this.color = color; this.dec = dec; this.y = y;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(header("Fahrt"));
        body = Ui.col(this);
        int p = Ui.dp(this, 10);
        body.setPadding(p, 0, p, Ui.dp(this, 24));
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        String path = getIntent().getStringExtra("file");
        if (path == null) { finish(); return; }
        File f = new File(path);
        TextView loading = Ui.text(this, "Lade Fahrt …", 15, Ui.MUTED, false);
        body.addView(loading);
        new Thread(() -> {
            try {
                RideStore.Samples s = RideStore.load(f);
                RideStore.Ride r = RideStore.summarize(f, s);
                runOnUiThread(() -> { samples = s; ride = r; build(); });
            } catch (IOException e) {
                runOnUiThread(() -> loading.setText("Die Fahrt konnte nicht geladen werden."));
            }
        }).start();
    }

    private void build() {
        body.removeAllViews();
        RideStore.Ride r = ride;
        RideStore.Samples s = samples;
        SimpleDateFormat day = new SimpleDateFormat("EEEE, d. MMMM yyyy · HH:mm 'Uhr'", Locale.GERMANY);
        TextView date = Ui.text(this, day.format(new Date(r.start)), 15, Ui.MUTED, false);
        date.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 4));
        body.addView(date);

        // ---- distance axis + the metrics that actually have data ----
        xKm = new float[s.n];
        for (int i = 0; i < s.n; i++) xKm[i] = Float.isNaN(s.dist[i]) ? Float.NaN : s.dist[i] / 1000f;

        boolean hasLv = s.hasAny(s.assist);
        levels = null;
        metrics = new java.util.ArrayList<>();
        if (hasLv) {
            levels = new int[s.n];
            float[] lvF = new float[s.n];
            for (int i = 0; i < s.n; i++) { levels[i] = s.assist[i]; lvF[i] = s.assist[i] >= 0 ? s.assist[i] : Float.NaN; }
            metrics.add(new Metric("Stufe", "", 0xFF9AA090, 0, lvF));
        }
        if (s.hasAny(s.alt)) metrics.add(new Metric("Höhe", "m", Ui.ALT, 0, s.alt));
        if (s.hasAny(s.speed)) metrics.add(new Metric("Tempo", "km/h", Ui.SPEED, 1, s.speed));
        if (s.hasAny(s.power)) metrics.add(new Metric("Leistung", "W", Ui.POWER, 0, toF(s.power, s.n, false)));
        if (s.hasAny(s.motor)) metrics.add(new Metric("Motor", "W", Ui.ACCENT, 0, toF(s.motor, s.n, false)));
        if (s.hasAny(s.hr)) metrics.add(new Metric("Puls", "bpm", Ui.HR, 0, toF(s.hr, s.n, true)));
        if (s.hasAny(s.cadence)) metrics.add(new Metric("Trittfr.", "/min", Ui.CAD, 0, toF(s.cadence, s.n, false)));
        if (s.hasAny(s.soc)) metrics.add(new Metric("Akku", "%", Ui.BATT, 0, toF(s.soc, s.n, false)));

        // ---- map stays on top and recolours to the selected metric ----
        if (s.hasTrack()) {
            map = new MapView(this);
            body.addView(map, Ui.full(this, 4));
            Button sat = Ui.button(this, "Satellit", Ui.CARD2, Ui.FG);
            sat.setOnClickListener(v -> {
                map.setSatellite(!map.isSatellite());
                ((Button) v).setText(map.isSatellite() ? "Karte" : "Satellit");
            });
            body.addView(sat);
        }

        // ---- metric selector + one chart that follows the selection ----
        if (!metrics.isEmpty()) {
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chipRow = Ui.row(this);
            chipRow.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            for (int i = 0; i < metrics.size(); i++) {
                final int idx = i;
                TextView b = chip(metrics.get(i).name);
                b.setOnClickListener(v -> selectMetric(idx));
                chips.add(b);
                chipRow.addView(b);
            }
            hs.addView(chipRow);
            body.addView(hs);

            statsLine = Ui.text(this, "", 14, Ui.MUTED, false);
            statsLine.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), 0, Ui.dp(this, 2));
            body.addView(statsLine);

            chartBox = Ui.col(this);
            body.addView(chartBox);
            if (hasLv) body.addView(levelLegend());

            int def = 0;
            for (int i = 0; i < metrics.size(); i++) if ("Leistung".equals(metrics.get(i).name)) def = i;
            selectMetric(def);
        }

        // ---- overview tiles ----
        body.addView(sectionTitle("Überblick"));
        addPair(tile("Distanz", Ui.f2(r.distM / 1000), "km", Ui.FG), tile("Fahrzeit", Ui.dur(r.movingMs), "", Ui.FG));
        addPair(tile("Ø Tempo", Ui.f1(r.avgSpeed), "km/h", Ui.SPEED), tile("Max Tempo", Ui.f1(r.maxSpeed), "km/h", Ui.SPEED));
        addPair(tile("Anstieg", Ui.f0(r.gainM), "hm", Ui.ALT), tile("Abstieg", Ui.f0(r.lossM), "hm", Ui.ALT));
        if (r.avgPower >= 0 || r.avgHr > 0) {
            addPair(tile("Ø Leistung", r.avgPower >= 0 ? String.valueOf(r.avgPower) : "–", "W", Ui.POWER),
                    tile("Max Leistung", r.maxPower >= 0 ? String.valueOf(r.maxPower) : "–", "W", Ui.POWER));
            addPair(tile("Ø Puls", r.avgHr > 0 ? String.valueOf(r.avgHr) : "–", "bpm", Ui.HR),
                    tile("Max Puls", r.maxHr > 0 ? String.valueOf(r.maxHr) : "–", "bpm", Ui.HR));
        }
        if (r.avgCad > 0 || r.socStart >= 0) {
            String used = r.socStart >= 0 && r.socEnd >= 0 ? String.valueOf(Math.max(0, r.socStart - r.socEnd)) : "–";
            addPair(tile("Ø Trittfrequenz", r.avgCad > 0 ? String.valueOf(r.avgCad) : "–", "U/min", Ui.CAD),
                    tile("Akku verbraucht", used, "%", Ui.BATT));
        }
        addPair(tile("Gesamtzeit", Ui.dur(r.durationMs), "", Ui.MUTED), null);

        // ---- breakdown per assist level ----
        addLevelStats(s);

        // actions
        body.addView(sectionTitle("Exportieren"));
        LinearLayout row = Ui.row(this);
        Button gpx = Ui.button(this, "GPX (Strava, Komoot)", Ui.CARD2, Ui.FG);
        gpx.setOnClickListener(v -> export(true));
        Button csv = Ui.button(this, "CSV (Excel)", Ui.CARD2, Ui.FG);
        csv.setOnClickListener(v -> export(false));
        row.addView(gpx, Ui.weight(this, 1, 4));
        row.addView(csv, Ui.weight(this, 1, 4));
        body.addView(row);
        TextView note = Ui.text(this, "Dateien landen im Ordner Download/eBike Tracker. GPX enthält Puls, Leistung und Trittfrequenz.", 13, Ui.MUTED, false);
        note.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), 0);
        body.addView(note);

        Button del = Ui.button(this, "Fahrt löschen", Ui.CARD, Ui.DANGER);
        del.setOnClickListener(v -> confirmDelete());
        body.addView(del, Ui.full(this, 4));
        ((LinearLayout.LayoutParams) del.getLayoutParams()).topMargin = Ui.dp(this, 22);
    }

    private static float[] toF(int[] a, int n, boolean positiveOnly) {
        float[] o = new float[n];
        for (int i = 0; i < n; i++) o[i] = (positiveOnly ? a[i] > 0 : a[i] >= 0) ? a[i] : Float.NaN;
        return o;
    }

    private TextView chip(String label) {
        TextView t = Ui.text(this, label, 14, Ui.FG, true);
        t.setBackground(Ui.round(this, Ui.CARD2, 14));
        int hx = Ui.dp(this, 14), vy = Ui.dp(this, 7);
        t.setPadding(hx, vy, hx, vy);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 6));
        t.setLayoutParams(lp);
        return t;
    }

    private static String fmtNum(double v, int dec) {
        return String.format(Locale.GERMANY, dec == 0 ? "%.0f" : "%.1f", v);
    }

    /** Small colour key for the assist levels (Flow colours), shown under the chart. */
    private android.view.View levelLegend() {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), 0, Ui.dp(this, 2));
        String[] lab = {"1 Eco", "2 Tour", "3 Auto", "4 Turbo"};
        for (int i = 1; i <= 4; i++) {
            TextView t = Ui.text(this, "● " + lab[i - 1], 12, ChartView.levelColor(i), true);
            t.setPadding(0, 0, Ui.dp(this, 12), 0);
            row.addView(t);
        }
        return row;
    }

    /** Per-assist-level breakdown: distance, time and averages for each level used. */
    private void addLevelStats(RideStore.Samples s) {
        if (!s.hasAny(s.assist)) return;
        body.addView(sectionTitle("Nach Fahrstufe"));
        String[] names = {"Stufe 0 · Aus", "Stufe 1 · Eco", "Stufe 2 · Tour", "Stufe 3 · Auto", "Stufe 4 · Turbo"};
        double[] dist = new double[5], time = new double[5];
        double[] sumSpd = new double[5], sumPw = new double[5], sumMo = new double[5];
        int[] nSpd = new int[5], nPw = new int[5], nMo = new int[5];
        for (int i = 0; i < s.n; i++) {
            int a = s.assist[i];
            if (a < 0 || a > 4) continue;
            time[a] += 1;   // samples are ~1 s apart
            if (i > 0 && !Float.isNaN(s.dist[i]) && !Float.isNaN(s.dist[i - 1])) {
                double dd = s.dist[i] - s.dist[i - 1];
                if (dd > 0 && dd < 200) dist[a] += dd;
            }
            if (!Float.isNaN(s.speed[i])) { sumSpd[a] += s.speed[i]; nSpd[a]++; }
            if (s.power[i] >= 0) { sumPw[a] += s.power[i]; nPw[a]++; }
            if (s.motor[i] >= 0) { sumMo[a] += s.motor[i]; nMo[a]++; }
        }
        for (int a = 0; a <= 4; a++) {
            if (time[a] < 1) continue;
            LinearLayout card = Ui.col(this);
            card.setBackground(Ui.round(this, Ui.CARD, 12));
            int pd = Ui.dp(this, 12);
            card.setPadding(pd, Ui.dp(this, 10), pd, Ui.dp(this, 10));
            card.addView(Ui.text(this, "● " + names[a], 15, ChartView.levelColor(a == 0 ? -1 : a), true));
            String spd = nSpd[a] > 0 ? fmtNum(sumSpd[a] / nSpd[a], 1) + " km/h" : "–";
            String pw = nPw[a] > 0 ? fmtNum(sumPw[a] / nPw[a], 0) + " W" : "–";
            String mo = nMo[a] > 0 ? fmtNum(sumMo[a] / nMo[a], 0) + " W" : "–";
            TextView v = Ui.text(this,
                    fmtNum(dist[a] / 1000, 2) + " km  ·  " + Ui.dur((long) (time[a] * 1000))
                            + "\nØ Tempo " + spd + "  ·  Ø Fahrer " + pw + "  ·  Ø Motor " + mo,
                    13, Ui.MUTED, false);
            v.setPadding(0, Ui.dp(this, 3), 0, 0);
            card.addView(v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 8);
            body.addView(card, lp);
        }
    }

    /** Switch the active metric: highlight its chip, recolour the map, update the
     *  Ø/min/max line and show its single chart. */
    private void selectMetric(int idx) {
        if (metrics == null || idx < 0 || idx >= metrics.size()) return;
        Metric m = metrics.get(idx);
        for (int i = 0; i < chips.size(); i++) {
            boolean on = i == idx;
            chips.get(i).setBackground(Ui.round(this, on ? m.color : Ui.CARD2, 14));
            chips.get(i).setTextColor(on ? 0xFF10140C : Ui.FG);
        }
        boolean byLevel = "Stufe".equals(m.name);
        if (map != null) map.setData(samples, m.y, byLevel);

        float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
        double sum = 0;
        int cnt = 0;
        for (int i = 0; i < samples.n; i++) {
            float v = m.y[i];
            if (Float.isNaN(v)) continue;
            mn = Math.min(mn, v); mx = Math.max(mx, v); sum += v; cnt++;
        }
        if (cnt == 0) statsLine.setText("keine Daten");
        else {
            // Build the numbers first, then append the unit — a '%' unit (Akku) inside a
            // format string would be read as a conversion and crash.
            String u = m.unit.isEmpty() ? "" : "  " + m.unit;
            statsLine.setText("Ø " + fmtNum(sum / cnt, m.dec) + "  ·  min " + fmtNum(mn, m.dec)
                    + "  ·  max " + fmtNum(mx, m.dec) + u);
        }

        chartBox.removeAllViews();
        ChartView c = new ChartView(this, m.name, m.unit, m.color, m.dec);
        c.setData(xKm, java.util.Arrays.copyOf(m.y, xKm.length), levels);
        c.setScrubListener(this::markAt);
        chartBox.addView(c, Ui.full(this, 4));
    }

    /** Finger on a chart (km) → show that spot on the map. */
    private void markAt(float km, boolean active) {
        if (map == null) return;
        if (!active) { map.setMarker(0, 0, false); return; }
        RideStore.Samples s = samples;
        float m = km * 1000;
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < s.n; i++) {
            if (Float.isNaN(s.dist[i])) continue;
            float dd = Math.abs(s.dist[i] - m);
            if (dd < bd) { bd = dd; best = i; }
        }
        // nearest sample that actually has a GPS fix
        for (int k = 0; k < s.n; k++) {
            int a = best - k, b = best + k;
            if (a >= 0 && !Double.isNaN(s.lat[a])) { best = a; break; }
            if (b < s.n && !Double.isNaN(s.lat[b])) { best = b; break; }
        }
        if (!Double.isNaN(s.lat[best])) map.setMarker(s.lat[best], s.lon[best], true);
    }

    private Tile tile(String l, String v, String u, int color) {
        Tile t = new Tile(this, l, u, color, 26);
        t.set(v);
        return t;
    }

    private void addPair(Tile a, Tile b) {
        LinearLayout r = Ui.row(this);
        r.addView(a, Ui.weight(this, 1, 4));
        if (b != null) r.addView(b, Ui.weight(this, 1, 4));
        else r.addView(new TextView(this), Ui.weight(this, 1, 4));
        body.addView(r);
    }

    private void export(boolean gpx) {
        if (gpx && !samples.hasTrack()) {
            Toast.makeText(this, "Diese Fahrt hat keine GPS-Punkte. Nimm die CSV-Datei.", Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            try {
                Uri u = gpx ? Exporter.gpx(this, ride, samples) : Exporter.csv(this, ride);
                runOnUiThread(() -> share(u, gpx ? "application/gpx+xml" : "text/csv"));
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, "Export fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void share(Uri u, String mime) {
        Toast.makeText(this, "Gespeichert in Download/eBike Tracker", Toast.LENGTH_SHORT).show();
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Fahrt teilen"));
        } catch (RuntimeException ignored) {}
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Fahrt löschen?")
                .setMessage("Das lässt sich nicht rückgängig machen. Bereits exportierte Dateien bleiben erhalten.")
                .setPositiveButton("Löschen", (d, w) -> { RideStore.delete(ride); finish(); })
                .setNegativeButton("Abbrechen", null)
                .show();
    }
}
