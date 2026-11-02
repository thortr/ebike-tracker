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
    private final Map<Integer, Integer> latest = new LinkedHashMap<>();
    private final List<BluetoothGattCharacteristic> subs = new ArrayList<>();
    private BluetoothGatt gatt;
    private BluetoothDevice target;
    private BluetoothGattCharacteristic reqChar;   // service 0010 / char 0012 (write)
    private long t0;
    private int subIdx, total, reqIdx;
    private boolean discovered, full;

    /** Signal IDs the Flow app explicitly subscribes to by writing to char 0012.
     *  The bike only streams these after the request; our passive capture missed them.
     *  Taken verbatim from a Bluetooth HCI snoop of a Flow ride (numeric/scalar signals). */
    private static final int[] REQ_KEYS = {
        0x8081,0x8082,0x8083,0x8084,0x8085,0x8086,0x8087,0x8088,0x808a,0x808b,0x808e,0x8090,
        0x8091,0x8096,0x8097,0x8098,0x809b,0x809c,0x80a4,0x80b0,0x80b1,0x80b4,0x80b6,0x80bc,
        0x80be,0x80c2,0x80ca,0x80cc,0x80cd,0x80d7,0x80d8,0x80d9,0x80dc,0x80e1,0x80e2,0x80e5,
        0x9801,0x9802,0x9803,0x9804,0x9805,0x9806,0x9807,0x9808,0x9809,0x980c,0x980d,0x980e,
        0x980f,0x9811,0x9812,0x9814,0x9815,0x9817,0x9818,0x9819,0x981a,0x981b,0x981c,0x981f,
        0x9820,0x9821,0x9825,0x9826,0x9827,0x9828,0x9829,0x982a,0x982b,0x982c,0x982d,0x982e,
        0x982f,0x9834,0x9835,0x9837,0x9838,0x9839,0x983a,0x983b,0x983c,0x983d,0x983e,0x983f,
        0x9842,0x9846,0x9848,0x9849,0x984e,0x9851,0x9854,0x9855,0x9857,0x985a,0x985b,0x985d,
        0x985e,0x985f,0x9860,0x9861,0x9862,0x9865,0x9868,0x9869,0x986a,0x986c,0x986d,0x9870,
        0x9871,0x9874,0x9875,0x987a,0x987d,0x987e,0x9880,0x9896,0x989c,0x989e,
        0xa011,0xa021,0xa022,0xa041,0xa042,0xa051,0xa061,0xa062,0xa063,0xa064,0xa065,0xa066,
        0xa069,0xa06b,0xa06e,0xa06f,0xa081,0xa083,0xa085,0xa086,0xa087,0xa088,0xa089,0xa08a,
        0xa08c,0xa08d,0xa0aa,0xa0b6,0xa0b9,0xa0c7,0xa0e2,0xa101,0xa105,0xa106,0xa107,0xa108,
        0xa109,0xa10a,0xa10b,0xa10c,0xa10e,0xa10f,0xa112,0xa121,0xa124,0xa151,0xa161,0xa163,
        0xa165,0xa169,0xa16a,0xa16b,0xa173,0xa176,0xa177,0xa17c,0xa181,0xa182,0xa183,0xa185,
        0xa186,0xa19b,0xa210,0xa21a,0xa21b,0xa21c,0xa21d,0xa21e,0xa21f,0xa220,0xa221,0xa241,
        0xa243,0xa246,0xa247,0xa248,0xa249,0xa24a,0xa24b,0xa24c,0xa24d,0xa250,0xa251,0xa254,
        0xa255,0xa256,0xa282,0xa316,
    };

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
        b.append("LIVE (Vermutung – mit eBike-Display vergleichen):\n");
        b.append("  Tempo   ~ ").append(dec(latest.get(0x9808), 100)).append(" km/h\n");
        b.append("  Stufe     ").append(val(latest.get(0x9809))).append("\n");
        b.append("  Trittfr.? ").append(val(latest.get(0x985a))).append("\n");
        b.append("  Leistung? A ").append(val(latest.get(0x985d))).append("   B ").append(val(latest.get(0x9815))).append("\n");
        b.append("  Akku/Reichw.? ").append(val(latest.get(0xa24a))).append("\n");
        b.append("\n").append(total).append(" Pakete").append(full ? " (Protokoll voll)" : "").append("\n");
        b.append("\nTipp: Fahre, dann HÖR KURZ AUF ZU TRETEN, während das Rad noch rollt. Der Wert, der dabei auf 0 fällt, ist Trittfrequenz bzw. Leistung.\n");
        b.append("\nDanach „Speichern & Teilen“ und mir die Datei schicken.");
        report.update(b.toString());
    }

    private static String val(Integer v) { return v == null ? "–" : String.valueOf(v); }

    private static String dec(Integer v, int div) {
        return v == null ? "–" : String.format(Locale.GERMANY, "%.1f", v / (double) div);
    }

    private void decode(byte[] p) {
        int i = 0;
        while (i + 1 < p.length) {
            if ((p[i] & 0xff) != 0x30) break;
            int ln = p[i + 1] & 0xff;
            int start = i + 2, end = start + ln;
            if (end > p.length) break;
            if (ln >= 3) {
                int key = ((p[start] & 0xff) << 8) | (p[start + 1] & 0xff);
                long[] tag = vInt(p, start + 2, end);
                if (tag != null && tag[0] == 0x08) {
                    long[] v = vInt(p, (int) tag[1], end);
                    if (v != null) latest.put(key, (int) v[0]);
                }
            }
            i = end;
        }
    }

    private static long[] vInt(byte[] b, int i, int end) {
        long v = 0; int s = 0;
        while (i < end) {
            int x = b[i++] & 0xff;
            v |= (long) (x & 0x7f) << s;
            if ((x & 0x80) == 0) return new long[]{v, i};
            s += 7;
        }
        return null;
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
                reqChar = null;
                for (BluetoothGattService s : g.getServices()) {
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        line("# dienst " + shortU(s.getUuid().toString()) + " merkmal " + shortU(c.getUuid().toString()) + " props=" + c.getProperties());
                        int p = c.getProperties();
                        if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) subs.add(c);
                        if ("0012".equals(shortU(c.getUuid().toString()))) reqChar = c;
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
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> { if (g == gatt) sendRequests(g); });
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
            reqIdx = 0;
            sendRequests(g);
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

    /** After subscribing to notifications, request the extra signals the way the Flow
     *  app does: write 0x30-framed "subscribe" commands (30 05 40 80 KEY 08) to char 0012.
     *  Sent in chunks; each chunk is written when the previous write completes. */
    private void sendRequests(BluetoothGatt g) {
        if (g != gatt) return;
        if (reqChar == null) {
            line("# keine Anfrage-Characteristic 0012 gefunden – nur Standard-Signale");
            status();
            return;
        }
        if (reqIdx >= REQ_KEYS.length) {
            line("# " + REQ_KEYS.length + " Signale bei 0012 angefordert (wie Flow)");
            status();
            return;
        }
        int perWrite = 28;
        int end = Math.min(reqIdx + perWrite, REQ_KEYS.length);
        byte[] buf = new byte[(end - reqIdx) * 7];
        int o = 0;
        for (int i = reqIdx; i < end; i++) {
            int key = REQ_KEYS[i];
            buf[o++] = 0x30; buf[o++] = 0x05; buf[o++] = 0x40; buf[o++] = (byte) 0x80;
            buf[o++] = (byte) ((key >> 8) & 0xff);
            buf[o++] = (byte) (key & 0xff);
            buf[o++] = 0x08;
        }
        reqIdx = end;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(reqChar, buf, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                reqChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                reqChar.setValue(buf);
                g.writeCharacteristic(reqChar);
            }
        } catch (SecurityException e) {
            line("# Anfrage fehlgeschlagen: " + e.getMessage());
            status();
        }
    }

    private void rx(BluetoothGattCharacteristic c, byte[] v) {
        if (v == null) return;
        String ch = shortU(c.getUuid().toString());
        total++;
        counts.put(ch, (counts.getOrDefault(ch, 0)) + 1);
        line((System.currentTimeMillis() - t0) + "  " + ch + "  " + hex(v));
        if ("0011".equals(ch)) decode(v);
        if (total % 4 == 0 || total < 5) status();
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
