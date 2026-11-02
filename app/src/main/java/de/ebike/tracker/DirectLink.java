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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;

import java.util.Locale;

/**
 * Connect to the eBike as CENTRAL (like the Flow app), force a fresh Secure-Connections
 * bond (Just Works) BEFORE subscribing — the Bosch docs state the bike sends no notify
 * data until that bond is established — then enable notifications on the Live Data
 * characteristic and show the values. This is the role Android supports well.
 */
final class DirectLink {
    interface Report { void update(String text); }

    private final Context ctx;
    private final Handler main;
    private final Report report;
    private final StringBuilder log = new StringBuilder();
    private BluetoothGatt gatt;
    private BluetoothDevice target;
    private BluetoothGattCharacteristic ldiChar;
    private int packets, pollTicks;
    private boolean bonding, discovered, receiverOn;

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
            target = ad.getRemoteDevice(address);
            registerBond();
            out("Verbinde als Zentrale mit " + (name == null || name.isEmpty() ? address : name) + " …");
            gatt = target.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException | IllegalArgumentException e) {
            out("Fehler beim Verbinden: " + e.getMessage());
        }
    }

    void close() {
        BluetoothGatt g = gatt;
        gatt = null;
        main.removeCallbacks(poll);
        if (receiverOn) {
            try { ctx.unregisterReceiver(bondRcv); } catch (IllegalArgumentException ignored) {}
            receiverOn = false;
        }
        if (g != null) {
            try { g.disconnect(); g.close(); } catch (SecurityException ignored) {}
        }
    }

    // ---- bonding first -------------------------------------------------------

    private void registerBond() {
        if (receiverOn) return;
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(bondRcv, f, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(bondRcv, f);
        receiverOn = true;
    }

    private final BroadcastReceiver bondRcv = new BroadcastReceiver() {
        @SuppressWarnings("deprecation")
        @Override
        public void onReceive(Context c, Intent i) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null || target == null || !d.getAddress().equalsIgnoreCase(target.getAddress())) return;
            int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            if (st == BluetoothDevice.BOND_BONDED && bonding) {
                bonding = false;
                out("Kopplung hergestellt. Suche Dienste …");
                main.postDelayed(() -> startDiscovery(gatt), 400);
            } else if (st == BluetoothDevice.BOND_NONE && bonding) {
                out("Kopplung abgelehnt oder abgebrochen.");
            }
        }
    };

    private void afterConnect(BluetoothGatt g) {
        if (g != gatt || target == null) return;
        int bond = BluetoothDevice.BOND_NONE;
        try { bond = target.getBondState(); } catch (SecurityException ignored) {}
        if (bond == BluetoothDevice.BOND_BONDED) {
            out("Bereits gekoppelt. Suche Dienste …");
            startDiscovery(g);
        } else {
            out("Noch nicht gekoppelt, fordere Kopplung an (am eBike bestätigen) …");
            bonding = true;
            try { target.createBond(); } catch (SecurityException ignored) {}
        }
    }

    private void startDiscovery(BluetoothGatt g) {
        if (g == null || g != gatt || discovered) return;
        discovered = true;
        try { g.requestMtu(247); } catch (SecurityException ignored) {}
        main.postDelayed(() -> { if (g == gatt) try { g.discoverServices(); } catch (SecurityException ignored) {} }, 700);
    }

    // ---- poll fallback -------------------------------------------------------

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            BluetoothGatt g = gatt;
            if (g == null || ldiChar == null) return;
            pollTicks++;
            try { g.readCharacteristic(ldiChar); } catch (SecurityException ignored) {}
            if (packets == 0 && pollTicks == 8) out("Noch keine Daten nach 8 Sekunden. Bitte unter Last fahren.");
            main.postDelayed(this, 1000);
        }
    };

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    out("Verbunden (Status " + status + ").");
                    main.postDelayed(() -> afterConnect(g), 300);
                } else {
                    out("Getrennt (Status " + status + ")."
                            + (status == 133 ? " Tipp: Flow schließen, eBike an, erneut." : ""));
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
                if (g.getServices().isEmpty()) {
                    out("Dienstliste noch leer, versuche erneut …");
                    main.postDelayed(() -> { if (g == gatt) try { g.discoverServices(); } catch (SecurityException ignored) {} }, 800);
                    return;
                }
                BluetoothGattService svc = g.getService(BoschLink.SVC);
                BluetoothGattCharacteristic c = svc == null ? null : svc.getCharacteristic(BoschLink.CHR);
                if (c == null) {
                    out(">>> Kein Live-Data-Dienst (eb20) auf dieser Verbindung.");
                    return;
                }
                ldiChar = c;
                out("Live-Data-Merkmal gefunden. Aktiviere Benachrichtigungen …");
                try {
                    g.setCharacteristicNotification(c, true);
                    BluetoothGattDescriptor d = c.getDescriptor(BoschLink.CCCD);
                    if (d != null) HrLink.writeEnable(g, d);
                    else g.readCharacteristic(c);
                } catch (SecurityException ignored) {}
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> {
                if (g != gatt) return;
                out("Benachrichtigung aktiviert (Status " + status + "). Jetzt unter Last fahren …");
                main.removeCallbacks(poll);
                pollTicks = 0;
                main.postDelayed(poll, 1000);
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            main.post(() -> handle(value, status, false));
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < 33) main.post(() -> handle(c.getValue(), status, false));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            main.post(() -> handle(value, 0, true));
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) main.post(() -> handle(c.getValue(), 0, true));
        }
    };

    private void handle(byte[] v, int status, boolean notify) {
        if (v == null || v.length == 0) {
            if (notify) out("Leere Benachrichtigung.");
            return;
        }
        State s = State.I;
        int known = Proto.decode(v, s);
        packets++;
        out(String.format(Locale.GERMANY, "%s %d: %d Byte, %d Felder — Leistung %s W, Kadenz %s, Tempo %s, Akku %s%%",
                notify ? "Benachrichtigung" : "Gelesen", packets, v.length, known,
                s.power >= 0 ? String.valueOf(s.power) : "-",
                s.cadence >= 0 ? String.valueOf(s.cadence) : "-",
                Float.isNaN(s.bikeSpeed) ? "-" : String.format(Locale.GERMANY, "%.1f", s.bikeSpeed),
                s.soc >= 0 ? String.valueOf(s.soc) : "-"));
    }
}
