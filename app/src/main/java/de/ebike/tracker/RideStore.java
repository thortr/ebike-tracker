package de.ebike.tracker;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Rides are stored as one CSV per ride plus a small summary file. */
final class RideStore {
    private RideStore() {}

    static final String HEADER = "t,lat,lon,alt,speed,hr,power,cadence,bike_speed,soc,odo,dist,gps_speed,acc,assist,motor,range";

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "rides");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File csvFor(File dir, long start) { return new File(dir, "ride_" + start + ".csv"); }

    static File metaFor(File csv) { return new File(csv.getParentFile(), csv.getName().replace(".csv", ".meta")); }

    // ------------------------------------------------------------------ model

    static final class Samples {
        int n;
        long[] t;
        double[] lat, lon;
        float[] alt, speed, bikeSpeed, gpsSpeed, acc, dist;
        int[] hr, power, cadence, soc, assist, motor, range;
        long[] odo;

        Samples(int cap) {
            t = new long[cap]; lat = new double[cap]; lon = new double[cap];
            alt = new float[cap]; speed = new float[cap]; bikeSpeed = new float[cap]; gpsSpeed = new float[cap];
            acc = new float[cap]; dist = new float[cap];
            hr = new int[cap]; power = new int[cap]; cadence = new int[cap]; soc = new int[cap];
            assist = new int[cap]; motor = new int[cap]; range = new int[cap]; odo = new long[cap];
        }

        void grow() {
            int c = t.length * 2;
            t = Arrays.copyOf(t, c); lat = Arrays.copyOf(lat, c); lon = Arrays.copyOf(lon, c);
            alt = Arrays.copyOf(alt, c); speed = Arrays.copyOf(speed, c); bikeSpeed = Arrays.copyOf(bikeSpeed, c);
            gpsSpeed = Arrays.copyOf(gpsSpeed, c); acc = Arrays.copyOf(acc, c); dist = Arrays.copyOf(dist, c);
            hr = Arrays.copyOf(hr, c); power = Arrays.copyOf(power, c); cadence = Arrays.copyOf(cadence, c);
            soc = Arrays.copyOf(soc, c); assist = Arrays.copyOf(assist, c); motor = Arrays.copyOf(motor, c);
            range = Arrays.copyOf(range, c); odo = Arrays.copyOf(odo, c);
        }

        boolean hasAny(int[] a) {
            for (int i = 0; i < n; i++) if (a[i] >= 0) return true;
            return false;
        }

        boolean hasAny(float[] a) {
            for (int i = 0; i < n; i++) if (!Float.isNaN(a[i])) return true;
            return false;
        }

        boolean hasTrack() {
            for (int i = 0; i < n; i++) if (!Double.isNaN(lat[i])) return true;
            return false;
        }
    }

    static final class Ride {
        File csv;
        long start, durationMs, movingMs;
        double distM, gainM, lossM;
        float maxSpeed, avgSpeed;
        int avgHr = -1, maxHr = -1, avgPower = -1, maxPower = -1, avgCad = -1, socStart = -1, socEnd = -1, points;
    }

    // ------------------------------------------------------------------ reading

    static Samples load(File csv) throws IOException {
        Samples s = new Samples(4096);
        try (BufferedReader r = new BufferedReader(new FileReader(csv), 1 << 16)) {
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",", -1);
                if (p.length < 12) continue;
                if (s.n == s.t.length) s.grow();
                int i = s.n;
                try {
                    s.t[i] = Long.parseLong(p[0]);
                } catch (NumberFormatException e) {
                    continue;
                }
                s.lat[i] = d(p[1]); s.lon[i] = d(p[2]);
                s.alt[i] = f(p[3]); s.speed[i] = f(p[4]);
                s.hr[i] = n(p[5]); s.power[i] = n(p[6]); s.cadence[i] = n(p[7]);
                s.bikeSpeed[i] = f(p[8]); s.soc[i] = n(p[9]);
                s.odo[i] = p[10].isEmpty() ? -1 : parseLong(p[10]);
                s.dist[i] = f(p[11]);
                s.gpsSpeed[i] = p.length > 12 ? f(p[12]) : Float.NaN;
                s.acc[i] = p.length > 13 ? f(p[13]) : Float.NaN;
                s.assist[i] = p.length > 14 ? n(p[14]) : -1;
                s.motor[i] = p.length > 15 ? n(p[15]) : -1;
                s.range[i] = p.length > 16 ? n(p[16]) : -1;
                s.n++;
            }
        }
        smoothAltitude(s);
        return s;
    }

    /** Barometric altitude picks up a few metres of slow wander from wind and weather while
     *  riding. Flatten the stored profile with a centred moving average (~±20 s) so the chart
     *  and the climb total stay realistic on flat ground; real hills last minutes and survive. */
    static void smoothAltitude(Samples s) {
        int n = s.n;
        if (n < 5) return;
        final int R = 10;                 // ±10 samples ≈ 20 s at 1 Hz
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double sum = 0; int cnt = 0;
            int lo = Math.max(0, i - R), hi = Math.min(n - 1, i + R);
            for (int j = lo; j <= hi; j++) {
                if (!Float.isNaN(s.alt[j])) { sum += s.alt[j]; cnt++; }
            }
            out[i] = cnt > 0 ? (float) (sum / cnt) : Float.NaN;
        }
        System.arraycopy(out, 0, s.alt, 0, n);
    }

    private static long parseLong(String v) {
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return -1; }
    }

    private static double d(String v) {
        if (v.isEmpty()) return Double.NaN;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static float f(String v) {
        if (v.isEmpty()) return Float.NaN;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return Float.NaN; }
    }

    private static int n(String v) {
        if (v.isEmpty()) return -1;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return -1; }
    }

    // ------------------------------------------------------------------ summary

    static Ride summarize(File csv, Samples s) {
        Ride r = new Ride();
        r.csv = csv;
        r.points = s.n;
        try {
            r.start = Long.parseLong(csv.getName().replace("ride_", "").replace(".csv", ""));
        } catch (NumberFormatException e) {
            r.start = s.n > 0 ? s.t[0] : csv.lastModified();
        }
        double sumHr = 0, sumP = 0, sumC = 0;
        int nHr = 0, nP = 0, nC = 0;
        double ref = Double.NaN;
        for (int i = 0; i < s.n; i++) {
            long dt = i == 0 ? 1000 : Math.min(2000, Math.max(0, s.t[i] - s.t[i - 1]));
            r.durationMs += dt;
            float v = s.speed[i];
            boolean moving = !Float.isNaN(v) && v >= 2f;
            if (moving) r.movingMs += dt;
            if (!Float.isNaN(v) && v > r.maxSpeed) r.maxSpeed = v;
            if (s.hr[i] > 0) { sumHr += s.hr[i]; nHr++; r.maxHr = Math.max(r.maxHr, s.hr[i]); }
            if (s.power[i] >= 0) {
                if (moving) { sumP += s.power[i]; nP++; }
                r.maxPower = Math.max(r.maxPower, s.power[i]);
            }
            if (s.cadence[i] > 0) { sumC += s.cadence[i]; nC++; }
            if (s.soc[i] >= 0) { if (r.socStart < 0) r.socStart = s.soc[i]; r.socEnd = s.soc[i]; }
            float a = s.alt[i];
            if (!Float.isNaN(a)) {
                if (Double.isNaN(ref)) ref = a;
                else if (a - ref >= 3) { r.gainM += a - ref; ref = a; }
                else if (ref - a >= 3) { r.lossM += ref - a; ref = a; }
            }
            if (!Float.isNaN(s.dist[i])) r.distM = s.dist[i];
        }
        if (nHr > 0) r.avgHr = (int) Math.round(sumHr / nHr);
        if (nP > 0) r.avgPower = (int) Math.round(sumP / nP);
        if (nC > 0) r.avgCad = (int) Math.round(sumC / nC);
        r.avgSpeed = r.movingMs > 0 ? (float) (r.distM / (r.movingMs / 1000.0) * 3.6) : 0f;
        return r;
    }

    static void writeMeta(Ride r) {
        Properties p = new Properties();
        p.setProperty("start", String.valueOf(r.start));
        p.setProperty("duration", String.valueOf(r.durationMs));
        p.setProperty("moving", String.valueOf(r.movingMs));
        p.setProperty("dist", String.valueOf(r.distM));
        p.setProperty("gain", String.valueOf(r.gainM));
        p.setProperty("loss", String.valueOf(r.lossM));
        p.setProperty("maxSpeed", String.valueOf(r.maxSpeed));
        p.setProperty("avgSpeed", String.valueOf(r.avgSpeed));
        p.setProperty("avgHr", String.valueOf(r.avgHr));
        p.setProperty("maxHr", String.valueOf(r.maxHr));
        p.setProperty("avgPower", String.valueOf(r.avgPower));
        p.setProperty("maxPower", String.valueOf(r.maxPower));
        p.setProperty("avgCad", String.valueOf(r.avgCad));
        p.setProperty("socStart", String.valueOf(r.socStart));
        p.setProperty("socEnd", String.valueOf(r.socEnd));
        p.setProperty("points", String.valueOf(r.points));
        try (FileOutputStream o = new FileOutputStream(metaFor(r.csv))) {
            p.store(o, null);
        } catch (IOException ignored) {}
    }

    private static Ride readMeta(File csv) {
        File m = metaFor(csv);
        if (!m.exists()) return null;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(m)) {
            p.load(in);
            Ride r = new Ride();
            r.csv = csv;
            r.start = Long.parseLong(p.getProperty("start"));
            r.durationMs = Long.parseLong(p.getProperty("duration"));
            r.movingMs = Long.parseLong(p.getProperty("moving"));
            r.distM = Double.parseDouble(p.getProperty("dist"));
            r.gainM = Double.parseDouble(p.getProperty("gain"));
            r.lossM = Double.parseDouble(p.getProperty("loss"));
            r.maxSpeed = Float.parseFloat(p.getProperty("maxSpeed"));
            r.avgSpeed = Float.parseFloat(p.getProperty("avgSpeed"));
            r.avgHr = Integer.parseInt(p.getProperty("avgHr"));
            r.maxHr = Integer.parseInt(p.getProperty("maxHr"));
            r.avgPower = Integer.parseInt(p.getProperty("avgPower"));
            r.maxPower = Integer.parseInt(p.getProperty("maxPower"));
            r.avgCad = Integer.parseInt(p.getProperty("avgCad"));
            r.socStart = Integer.parseInt(p.getProperty("socStart"));
            r.socEnd = Integer.parseInt(p.getProperty("socEnd"));
            r.points = Integer.parseInt(p.getProperty("points"));
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** All rides, newest first. Rides without summary (e.g. after a crash) are recovered. */
    static List<Ride> list(Context c, long skipStart) {
        File[] files = dir(c).listFiles((d, name) -> name.startsWith("ride_") && name.endsWith(".csv"));
        List<Ride> out = new ArrayList<>();
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (File f : files) {
            if (f.getName().equals("ride_" + skipStart + ".csv")) continue; // ride currently recording
            Ride r = readMeta(f);
            if (r == null) {
                try {
                    Samples s = load(f);
                    if (s.n < 5) continue;
                    r = summarize(f, s);
                    writeMeta(r);
                } catch (IOException e) {
                    continue;
                }
            }
            out.add(r);
        }
        out.sort((a, b) -> Long.compare(b.start, a.start));
        return out;
    }

    static void delete(Ride r) {
        r.csv.delete();
        metaFor(r.csv).delete();
    }

    // ------------------------------------------------------------------ writing

    static final class Writer {
        final File file;
        private final BufferedWriter w;
        private int pending;

        Writer(File dir, long start) throws IOException {
            file = csvFor(dir, start);
            boolean exists = file.exists();
            w = new BufferedWriter(new FileWriter(file, true));
            if (!exists) { w.write(HEADER); w.newLine(); }
        }

        void write(long t, double lat, double lon, double alt, float speed, int hr, int power, int cad,
                   float bikeSpeed, int soc, long odo, double dist, float gpsSpeed, float acc, int assist, int motor, int range) {
            StringBuilder b = new StringBuilder(120);
            b.append(t).append(',');
            if (!Double.isNaN(lat)) b.append(String.format(Locale.US, "%.6f", lat));
            b.append(',');
            if (!Double.isNaN(lon)) b.append(String.format(Locale.US, "%.6f", lon));
            b.append(',');
            if (!Double.isNaN(alt)) b.append(String.format(Locale.US, "%.1f", alt));
            b.append(',');
            if (!Float.isNaN(speed)) b.append(String.format(Locale.US, "%.2f", speed));
            b.append(',');
            if (hr > 0) b.append(hr);
            b.append(',');
            if (power >= 0) b.append(power);
            b.append(',');
            if (cad >= 0) b.append(cad);
            b.append(',');
            if (!Float.isNaN(bikeSpeed)) b.append(String.format(Locale.US, "%.2f", bikeSpeed));
            b.append(',');
            if (soc >= 0) b.append(soc);
            b.append(',');
            if (odo >= 0) b.append(odo);
            b.append(',');
            b.append(String.format(Locale.US, "%.1f", dist));
            b.append(',');
            if (!Float.isNaN(gpsSpeed)) b.append(String.format(Locale.US, "%.2f", gpsSpeed));
            b.append(',');
            if (!Float.isNaN(acc)) b.append(String.format(Locale.US, "%.0f", acc));
            b.append(',');
            if (assist >= 0) b.append(assist);
            b.append(',');
            if (motor >= 0) b.append(motor);
            b.append(',');
            if (range >= 0) b.append(range);
            try {
                w.write(b.toString());
                w.newLine();
                if (++pending >= 10) { w.flush(); pending = 0; }
            } catch (IOException ignored) {}
        }

        void close() {
            try { w.flush(); w.close(); } catch (IOException ignored) {}
        }
    }
}
