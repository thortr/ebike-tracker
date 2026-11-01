package de.ebike.tracker;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Bosch eBike Live Data Interface.
 *
 * The phone plays the "accessory": it advertises the Live Data service UUID as a
 * solicitation, the eBike (GAP central) connects to us, and we then act as GATT client
 * on that link and subscribe to the Live Data characteristic (protobuf payload).
 */
final class BoschLink {
    static final UUID SVC = UUID.fromString("0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4");
    static final UUID CHR = UUID.fromString("0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Handler main;
    private final SharedPreferences prefs;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser adv;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chr;
    private BluetoothDevice bike;
    private boolean running, advertising, withName = true, receiverOn, pendingEnable, discovered;
    private int enableTries;
    private final Set<String> notBike = new HashSet<>();
    String ignoreAddr;

    BoschLink(Context c, Handler h) {
        ctx = c.getApplicationContext();
        main = h;
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private void status(int st, String msg) {
        State.I.boschStatus = st;
        State.I.boschMsg = msg;
    }

    void start() {
        if (running) return;
        BluetoothAdapter ad = adapter();
        if (ad == null || !ad.isEnabled()) { status(State.OFF, "Bluetooth ist aus"); return; }
        if (!ad.isMultipleAdvertisementSupported()) { status(State.OFF, "Dieses Handy kann nicht als Zubehör auftreten"); return; }
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            server = bm.openGattServer(ctx, serverCb);
        } catch (SecurityException e) {
            status(State.OFF, "Bluetooth-Berechtigung fehlt");
            return;
        }
        if (server == null) { status(State.OFF, "Bluetooth-Server nicht verfügbar"); return; }
        running = true;
        registerBondReceiver();
        startAdv();
    }

    void stop() {
        running = false;
        stopAdv();
        closeClient();
        if (server != null) {
            try { server.close(); } catch (SecurityException ignored) {}
            server = null;
        }
        if (receiverOn) {
            try { ctx.unregisterReceiver(bondReceiver); } catch (IllegalArgumentException ignored) {}
            receiverOn = false;
        }
        status(State.OFF, "Aus");
    }

    boolean isRunning() { return running; }

    /** Name the eBike will show when pairing. */
    String advertisedName() {
        try {
            BluetoothAdapter ad = adapter();
            return ad == null ? "?" : ad.getName();
        } catch (SecurityException e) {
            return "?";
        }
    }

    // ---------------------------------------------------------------- advertising

    private void startAdv() {
        if (!running || advertising || gatt != null) return;
        BluetoothAdapter ad = adapter();
        if (ad == null || !ad.isEnabled()) { status(State.OFF, "Bluetooth ist aus"); return; }
        adv = ad.getBluetoothLeAdvertiser();
        if (adv == null) { status(State.OFF, "Bluetooth ist aus"); return; }
        AdvertiseSettings.Builder sb = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0);
        if (Build.VERSION.SDK_INT >= 34) sb.setDiscoverable(true);
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceSolicitationUuid(new ParcelUuid(SVC))
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build();
        AdvertiseData resp = new AdvertiseData.Builder().setIncludeDeviceName(withName).build();
        try {
            adv.startAdvertising(sb.build(), data, resp, advCb);
            advertising = true;
            status(State.SEARCH, "Wartet auf eBike");
        } catch (SecurityException e) {
            status(State.OFF, "Bluetooth-Berechtigung fehlt");
        }
    }

    private void stopAdv() {
        if (adv != null && advertising) {
            try { adv.stopAdvertising(advCb); } catch (SecurityException | IllegalStateException ignored) {}
        }
        advertising = false;
    }

    private final AdvertiseCallback advCb = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            main.post(() -> {
                advertising = false;
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && withName) {
                    // Bluetooth name too long for the scan response: try without it.
                    withName = false;
                    startAdv();
                } else if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                    advertising = true;
                } else {
                    status(State.OFF, "Senden fehlgeschlagen (Code " + errorCode + ")");
                    main.postDelayed(BoschLink.this::startAdv, 10000);
                }
            });
        }
    };

    // ---------------------------------------------------------------- incoming link

    private final BluetoothGattServerCallback serverCb = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice d, int status, int newState) {
            main.post(() -> onLink(d, newState));
        }
    };

    private void onLink(BluetoothDevice d, int st) {
        if (!running || d == null) return;
        String a = d.getAddress();
        if (ignoreAddr != null && ignoreAddr.equalsIgnoreCase(a)) return; // our own HR connection
        if (st == BluetoothProfile.STATE_CONNECTED) {
            if (gatt != null || notBike.contains(a)) return;
            stopAdv();
            bike = d;
            discovered = false;
            enableTries = 0;
            status(State.SEARCH, "Verbinde mit eBike …");
            try {
                gatt = d.connectGatt(ctx, false, clientCb, BluetoothDevice.TRANSPORT_LE);
            } catch (SecurityException e) {
                status(State.OFF, "Bluetooth-Berechtigung fehlt");
            }
        } else if (st == BluetoothProfile.STATE_DISCONNECTED) {
            notBike.remove(a);
            if (bike != null && a.equalsIgnoreCase(bike.getAddress())) {
                closeClient();
                status(State.SEARCH, "Wartet auf eBike");
                main.postDelayed(this::startAdv, 600);
            }
        }
    }

    private void closeClient() {
        BluetoothGatt g = gatt;
        gatt = null;
        chr = null;
        bike = null;
        pendingEnable = false;
        if (g != null) {
            try { g.close(); } catch (SecurityException ignored) {}
        }
    }

    private void rejectCurrent() {
        if (bike != null) notBike.add(bike.getAddress());
        closeClient();
        status(State.SEARCH, "Wartet auf eBike");
        main.postDelayed(this::startAdv, 300);
    }

    // ---------------------------------------------------------------- GATT client on that link

    private void discover(BluetoothGatt g) {
        if (discovered || g != gatt) return;
        discovered = true;
        try { g.discoverServices(); } catch (SecurityException ignored) {}
    }

    private void enable(BluetoothGatt g) {
        if (g != gatt || chr == null) return;
        try {
            g.setCharacteristicNotification(chr, true);
            BluetoothGattDescriptor d = chr.getDescriptor(CCCD);
            if (d != null) HrLink.writeEnable(g, d);
            else g.readCharacteristic(chr);
        } catch (SecurityException ignored) {}
    }

    private final BluetoothGattCallback clientCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try { g.requestMtu(247); } catch (SecurityException ignored) {}
                    // Fallback if the MTU answer never arrives.
                    main.postDelayed(() -> discover(g), 2500);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    closeClient();
                    status(State.SEARCH, "Wartet auf eBike");
                    main.postDelayed(BoschLink.this::startAdv, 600);
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
                if (c == null) { rejectCurrent(); return; }   // some other device, not the bike
                chr = c;
                if (bike != null) {
                    State.I.boschAddr = bike.getAddress();
                    prefs.edit().putString("bosch_addr", bike.getAddress()).apply();
                }
                status(State.SEARCH, "eBike gefunden, aktiviere Daten …");
                enable(g);
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> {
                if (g != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    status(State.ON, "Verbunden");
                    try { g.readCharacteristic(chr); } catch (SecurityException ignored) {}
                    return;
                }
                int bond = BluetoothDevice.BOND_NONE;
                try { bond = g.getDevice().getBondState(); } catch (SecurityException ignored) {}
                if (bond != BluetoothDevice.BOND_BONDED) {
                    pendingEnable = true;
                    status(State.SEARCH, "Kopplung … bitte am eBike bestätigen");
                    if (bond == BluetoothDevice.BOND_NONE) {
                        try { g.getDevice().createBond(); } catch (SecurityException ignored) {}
                    }
                } else if (++enableTries <= 3) {
                    main.postDelayed(() -> enable(g), 1200);
                } else {
                    status(State.SEARCH, "eBike lehnt Daten ab (Code " + status + ")");
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) handle(value);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < 33 && status == BluetoothGatt.GATT_SUCCESS) handle(c.getValue());
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

    private void handle(byte[] v) {
        if (v == null || v.length == 0) return;
        byte[] copy = v.clone();
        long now = System.currentTimeMillis();
        main.post(() -> {
            State s = State.I;
            Proto.decode(copy, s);
            s.boschTime = now;
            if (s.boschStatus != State.ON) status(State.ON, "Verbunden");
        });
    }

    // ---------------------------------------------------------------- bonding

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context c, Intent i) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            if (d == null || bike == null || !d.getAddress().equalsIgnoreCase(bike.getAddress())) return;
            if (st == BluetoothDevice.BOND_BONDED && pendingEnable) {
                pendingEnable = false;
                BluetoothGatt g = gatt;
                main.postDelayed(() -> enable(g), 500);
            } else if (st == BluetoothDevice.BOND_NONE && pendingEnable) {
                status(State.SEARCH, "Kopplung abgelehnt oder abgebrochen");
            }
        }
    };

    private void registerBondReceiver() {
        if (receiverOn) return;
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(bondReceiver, f, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(bondReceiver, f);
        receiverOn = true;
    }

    /** Removes the pairing with the bike (hidden API, may not work on every phone). */
    boolean forgetBike() {
        String a = prefs.getString("bosch_addr", null);
        BluetoothAdapter ad = adapter();
        if (a == null || ad == null) return false;
        try {
            BluetoothDevice d = ad.getRemoteDevice(a);
            Object r = d.getClass().getMethod("removeBond").invoke(d);
            prefs.edit().remove("bosch_addr").apply();
            return Boolean.TRUE.equals(r);
        } catch (Exception e) {
            return false;
        }
    }
}
