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

import java.util.UUID;

/**
 * Live connection to the Bosch eBike as CENTRAL (the way the Flow app connects).
 * Subscribes to the bike's private live-data channel (service 0010, characteristic
 * 0011) and decodes the signals into {@link State}. Reconnects automatically.
 *
 * Signal mapping was reverse-engineered and verified against the Flow app:
 *   9808 = speed x100 (km/h) · 985d = rider power (W) · 985a = cadence x2 (rpm)
 *   9809 = assist level (1-4) · 9818 = odometer (m) · 8088 = battery %
 *   a254 = range (km) · 8091 = remaining energy (Wh x10) · a243 = trip time (s)
 *   a251 = trip distance · a246/a247 = avg/max speed · a248/a249 = avg/max cadence
 *   a24a/a24b = avg/max rider power · 9815/9814 = rider/motor torque (experimental)
 */
final class BikeLink {
    // Rider-power calibration: signal 985b reads ~1.1x Flow's rider power, so scale to match.
    static final float RIDER_CAL = 0.9f;

    static final UUID SVC = UUID.fromString("00000010-eaa2-11e9-81b4-2a2ae2dbcce4");
    static final UUID CHR = UUID.fromString("00000011-eaa2-11e9-81b4-2a2ae2dbcce4");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Handler main;
    private String addr;
    private BluetoothGatt gatt;
    private boolean running, discovered;

    BikeLink(Context c, Handler h) {
        ctx = c.getApplicationContext();
        main = h;
    }

    void start(String address) {
        if (running && address != null && address.equalsIgnoreCase(addr)) return;
        stop();
        addr = address;
        if (address == null) { status(State.OFF, "Kein eBike gewählt"); return; }
        running = true;
        State.I.boschAddr = address;
        connect();
    }

    void stop() {
        running = false;
        discovered = false;
        main.removeCallbacks(reconnect);
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) { try { g.disconnect(); g.close(); } catch (SecurityException ignored) {} }
        status(State.OFF, "Aus");
    }

    boolean isRunning() { return running; }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private void status(int st, String msg) {
        State.I.boschStatus = st;
        State.I.boschMsg = msg;
    }

    private final Runnable reconnect = this::connect;

    private void scheduleReconnect() {
        if (running) { main.removeCallbacks(reconnect); main.postDelayed(reconnect, 5000); }
    }

    private void connect() {
        if (!running || addr == null) return;
        BluetoothAdapter ad = adapter();
        if (ad == null || !ad.isEnabled()) { status(State.OFF, "Bluetooth ist aus"); scheduleReconnect(); return; }
        try {
            BluetoothDevice d = ad.getRemoteDevice(addr);
            discovered = false;
            status(State.SEARCH, "Verbinde mit eBike …");
            gatt = d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException | IllegalArgumentException e) {
            status(State.OFF, "Bluetooth-Berechtigung fehlt");
        }
    }

    private void discover(BluetoothGatt g) {
        if (g != gatt || discovered) return;
        discovered = true;
        try { g.discoverServices(); } catch (SecurityException ignored) {}
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status(State.SEARCH, "eBike verbunden, lese Daten …");
                    try { g.requestMtu(247); } catch (SecurityException ignored) {}
                    main.postDelayed(() -> discover(g), 1500);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    BluetoothGatt gg = gatt;
                    gatt = null;
                    discovered = false;
                    if (gg != null) { try { gg.close(); } catch (SecurityException ignored) {} }
                    status(State.SEARCH, "Warte auf eBike");
                    scheduleReconnect();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            main.post(() -> discover(g));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g != gatt) return;
                BluetoothGattService svc = g.getService(SVC);
                BluetoothGattCharacteristic c = svc == null ? null : svc.getCharacteristic(CHR);
                if (c == null) { status(State.SEARCH, "eBike-Datenkanal nicht gefunden"); return; }
                try {
                    g.setCharacteristicNotification(c, true);
                    BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                    if (d != null) {
                        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        else { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }
                    }
                } catch (SecurityException ignored) {}
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            main.post(() -> handle(value));
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) main.post(() -> handle(c.getValue()));
        }
    };

    /** Decode one notification: a series of 0x30-length-prefixed records, each a
     *  2-byte signal key followed by protobuf field 1 (the value). */
    private void handle(byte[] p) {
        if (p == null) return;
        State s = State.I;
        boolean got = false;
        int i = 0;
        while (i + 1 < p.length) {
            if ((p[i] & 0xff) != 0x30) break;
            int ln = p[i + 1] & 0xff, start = i + 2, end = start + ln;
            if (end > p.length) break;
            if (ln >= 3) {
                int key = ((p[start] & 0xff) << 8) | (p[start + 1] & 0xff);
                long[] tag = vInt(p, start + 2, end);
                if (tag != null && tag[0] == 0x08) {
                    long[] val = vInt(p, (int) tag[1], end);
                    if (val != null) {
                        int v = (int) val[0];
                        got = true;
                        long now = System.currentTimeMillis();
                        switch (key) {
                            // --- live values ---
                            case 0x9808: s.bikeSpeed = v / 100f; s.bikeSpeedTime = now; break;
                            // 985b = RIDER power (verified vs Flow; 985b ≈ 1.1x Flow, so x0.9 to match)
                            case 0x985b: s.power = Math.round(v * RIDER_CAL); s.powerTime = now; break;
                            // 985d = MOTOR power in watts (real, not an estimate)
                            case 0x985d: s.motorPower = v; s.motorTime = now; break;
                            case 0x985a: s.cadence = v / 2; s.cadTime = now; break;
                            case 0x9809: s.assist = v; break;
                            case 0x9818: s.odo = v; break;
                            case 0x8088: s.soc = v; break;
                            case 0x9815: s.riderTorque = v; break;   // rises with rider power
                            case 0x9814: s.motorTorque = v; break;   // motor torque curve
                            case 0x8091: s.energyWh = v / 10f; break; // remaining energy, Wh
                            // --- range / trip totals the bike keeps ---
                            case 0xa254: s.range = v; break;         // remaining range, km
                            case 0xa243: s.tripTimeS = v; break;     // trip time, s
                            case 0xa251: s.tripDist = v; break;      // trip distance
                            // --- trip statistics (verified against Flow) ---
                            case 0xa246: s.trAvgSpeed = v / 100f; break;
                            case 0xa247: s.trMaxSpeed = v / 100f; break;
                            case 0xa248: s.trAvgCad = v / 2; break;
                            case 0xa249: s.trMaxCad = v / 2; break;
                            case 0xa24a: s.trAvgPower = v; break;
                            case 0xa24b: s.trMaxPower = v; break;
                            default: break;
                        }
                    }
                }
            }
            i = end;
        }
        if (got) {
            s.boschTime = System.currentTimeMillis();
            if (s.boschStatus != State.ON) status(State.ON, "Verbunden");
        }
    }

    private static long[] vInt(byte[] b, int i, int end) {
        long v = 0; int sh = 0;
        while (i < end) {
            int x = b[i++] & 0xff;
            v |= (long) (x & 0x7f) << sh;
            if ((x & 0x80) == 0) return new long[]{v, i};
            sh += 7;
        }
        return null;
    }
}
