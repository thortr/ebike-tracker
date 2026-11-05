package de.ebike.tracker;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity {
    private Tile speed, time, dist, hr, power, cad, avgSpeed, alt, gain, batt, avgPower, maxSpeed, assist, range, energy, motor, total;
    private TextView chipGps, chipHr, chipBike, status, hint;
    private Button start, pause, stop;
    private LinearLayout idleRow, recRow;
    private SharedPreferences prefs;
    private boolean keepOn;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 10);
        root.setPadding(p, p, p, p);

        // header
        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "eBike Tracker", 20, Ui.FG, true);
        title.setPadding(Ui.dp(this, 6), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        chipGps = chip("GPS");
        chipHr = chip("Puls");
        chipBike = chip("eBike");
        head.addView(chipGps);
        head.addView(chipHr);
        head.addView(chipBike);
        root.addView(head, Ui.full(this, 2));

        status = Ui.text(this, "", 13, Ui.MUTED, false);
        status.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), 0, Ui.dp(this, 4));
        root.addView(status);

        // tiles
        LinearLayout grid = Ui.col(this);
        speed = new Tile(this, "Tempo", "km/h", Ui.SPEED, 76);
        grid.addView(speed, Ui.full(this, 4));
        time = new Tile(this, "Zeit", "", Ui.FG, 34);
        dist = new Tile(this, "Distanz", "km", Ui.FG, 34);
        addRow(grid, time, dist);
        hr = new Tile(this, "Puls", "bpm", Ui.HR, 40);
        power = new Tile(this, "Deine Leistung", "W", Ui.POWER, 40);
        addRow(grid, hr, power);
        cad = new Tile(this, "Trittfrequenz", "U/min", Ui.CAD, 34);
        avgPower = new Tile(this, "Ø Leistung", "W", Ui.POWER, 34);
        addRow(grid, cad, avgPower);
        avgSpeed = new Tile(this, "Ø Tempo", "km/h", Ui.SPEED, 34);
        maxSpeed = new Tile(this, "Max Tempo", "km/h", Ui.SPEED, 34);
        addRow(grid, avgSpeed, maxSpeed);
        alt = new Tile(this, "Höhe", "m", Ui.ALT, 34);
        gain = new Tile(this, "Anstieg", "hm", Ui.ALT, 34);
        addRow(grid, alt, gain);
        batt = new Tile(this, "eBike-Akku", "%", Ui.BATT, 34);
        assist = new Tile(this, "Stufe", "", Ui.ACCENT, 34);
        addRow(grid, batt, assist);
        range = new Tile(this, "Reichweite", "km", Ui.BATT, 34);
        energy = new Tile(this, "Verbrauch", "Wh/km", Ui.BATT, 34);
        addRow(grid, range, energy);
        motor = new Tile(this, "Motor", "W", Ui.ACCENT, 40);
        total = new Tile(this, "Gesamt", "W", Ui.POWER, 34);
        addRow(grid, motor, total);

        hint = Ui.text(this, "", 13, Ui.WARN, false);
        hint.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), 0);
        grid.addView(hint);

        ScrollView sv = new ScrollView(this);
        sv.addView(grid);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // controls
        start = Ui.button(this, "Aufzeichnung starten", Ui.ACCENT, 0xFF1A0D03);
        start.setOnClickListener(v -> startRide());
        root.addView(start, Ui.full(this, 4));

        idleRow = Ui.row(this);
        Button rides = Ui.button(this, "Fahrten", Ui.CARD2, Ui.FG);
        rides.setOnClickListener(v -> startActivity(new Intent(this, RidesActivity.class)));
        Button settings = Ui.button(this, "Einstellungen", Ui.CARD2, Ui.FG);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        idleRow.addView(rides, Ui.weight(this, 1, 4));
        idleRow.addView(settings, Ui.weight(this, 1, 4));
        root.addView(idleRow);

        recRow = Ui.row(this);
        pause = Ui.button(this, "Pause", Ui.CARD2, Ui.FG);
        pause.setOnClickListener(v -> togglePause());
        stop = Ui.button(this, "Beenden", Ui.DANGER, Ui.FG);
        stop.setOnClickListener(v -> confirmStop());
        recRow.addView(pause, Ui.weight(this, 1, 4));
        recRow.addView(stop, Ui.weight(this, 1, 4));
        root.addView(recRow);

        chipGps.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        chipHr.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        chipBike.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        setContentView(root);
        askPermissions();
    }

    private TextView chip(String s) {
        TextView t = Ui.text(this, "● " + s, 13, Ui.OFF, true);
        t.setBackground(Ui.round(this, Ui.CARD, 12));
        int h = Ui.dp(this, 10), v = Ui.dp(this, 6);
        t.setPadding(h, v, h, v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Ui.dp(this, 6));
        t.setLayoutParams(lp);
        return t;
    }

    private void addRow(LinearLayout grid, Tile a, Tile b) {
        LinearLayout r = Ui.row(this);
        r.addView(a, Ui.weight(this, 1, 4));
        r.addView(b, Ui.weight(this, 1, 4));
        grid.addView(r);
    }

    // ------------------------------------------------------------------ permissions

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        String[] all = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE};
        for (String s : all) if (!TrackerService.has(this, s)) need.add(s);
        if (Build.VERSION.SDK_INT >= 33 && !TrackerService.has(this, Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        TrackerService.ensureStarted(this);
    }

    // ------------------------------------------------------------------ actions

    private void startRide() {
        TrackerService s = TrackerService.instance;
        if (s == null) {
            TrackerService.ensureStarted(this);
            Toast.makeText(this, "Sensoren starten, bitte gleich noch einmal tippen.", Toast.LENGTH_SHORT).show();
            return;
        }
        s.startRide();
        update();
    }

    private void togglePause() {
        TrackerService s = TrackerService.instance;
        if (s != null) s.setPaused(!State.I.paused);
        update();
    }

    private void confirmStop() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Aufzeichnung beenden?")
                .setMessage("Die Fahrt wird gespeichert.")
                .setPositiveButton("Beenden und speichern", (d, w) -> {
                    TrackerService s = TrackerService.instance;
                    File f = s == null ? null : s.stopRide();
                    update();
                    if (f != null) {
                        startActivity(new Intent(this, RideDetailActivity.class).putExtra("file", f.getAbsolutePath()));
                    } else {
                        Toast.makeText(this, "Fahrt war zu kurz und wurde nicht gespeichert.", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Weiterfahren", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }

    // ------------------------------------------------------------------ display

    private void setChip(TextView t, String name, int st) {
        t.setTextColor(Ui.statusColor(st));
    }

    private void update() {
        State s = State.I;
        long now = System.currentTimeMillis();
        setChip(chipGps, "GPS", s.gpsStatus);
        setChip(chipHr, "Puls", s.hrStatus);
        setChip(chipBike, "eBike", s.boschStatus);

        float spd = Float.isNaN(s.dispSpeed) ? s.speedKmh(now) : s.dispSpeed;
        speed.set(Float.isNaN(spd) ? "–" : Ui.f1(spd));
        time.set(Ui.dur(s.elapsedMs));
        dist.set(Ui.f2(s.distM / 1000));
        hr.set(s.hrFresh(now) ? String.valueOf(s.hr) : "–");
        boolean bike = s.bosch();
        int dp = s.dispPower >= 0 ? s.dispPower : s.powerNow(now);
        power.set(dp >= 0 ? String.valueOf(dp) : "–");
        cad.set(s.cadenceNow(now) >= 0 ? String.valueOf(s.cadenceNow(now)) : "–");
        avgPower.set(s.nPower > 0 ? Ui.f0(s.sumPower / s.nPower) : "–");
        avgSpeed.set(s.movingMs > 0 ? Ui.f1(s.distM / (s.movingMs / 1000.0) * 3.6) : "–");
        maxSpeed.set(s.maxSpeed > 0 ? Ui.f1(s.maxSpeed) : "–");
        alt.set(Double.isNaN(s.alt) ? "–" : Ui.f0(s.alt));
        gain.set(Ui.f0(s.gainM));
        batt.set(bike && s.soc >= 0 ? String.valueOf(s.soc) : "–");
        assist.set(bike && s.assist >= 0 ? String.valueOf(s.assist) : "–");
        range.set(bike && s.rangeNow() >= 0 ? String.valueOf(s.rangeNow()) : "–");
        float cons = s.consumptionWhPerKm();
        energy.set(!Float.isNaN(cons) ? Ui.f0(cons) : "–");
        motor.set(bike && s.motorNow(now) >= 0 ? String.valueOf(s.motorNow(now)) : "–");
        total.set(bike && s.totalNow(now) >= 0 ? String.valueOf(s.totalNow(now)) : "–");

        String st;
        if (s.recording) st = s.paused ? "Pausiert" : "● Aufzeichnung läuft";
        else st = "Bereit";
        st += "   ·   " + s.gpsMsg + "   ·   " + s.hrMsg + "   ·   eBike: " + s.boschMsg;
        status.setText(st);
        status.setTextColor(s.recording && !s.paused ? Ui.ACCENT : Ui.MUTED);

        String h = "";
        if (TrackerService.instance == null) h = "Bitte Standort- und Bluetooth-Zugriff erlauben, damit die Sensoren starten.";
        else if (s.gpsStatus == State.OFF) h = "GPS ist aus oder nicht erlaubt.";
        hint.setText(h);
        gone(hint, h.isEmpty());

        gone(start, s.recording);
        gone(idleRow, s.recording);
        gone(recRow, !s.recording);
        pause.setText(s.paused ? "Weiter" : "Pause");

        boolean wantOn = s.recording && prefs.getBoolean("keep_screen", true);
        if (wantOn != keepOn) {
            keepOn = wantOn;
            if (wantOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}
