package de.ebike.tracker;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reverse-engineering recorder. Connects to the eBike as central (like the Flow app)
 * and subscribes to EVERY notify/indicate characteristic, logging each packet's raw
 * bytes with a timestamp. The log is saved to Downloads so the user can send it for
 * decoding. Read-only; never writes control data.
 */
final class BoschRecorder {
    interface Report { void update(String text); }

    private static final java.util.UUID CCCD =
            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Handler main;
    private final Report report;
    private final StringBuilder file = new StringBuilder();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final List<BluetoothGattCharacteristic> subs = new ArrayList<>();
    private BluetoothGatt gatt;
    private BluetoothDevice target;
    private long t0;
    private int subIdx, total;
    private boolean discovered, full;

    BoschRecorder(Context c, Handler h, Report r) {
        ctx = c.getApplicationContext();
        main = h;
        report = r;
    }

    private void line(String s) {
        if (file.length() < 2_000_000) file.append(s).append('\n');
        else full = true;
    }

    private void status() {
        StringBuilder b = new StringBuilder();
        b.append(total).append(" Pakete empfangen").append(full ? " (Protokoll voll)" : "").append("\n\n");
        if (counts.isEmpty()) b.append("Warte auf Daten … bitte fahren.\n");
        else { b.append("Pro Kanal:\n"); for (Map.Entry<String, Integer> e : counts.entrySet()) b.append("  ").append(e.getKey()).append(" : ").append(e.getValue()).append("\n"); }
        b.append("\nWenn du eine Runde gefahren bist, auf „Speichern & Teilen“ tippen und mir die Datei schicken.");
        report.update(b.toString());
    }

    void connect(String address, String name) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) { report.update("Bluetooth nicht verfügbar."); return; }
        t0 = System.currentTimeMillis();
        line("# eBike-Rohdaten-Mitschnitt");
        line("# Gerät: " + (name == null ? address : name) + " (" + address + ")");
        line("# Start: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY).format(new Date(t0)));
        line("# Format:  <ms seit Start>  <kanal>  <hex bytes>");
        line("");
        try {
            target = ad.getRemoteDevice(address);
            gatt = target.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
            report.update("Verbinde mit " + (name == null ? address : name) + " …");
        } catch (SecurityException | IllegalArgumentException e) {
            report.update("Fehler: " + e.getMessage());
        }
    }

    void close() {
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) { try { g.disconnect(); g.close(); } catch (SecurityException ignored) {} }
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    report.update("Verbunden. Suche Kanäle …");
                    try { g.requestMtu(247); } catch (SecurityException ignored) {}
                    main.postDelayed(() -> startDiscovery(g), 800);
                } else {
                    line("# getrennt, Status " + status);
                    report.update("Verbindung getrennt (Status " + status + "). Du kannst trotzdem speichern.");
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            main.post(() -> startDiscovery(g));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g != gatt) return;
                if (g.getServices().isEmpty()) { main.postDelayed(() -> { try { g.discoverServices(); } catch (SecurityException ignored) {} }, 800); return; }
                subs.clear();
                for (BluetoothGattService s : g.getServices()) {
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        line("# dienst " + shortU(s.getUuid().toString()) + " merkmal " + shortU(c.getUuid().toString()) + " props=" + c.getProperties());
                        int p = c.getProperties();
                        if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) subs.add(c);
                    }
                }
                line("");
                subIdx = 0;
                report.update("Abonniere " + subs.size() + " Kanäle …");
                subscribeNext(g);
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> { if (g == gatt) { subIdx++; subscribeNext(g); } });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            main.post(() -> rx(c, value));
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) main.post(() -> rx(c, c.getValue()));
        }
    };

    private void startDiscovery(BluetoothGatt g) {
        if (g != gatt || discovered) return;
        discovered = true;
        try { g.discoverServices(); } catch (SecurityException ignored) {}
    }

    private void subscribeNext(BluetoothGatt g) {
        if (g != gatt) return;
        if (subIdx >= subs.size()) {
            line("# alle " + subs.size() + " Kanäle abonniert");
            status();
            return;
        }
        BluetoothGattCharacteristic c = subs.get(subIdx);
        try {
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d == null) { subIdx++; subscribeNext(g); return; }
            boolean indicate = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
            byte[] v = indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v);
            else { d.setValue(v); g.writeDescriptor(d); }
        } catch (SecurityException e) {
            subIdx++; subscribeNext(g);
        }
    }

    private void rx(BluetoothGattCharacteristic c, byte[] v) {
        if (v == null) return;
        String ch = shortU(c.getUuid().toString());
        total++;
        counts.put(ch, (counts.getOrDefault(ch, 0)) + 1);
        line((System.currentTimeMillis() - t0) + "  " + ch + "  " + hex(v));
        if (total % 10 == 0 || total < 5) status();
    }

    Uri save() throws IOException {
        String name = "bosch-rohdaten-" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date(t0)) + ".txt";
        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/eBike Tracker");
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("Speichern nicht möglich");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("Datei nicht beschreibbar");
            os.write(file.toString().getBytes(StandardCharsets.UTF_8));
        }
        return uri;
    }

    private static String shortU(String u) {
        String low = u.toLowerCase(Locale.US);
        if (low.endsWith("-eaa2-11e9-81b4-2a2ae2dbcce4")) return low.substring(4, 8); // Bosch service family
        if (low.startsWith("0000") && low.endsWith("-0000-1000-8000-00805f9b34fb")) return "0x" + low.substring(4, 8).toUpperCase(Locale.US);
        return low;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return s.toString();
    }
}
