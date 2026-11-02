package de.ebike.tracker;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SettingsActivity extends BaseActivity {
    private SharedPreferences prefs;
    private TextView hrInfo, boschInfo, boschName, gpsInfo;
    private Button hrForget;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            ui.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(header("Einstellungen"));
        LinearLayout c = Ui.col(this);
        int p = Ui.dp(this, 12);
        c.setPadding(p, 0, p, Ui.dp(this, 28));
        ScrollView sv = new ScrollView(this);
        sv.addView(c);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // heart rate
        c.addView(sectionTitle("Pulsgurt"));
        LinearLayout hrCard = card(c);
        hrInfo = Ui.text(this, "", 15, Ui.FG, false);
        hrCard.addView(hrInfo);
        hrCard.addView(note("Trenne den Gurt vorher von adidas Running oder schließe die App. Die meisten Gurte erlauben nur eine Verbindung zur Zeit."));
        LinearLayout hrRow = Ui.row(this);
        Button scan = Ui.button(this, "Pulsgurt suchen", Ui.ACCENT, 0xFF1A0D03);
        scan.setOnClickListener(v -> scanHr());
        hrForget = Ui.button(this, "Entfernen", Ui.CARD2, Ui.FG);
        hrForget.setOnClickListener(v -> {
            prefs.edit().remove("hr_addr").remove("hr_name").apply();
            if (TrackerService.instance != null) TrackerService.instance.refreshLinks();
        });
        hrRow.addView(scan, Ui.weight(this, 1.4f, 3));
        hrRow.addView(hrForget, Ui.weight(this, 1, 3));
        hrCard.addView(hrRow);

        // bosch
        c.addView(sectionTitle("Bosch eBike"));
        LinearLayout bc = card(c);
        boschInfo = Ui.text(this, "", 15, Ui.FG, false);
        bc.addView(boschInfo);
        Switch on = new Switch(this);
        on.setText("Mit dem eBike verbinden");
        on.setTextColor(Ui.FG);
        on.setTextSize(15);
        on.setChecked(prefs.getBoolean("bosch_on", true));
        on.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("bosch_on", checked).apply();
            if (TrackerService.instance != null) TrackerService.instance.refreshLinks();
        });
        on.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        bc.addView(on);
        bc.addView(Ui.text(this, "So koppelst du einmalig:", 14, Ui.FG, true));
        boschName = Ui.text(this, "", 14, Ui.MUTED, false);
        boschName.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        boschName.setLineSpacing(0, 1.25f);
        bc.addView(boschName);
        bc.addView(note("Voraussetzung: Antriebs-Software v19 oder neuer (Flow-App → Einstellungen → Systeminformation). "
                + "Bosch liefert deine Tretleistung, Trittfrequenz, Tempo, Akkustand und Kilometerstand. "
                + "Motorleistung und Unterstützungsstufe stellt die Schnittstelle nicht bereit. "
                + "Es kann immer nur ein Zubehör gleichzeitig verbunden sein, zum Beispiel nicht zusätzlich ein Garmin."));
        Button forget = Ui.button(this, "Bosch-Verbindung neu starten", Ui.CARD2, Ui.FG);
        forget.setOnClickListener(v -> {
            if (TrackerService.instance != null) TrackerService.instance.forgetBike();
            Toast.makeText(this, "Verbindung neu gestartet. Zum vollständigen Entfernen das Handy in der Flow-App unter Komponenten löschen.", Toast.LENGTH_LONG).show();
        });
        bc.addView(forget, Ui.full(this, 0));
        ((LinearLayout.LayoutParams) forget.getLayoutParams()).topMargin = Ui.dp(this, 10);

        bc.addView(note("Test: Statt als Zubehör zu warten, kann sich die App auch selbst mit dem eBike verbinden, so wie die Flow-App. Dafür zuerst die Flow-App schließen, eBike einschalten."));
        Button direct = Ui.button(this, "eBike direkt verbinden (Test)", Ui.CARD2, Ui.FG);
        direct.setOnClickListener(v -> scanDirect());
        bc.addView(direct, Ui.full(this, 0));
        ((LinearLayout.LayoutParams) direct.getLayoutParams()).topMargin = Ui.dp(this, 8);

        Button record = Ui.button(this, "Alle Kanäle aufzeichnen (Test)", Ui.CARD2, Ui.FG);
        record.setOnClickListener(v -> scanRecord());
        bc.addView(record, Ui.full(this, 0));
        ((LinearLayout.LayoutParams) record.getLayoutParams()).topMargin = Ui.dp(this, 8);

        // GPS & battery
        c.addView(sectionTitle("Aufzeichnung"));
        LinearLayout gc = card(c);
        gpsInfo = Ui.text(this, "", 15, Ui.FG, false);
        gc.addView(gpsInfo);
        Switch keep = new Switch(this);
        keep.setText("Bildschirm während der Fahrt anlassen");
        keep.setTextColor(Ui.FG);
        keep.setTextSize(15);
        keep.setChecked(prefs.getBoolean("keep_screen", true));
        keep.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("keep_screen", checked).apply());
        keep.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        gc.addView(keep);
        gc.addView(note("Damit Android die Aufzeichnung bei ausgeschaltetem Bildschirm nicht anhält, nimm die App von der Akku-Optimierung aus."));
        Button batt = Ui.button(this, "Akku-Optimierung ausschalten", Ui.CARD2, Ui.FG);
        batt.setOnClickListener(v -> askBattery());
        gc.addView(batt, Ui.full(this, 0));
        ((LinearLayout.LayoutParams) batt.getLayoutParams()).topMargin = Ui.dp(this, 10);
        Button app = Ui.button(this, "App-Berechtigungen öffnen", Ui.CARD2, Ui.FG);
        app.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        gc.addView(app, Ui.full(this, 0));
        ((LinearLayout.LayoutParams) app.getLayoutParams()).topMargin = Ui.dp(this, 8);

        TextView ver = Ui.text(this, "eBike Tracker " + versionName(), 12, Ui.OFF, false);
        ver.setPadding(Ui.dp(this, 6), Ui.dp(this, 20), 0, 0);
        c.addView(ver);

        setContentView(root);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout l = Ui.col(this);
        l.setBackground(Ui.round(this, Ui.CARD, 16));
        int p = Ui.dp(this, 14);
        l.setPadding(p, p, p, p);
        parent.addView(l, Ui.full(this, 4));
        return l;
    }

    private TextView note(String s) {
        TextView t = Ui.text(this, s, 13, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        t.setLineSpacing(0, 1.15f);
        return t;
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

    private void update() {
        State s = State.I;
        String hrName = prefs.getString("hr_name", null);
        String hrAddr = prefs.getString("hr_addr", null);
        if (hrAddr == null) hrInfo.setText("Kein Pulsgurt gewählt");
        else hrInfo.setText((hrName == null || hrName.isEmpty() ? hrAddr : hrName) + "\n" + s.hrMsg
                + (s.hrFresh(System.currentTimeMillis()) ? " · " + s.hr + " bpm" : ""));
        hrInfo.setTextColor(Ui.statusColor(s.hrStatus) == Ui.OFF ? Ui.FG : Ui.statusColor(s.hrStatus));
        gone(hrForget, hrAddr == null);

        String b = "Status: " + s.boschMsg;
        if (s.bosch()) {
            b += "\nLeistung " + (s.power >= 0 ? s.power + " W" : "–")
                    + " · Trittfrequenz " + (s.cadence >= 0 ? s.cadence + " U/min" : "–")
                    + " · Akku " + (s.soc >= 0 ? s.soc + " %" : "–");
        }
        boschInfo.setText(b);
        boschInfo.setTextColor(s.boschStatus == State.ON ? Ui.OK : s.boschStatus == State.SEARCH ? Ui.WARN : Ui.FG);

        String name = TrackerService.instance == null ? "?" : TrackerService.instance.boschName();
        boschName.setText("1. eBike einschalten, diese Seite offen lassen (das Handy sendet nur, solange sie offen ist).\n"
                + "2. Flow-App → dein eBike → Zahnrad → Komponenten → Neues Gerät hinzufügen → „Zubehör“.\n"
                + "3. In der Liste „" + name + "“ wählen (so heißt dein Handy).\n"
                + "4. Kopplung am Display bzw. an der Fernbedienung des eBikes bestätigen.\n"
                + "5. Danach beobachte oben die Statuszeile: sie sollte auf „Verbunden · N Datenpakete“ springen.");

        gpsInfo.setText(s.gpsMsg + (s.hasBaro ? " · Barometer für die Höhe aktiv" : " · Höhe nur über GPS"));
    }

    private void askBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Ist bereits ausgeschaltet.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ------------------------------------------------------------------ heart rate scan

    // ------------------------------------------------------------------ direct (central) test

    private DirectLink directLink;

    private void scanDirect() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { Toast.makeText(this, "Bitte zuerst Bluetooth einschalten.", Toast.LENGTH_LONG).show(); return; }
        List<String> labels = new ArrayList<>();
        List<String> addrs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            for (android.bluetooth.BluetoothDevice d : ad.getBondedDevices()) {
                addrs.add(d.getAddress());
                String n = d.getName();
                names.add(n == null ? "" : n);
                labels.add((n == null ? "?" : n) + "  (gekoppelt)");
            }
        } catch (SecurityException ignored) {}
        ArrayAdapter<String> aa = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                String a = r.getDevice().getAddress();
                String n = null;
                try { n = r.getDevice().getName(); } catch (SecurityException ignored) {}
                if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
                String nn = n;
                runOnUiThread(() -> {
                    if (addrs.contains(a)) return;
                    addrs.add(a);
                    names.add(nn == null ? "" : nn);
                    labels.add((nn == null ? "Unbekannt" : nn) + "  (" + r.getRssi() + " dBm)");
                    aa.notifyDataSetChanged();
                });
            }
        };
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("eBike wählen")
                .setAdapter(aa, (d, which) -> connectDirect(addrs.get(which), names.get(which)))
                .setNegativeButton("Abbrechen", null)
                .create();
        if (sc != null) {
            try {
                sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
            } catch (SecurityException ignored) {}
        }
        dlg.setOnDismissListener(d -> { if (sc != null) try { sc.stopScan(cb); } catch (SecurityException | IllegalStateException ignored) {} });
        dlg.show();
        Toast.makeText(this, "Dein eBike heißt oft wie dein Rad oder „Bosch“. Meist ist es das gekoppelte Gerät.", Toast.LENGTH_LONG).show();
    }

    private void connectDirect(String address, String name) {
        TextView tv = new TextView(this);
        tv.setText("Starte …");
        tv.setTextColor(Ui.FG);
        tv.setTextSize(12);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        int p = Ui.dp(this, 12);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Direktverbindung")
                .setView(sv)
                .setPositiveButton("Schließen", null)
                .create();
        dlg.setOnDismissListener(d -> { if (directLink != null) { directLink.close(); directLink = null; } });
        dlg.show();
        directLink = new DirectLink(this, ui, text -> runOnUiThread(() -> {
            tv.setText(text);
            sv.post(() -> sv.fullScroll(android.view.View.FOCUS_DOWN));
        }));
        directLink.connect(address, name);
    }

    // ------------------------------------------------------------------ record-all-channels test

    private BoschRecorder recorder;

    private void scanRecord() {
        pickDevice(this::startRecord);
    }

    private void pickDevice(java.util.function.BiConsumer<String, String> onPick) {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { Toast.makeText(this, "Bitte zuerst Bluetooth einschalten.", Toast.LENGTH_LONG).show(); return; }
        List<String> labels = new ArrayList<>();
        List<String> addrs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            for (android.bluetooth.BluetoothDevice d : ad.getBondedDevices()) {
                addrs.add(d.getAddress());
                String n = d.getName();
                names.add(n == null ? "" : n);
                labels.add((n == null ? "?" : n) + "  (gekoppelt)");
            }
        } catch (SecurityException ignored) {}
        ArrayAdapter<String> aa = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                String a = r.getDevice().getAddress();
                String n = null;
                try { n = r.getDevice().getName(); } catch (SecurityException ignored) {}
                if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
                String nn = n;
                runOnUiThread(() -> {
                    if (addrs.contains(a)) return;
                    addrs.add(a);
                    names.add(nn == null ? "" : nn);
                    labels.add((nn == null ? "Unbekannt" : nn) + "  (" + r.getRssi() + " dBm)");
                    aa.notifyDataSetChanged();
                });
            }
        };
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("eBike wählen")
                .setAdapter(aa, (d, which) -> onPick.accept(addrs.get(which), names.get(which)))
                .setNegativeButton("Abbrechen", null)
                .create();
        if (sc != null) {
            try { sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb); } catch (SecurityException ignored) {}
        }
        dlg.setOnDismissListener(d -> { if (sc != null) try { sc.stopScan(cb); } catch (SecurityException | IllegalStateException ignored) {} });
        dlg.show();
        Toast.makeText(this, "Dein eBike ist meist das gekoppelte Gerät („smart system eBike“).", Toast.LENGTH_LONG).show();
    }

    private void startRecord(String address, String name) {
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 12);
        box.setPadding(p, p, p, p);
        TextView tv = new TextView(this);
        tv.setText("Starte …");
        tv.setTextColor(Ui.FG);
        tv.setTextSize(13);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        box.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 300)));
        Button saveBtn = Ui.button(this, "Speichern & Teilen", Ui.ACCENT, 0xFF1A0D03);
        box.addView(saveBtn, Ui.full(this, 0));
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Kanäle aufzeichnen")
                .setView(box)
                .setPositiveButton("Schließen", null)
                .create();
        dlg.setOnDismissListener(d -> { if (recorder != null) { recorder.close(); recorder = null; } });
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recorder = new BoschRecorder(this, ui, text -> runOnUiThread(() -> {
            tv.setText(text);
            sv.post(() -> sv.fullScroll(android.view.View.FOCUS_DOWN));
        }));
        saveBtn.setOnClickListener(v -> {
            if (recorder == null) return;
            new Thread(() -> {
                try {
                    android.net.Uri u = recorder.save();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Gespeichert in Download/eBike Tracker", Toast.LENGTH_SHORT).show();
                        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try { startActivity(Intent.createChooser(i, "Rohdaten senden")); } catch (RuntimeException ignored) {}
                    });
                } catch (java.io.IOException e) {
                    runOnUiThread(() -> Toast.makeText(this, "Speichern fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });
        recorder.connect(address, name);
    }

    private void scanHr() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            Toast.makeText(this, "Bitte zuerst Bluetooth einschalten.", Toast.LENGTH_LONG).show();
            return;
        }
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        if (sc == null) return;
        List<String> labels = new ArrayList<>();
        List<String> addrs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ArrayAdapter<String> aa = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                String a = r.getDevice().getAddress();
                String n = null;
                try { n = r.getDevice().getName(); } catch (SecurityException ignored) {}
                if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
                String nn = n;
                runOnUiThread(() -> {
                    if (addrs.contains(a)) return;
                    addrs.add(a);
                    names.add(nn == null ? "" : nn);
                    labels.add((nn == null ? "Unbekannter Gurt" : nn) + "   (" + r.getRssi() + " dBm)");
                    aa.notifyDataSetChanged();
                });
            }
        };
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Suche Pulsgurte …")
                .setAdapter(aa, (d, which) -> {
                    prefs.edit().putString("hr_addr", addrs.get(which)).putString("hr_name", names.get(which)).apply();
                    if (TrackerService.instance != null) TrackerService.instance.refreshLinks();
                })
                .setNegativeButton("Abbrechen", null)
                .create();
        dlg.setOnDismissListener(d -> { try { sc.stopScan(cb); } catch (SecurityException | IllegalStateException ignored) {} });
        dlg.show();
        Toast.makeText(this, "Gurt anlegen, er sendet erst mit Hautkontakt.", Toast.LENGTH_LONG).show();
        try {
            ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(HrLink.HRS)).build();
            ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            sc.startScan(Collections.singletonList(f), st, cb);
        } catch (SecurityException e) {
            dlg.dismiss();
            Toast.makeText(this, "Bluetooth-Berechtigung fehlt.", Toast.LENGTH_LONG).show();
        }
        ui.postDelayed(() -> { if (dlg.isShowing()) dlg.setTitle(labels.isEmpty() ? "Kein Gurt gefunden – Gurt angelegt?" : "Pulsgurt wählen"); }, 12000);
    }
}
