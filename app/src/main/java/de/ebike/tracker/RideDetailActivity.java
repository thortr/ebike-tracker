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

        // summary tiles
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

        // track
        if (s.hasTrack()) {
            boolean byPower = s.hasAny(s.power);
            body.addView(sectionTitle(byPower ? "Strecke · Farbe nach Leistung" : "Strecke · Farbe nach Tempo"));
            TrackView tv = new TrackView(this);
            float[] col = new float[s.n];
            for (int i = 0; i < s.n; i++) col[i] = byPower ? (s.power[i] >= 0 ? s.power[i] : Float.NaN) : s.speed[i];
            tv.setData(s, col);
            body.addView(tv, Ui.full(this, 4));
        }

        // charts over distance
        float[] x = new float[s.n];
        for (int i = 0; i < s.n; i++) x[i] = Float.isNaN(s.dist[i]) ? Float.NaN : s.dist[i] / 1000f;
        body.addView(sectionTitle("Verlauf · zum Ablesen auf das Diagramm tippen"));
        if (s.hasAny(s.alt)) chart("Höhe", "m", Ui.ALT, 0, x, s.alt);
        if (s.hasAny(s.speed)) chart("Tempo", "km/h", Ui.SPEED, 1, x, s.speed);
        if (s.hasAny(s.power)) chart("Deine Leistung", "W", Ui.POWER, 0, x, toF(s.power, s.n, false));
        if (s.hasAny(s.hr)) chart("Puls", "bpm", Ui.HR, 0, x, toF(s.hr, s.n, true));
        if (s.hasAny(s.cadence)) chart("Trittfrequenz", "U/min", Ui.CAD, 0, x, toF(s.cadence, s.n, false));
        if (s.hasAny(s.soc)) chart("eBike-Akku", "%", Ui.BATT, 0, x, toF(s.soc, s.n, false));

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

    private void chart(String title, String unit, int color, int dec, float[] x, float[] y) {
        ChartView c = new ChartView(this, title, unit, color, dec);
        c.setData(x, java.util.Arrays.copyOf(y, x.length));
        body.addView(c, Ui.full(this, 4));
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
