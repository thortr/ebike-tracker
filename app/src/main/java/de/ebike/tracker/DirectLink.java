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
import android.content.Context;
import android.os.Build;
import android.os.Handler;

import java.util.Locale;

/**
 * Diagnostic path: connect to the eBike as CENTRAL (the way the Flow app does),
 * then list every GATT service it exposes. If the Bosch Live Data characteristic
 * is reachable this way, enable notifications and show live values. This is the
 * role combination Android supports well (phone = central/client).
 */
final class DirectLink {
    interface Report { void update(String text); }

    private final Context ctx;
    private final Handler main;
    private final Report report;
    private final StringBuilder log = new StringBuilder();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic ldiChar;
    private int packets;

    DirectLink(Context c, Handler h, Report r) {
        ctx = c.getApplicationContext();
        main = h;
        report = r;
    }

    private void out(String s) {
        log.append(s).append('\n');
        report.update(log.toString());
    }

    void connect(String address, String name) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) { out("Bluetooth nicht verfügbar."); return; }
        try {
            BluetoothDevice d = ad.getRemoteDevice(address);
            out("Verbinde als Zentrale mit " + (name == null || name.isEmpty() ? address : name) + " …");
            gatt = d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException | IllegalArgumentException e) {
            out("Fehler beim Verbinden: " + e.getMessage());
        }
    }

    void close() {
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try { g.disconnect(); g.close(); } catch (SecurityException ignored) {}
        }
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    out("Verbunden (Status " + status + "). Suche Dienste …");
                    try { g.requestMtu(247); } catch (SecurityException ignored) {}
                    main.postDelayed(() -> { if (g == gatt) try { g.discoverServices(); } catch (SecurityException ignored) {} }, 800);
                } else {
                    out("Getrennt (Status " + status + ")."
                            + (status == 133 ? " Hinweis: Flow-App schließen und eBike einschalten, dann erneut." : ""));
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            main.post(() -> { if (g == gatt) try { g.discoverServices(); } catch (SecurityException ignored) {} });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g != gatt) return;
                out("");
                out("=== Angebotene Dienste (Status " + status + ") ===");
                boolean foundLdi = false;
                for (BluetoothGattService s : g.getServices()) {
                    out("Dienst " + shortUuid(s.getUuid().toString()));
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        out("    Merkmal " + shortUuid(c.getUuid().toString()) + "  (" + props(c.getProperties()) + ")");
                        if (c.getUuid().equals(BoschLink.CHR)) { ldiChar = c; foundLdi = true; }
                    }
                }
                out("");
                if (foundLdi) {
                    out("*** Live-Data-Merkmal gefunden! Aktiviere Benachrichtigungen …");
                    try {
                        g.setCharacteristicNotification(ldiChar, true);
                        BluetoothGattDescriptor d = ldiChar.getDescriptor(BoschLink.CCCD);
                        if (d != null) HrLink.writeEnable(g, d);
                        else g.readCharacteristic(ldiChar);
                    } catch (SecurityException ignored) {}
                } else {
                    out(">>> Kein Live-Data-Dienst (eb20) über diese Verbindung.");
                    out(">>> Das eBike gibt die Livedaten so nicht heraus; sie laufen nur über die Zubehör-Rolle.");
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> {
                if (g != gatt) return;
                out("Benachrichtigung aktiviert (Status " + status + "). Warte auf Daten, bitte Pedale drehen …");
                try { g.readCharacteristic(ldiChar); } catch (SecurityException ignored) {}
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            main.post(() -> handle(value, status));
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < 33) main.post(() -> handle(c.getValue(), status));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            main.post(() -> handle(value, 0));
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) main.post(() -> handle(c.getValue(), 0));
        }
    };

    private void handle(byte[] v, int status) {
        if (v == null || v.length == 0) { out("Leeres Paket (Status " + status + ")."); return; }
        State s = State.I;
        int known = Proto.decode(v, s);
        packets++;
        out(String.format(Locale.GERMANY, "Paket %d: %d Byte, %d Felder — Leistung %s W, Kadenz %s, Tempo %s, Akku %s%%",
                packets, v.length, known,
                s.power >= 0 ? String.valueOf(s.power) : "-",
                s.cadence >= 0 ? String.valueOf(s.cadence) : "-",
                Float.isNaN(s.bikeSpeed) ? "-" : String.format(Locale.GERMANY, "%.1f", s.bikeSpeed),
                s.soc >= 0 ? String.valueOf(s.soc) : "-"));
    }

    private static String shortUuid(String u) {
        // Highlight the eBike's own UUIDs; shorten standard 16-bit ones.
        if (u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805f9b34fb")) {
            return "0x" + u.substring(4, 8).toUpperCase(Locale.US);
        }
        return u;
    }

    private static String props(int p) {
        StringBuilder b = new StringBuilder();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) b.append("read ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) b.append("write ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) b.append("notify ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) b.append("indicate ");
        return b.toString().trim();
    }
}
