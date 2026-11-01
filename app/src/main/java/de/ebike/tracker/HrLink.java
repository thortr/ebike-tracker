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

/** Standard Bluetooth heart rate sensor (Heart Rate Service 0x180D). */
final class HrLink {
    static final UUID HRS = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    static final UUID HRM = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Handler main;
    private BluetoothGatt gatt;
    String addr;
    private String name;

    HrLink(Context c, Handler h) {
        ctx = c.getApplicationContext();
        main = h;
    }

    void connect(String address, String devName) {
        if (address != null && address.equalsIgnoreCase(addr) && gatt != null) return;
        close();
        addr = address;
        name = devName;
        State s = State.I;
        if (address == null) {
            s.hrStatus = State.OFF;
            s.hrMsg = "Kein Pulsgurt gewählt";
            return;
        }
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            s.hrStatus = State.OFF;
            s.hrMsg = "Bluetooth ist aus";
            return;
        }
        try {
            BluetoothDevice d = ad.getRemoteDevice(address);
            s.hrStatus = State.SEARCH;
            s.hrMsg = "Suche " + label();
            gatt = d.connectGatt(ctx, true, cb, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException | IllegalArgumentException e) {
            s.hrStatus = State.OFF;
            s.hrMsg = "Bluetooth-Berechtigung fehlt";
        }
    }

    private String label() {
        return name == null || name.isEmpty() ? "Pulsgurt" : name;
    }

    void close() {
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try { g.close(); } catch (SecurityException ignored) {}
        }
        addr = null;
        State.I.hrStatus = State.OFF;
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                State s = State.I;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    s.hrMsg = "Verbinde " + label();
                    try { g.discoverServices(); } catch (SecurityException ignored) {}
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    s.hrStatus = State.SEARCH;
                    s.hrMsg = "Suche " + label();
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        // Some stacks give up after an error; start a fresh attempt.
                        String a = addr, n = name;
                        main.postDelayed(() -> { if (gatt == g) { close(); connect(a, n); } }, 4000);
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g != gatt) return;
                BluetoothGattService svc = g.getService(HRS);
                BluetoothGattCharacteristic c = svc == null ? null : svc.getCharacteristic(HRM);
                if (c == null) {
                    State.I.hrMsg = label() + " hat keinen Pulsdienst";
                    return;
                }
                try {
                    g.setCharacteristicNotification(c, true);
                    BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                    if (d != null) writeEnable(g, d);
                } catch (SecurityException ignored) {}
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            handle(value);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) handle(c.getValue());
        }
    };

    @SuppressWarnings("deprecation")
    static void writeEnable(BluetoothGatt g, BluetoothGattDescriptor d) throws SecurityException {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(d);
        }
    }

    private void handle(byte[] v) {
        if (v == null || v.length < 2) return;
        int flags = v[0] & 0xff;
        int bpm = (flags & 1) != 0 && v.length >= 3 ? (v[1] & 0xff) | ((v[2] & 0xff) << 8) : v[1] & 0xff;
        long now = System.currentTimeMillis();
        main.post(() -> {
            State s = State.I;
            s.hr = bpm;
            s.hrTime = now;
            s.hrStatus = State.ON;
            s.hrMsg = label() + " verbunden";
        });
    }
}
