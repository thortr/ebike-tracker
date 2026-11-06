package de.ebike.tracker;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;

/**
 * Keeps GPS, heart rate sensor and the Bosch link alive and records the ride once per second.
 * Runs as a foreground service so recording continues with the screen off.
 */
public class TrackerService extends Service implements LocationListener, SensorEventListener {
    static TrackerService instance;

    private static final String CHANNEL = "rec";
    private static final int NOTIF_ID = 1;

    private final Handler h = new Handler(Looper.getMainLooper());
    private LocationManager lm;
    private SensorManager sm;
    private PowerManager.WakeLock wl;
    private HrLink hr;
    private BikeLink bike;
    private RideStore.Writer writer;
    private SharedPreferences prefs;

    private float pressure = Float.NaN;
    private double baroOffset = Double.NaN;
    private int offsetLocks = 0;
    private double altSmooth = Double.NaN;
    private double gpsMsl = Double.NaN;
    private long gpsAltTime;

    private double prevLat = Double.NaN, prevLon = Double.NaN, refAlt = Double.NaN;
    private long lastOdo = -1, lastTick;
    private int tickCount;
    private boolean locationOn;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            doTick();
            h.postDelayed(this, 1000);
        }
    };

    static boolean has(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    static void ensureStarted(Context c) {
        if (instance != null) { instance.refreshLinks(); return; }
        if (!has(c, Manifest.permission.ACCESS_FINE_LOCATION) && !has(c, Manifest.permission.BLUETOOTH_CONNECT)) return;
        try {
            c.startForegroundService(new Intent(c, TrackerService.class));
        } catch (RuntimeException ignored) {
            // Not allowed from the background; the next activity start will retry.
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        createChannel();
        int types = 0;
        if (has(this, Manifest.permission.ACCESS_FINE_LOCATION)) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        if (has(this, Manifest.permission.BLUETOOTH_CONNECT)) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        try {
            startForeground(NOTIF_ID, buildNotification(), types);
        } catch (RuntimeException e) {
            stopSelf();
            return;
        }
        hr = new HrLink(this, h);
        bike = new BikeLink(this, h);
        startSensors();
        lastTick = System.currentTimeMillis();
        h.postDelayed(tick, 1000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        refreshLinks();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (State.I.recording) stopRide();
        if (lm != null) { try { lm.removeUpdates(this); } catch (SecurityException ignored) {} }
        if (sm != null) sm.unregisterListener(this);
        if (hr != null) hr.close();
        if (bike != null) bike.stop();
        releaseWake();
        State s = State.I;
        s.gpsStatus = State.OFF; s.gpsMsg = "Aus";
        s.hrStatus = State.OFF; s.boschStatus = State.OFF; s.boschMsg = "Aus";
        instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ sensors

    private void startSensors() {
        startLocation();
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor baro = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
        State.I.hasBaro = baro != null;
        if (baro != null) sm.registerListener(this, baro, SensorManager.SENSOR_DELAY_NORMAL);
        refreshLinks();
    }

    private void startLocation() {
        if (locationOn) return;
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        State s = State.I;
        if (lm == null || !has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            s.gpsStatus = State.OFF; s.gpsMsg = "Standort-Berechtigung fehlt";
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, this, Looper.getMainLooper());
            locationOn = true;
            s.gpsStatus = State.SEARCH; s.gpsMsg = "Suche GPS";
        } catch (SecurityException | IllegalArgumentException e) {
            s.gpsStatus = State.OFF; s.gpsMsg = "GPS nicht verfügbar";
        }
    }

    /** Re-reads the settings and (re)connects heart rate sensor and the eBike link. */
    void refreshLinks() {
        if (hr == null || bike == null) return;
        startLocation();
        if (has(this, Manifest.permission.BLUETOOTH_CONNECT)) {
            String a = prefs.getString("hr_addr", null);
            if (a == null) hr.close();
            else hr.connect(a, prefs.getString("hr_name", null));
            String bikeAddr = prefs.getString("bike_addr", null);
            if (prefs.getBoolean("bosch_on", true) && bikeAddr != null) bike.start(bikeAddr);
            else bike.stop();
        }
        State.I.boschAddr = prefs.getString("bike_addr", null);
    }

    @Override
    public void onLocationChanged(Location l) {
        State s = State.I;
        long now = System.currentTimeMillis();
        s.lat = l.getLatitude();
        s.lon = l.getLongitude();
        s.acc = l.hasAccuracy() ? l.getAccuracy() : Float.NaN;
        s.gpsSpeed = l.hasSpeed() ? l.getSpeed() : Float.NaN;
        s.gpsTime = now;
        s.gpsStatus = !Float.isNaN(s.acc) && s.acc > 30 ? State.SEARCH : State.ON;
        s.gpsMsg = Float.isNaN(s.acc) ? "GPS" : "GPS ± " + Math.round(s.acc) + " m";
        if (l.hasAltitude()) {
            double alt;
            if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) {
                alt = l.getMslAltitudeMeters();
            } else {
                // GPS gives height above the ellipsoid; in Central Europe the geoid lies ~47 m above it.
                alt = l.getAltitude();
                if (s.lat > 44 && s.lat < 56 && s.lon > 4 && s.lon < 18) alt -= 47;
            }
            float vacc = l.hasVerticalAccuracy() ? l.getVerticalAccuracyMeters() : (Float.isNaN(s.acc) ? 99 : s.acc * 1.5f);
            if (vacc <= 20) {
                gpsMsl = alt;
                gpsAltTime = now;
                double baroAlt = baroAltitude();
                if (!Double.isNaN(baroAlt)) {
                    double diff = alt - baroAlt;
                    // Sensor fusion: GPS calibrates the absolute altitude, the barometer carries the
                    // fine profile. Converge fast for the first ~30 fixes so a poor initial GPS
                    // reading is corrected within seconds, then track slowly so only real weather
                    // drift is followed and the barometer keeps leading the profile (no GPS sawtooth).
                    double a = offsetLocks < 30 ? 0.1 : 0.02;
                    baroOffset = Double.isNaN(baroOffset) ? diff : baroOffset + a * (diff - baroOffset);
                    offsetLocks++;
                }
            }
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        State.I.gpsStatus = State.OFF;
        State.I.gpsMsg = "GPS ist ausgeschaltet";
    }

    @Override
    public void onProviderEnabled(String provider) {
        State.I.gpsStatus = State.SEARCH;
        State.I.gpsMsg = "Suche GPS";
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float p = e.values[0];
        pressure = Float.isNaN(pressure) ? p : pressure + 0.2f * (p - pressure);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private double baroAltitude() {
        return Float.isNaN(pressure) ? Double.NaN : SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure);
    }

    private double currentAlt(long now) {
        double b = baroAltitude();
        if (!Double.isNaN(b) && !Double.isNaN(baroOffset)) return b + baroOffset;
        if (!Double.isNaN(gpsMsl) && now - gpsAltTime < 10000) return gpsMsl;
        return Double.NaN;
    }

    // ------------------------------------------------------------------ recording

    void startRide() {
        State s = State.I;
        if (s.recording) return;
        s.resetRide();
        s.startTime = System.currentTimeMillis();
        try {
            writer = new RideStore.Writer(RideStore.dir(this), s.startTime);
        } catch (IOException e) {
            writer = null;
            return;
        }
        s.recording = true;
        s.socStart = s.soc;
        prevLat = Double.NaN; prevLon = Double.NaN; lastOdo = -1; refAlt = Double.NaN;
        lastTick = System.currentTimeMillis();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && wl == null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ebiketracker:ride");
            wl.acquire(12 * 60 * 60 * 1000L);
        }
        updateNotification();
    }

    void setPaused(boolean p) {
        State.I.paused = p;
        prevLat = Double.NaN; prevLon = Double.NaN; lastOdo = -1;
        updateNotification();
    }

    /** Finishes the ride. Returns the CSV file or null if the ride was too short and was discarded. */
    File stopRide() {
        State s = State.I;
        if (!s.recording || writer == null) { s.recording = false; return null; }
        s.recording = false;
        s.paused = false;
        writer.close();
        File f = writer.file;
        writer = null;
        releaseWake();
        File result = null;
        try {
            RideStore.Samples smp = RideStore.load(f);
            if (smp.n >= 5) {
                RideStore.writeMeta(RideStore.summarize(f, smp));
                result = f;
            } else {
                f.delete();
            }
        } catch (IOException e) {
            result = f;
        }
        updateNotification();
        return result;
    }

    /** Called when the app UI went away; keeps running only while recording. */
    void shutdownIfIdle() {
        if (!State.I.recording) stopSelf();
    }

    private void releaseWake() {
        if (wl != null) {
            try { if (wl.isHeld()) wl.release(); } catch (RuntimeException ignored) {}
            wl = null;
        }
    }

    private static double haversine(double la1, double lo1, double la2, double lo2) {
        double r = 6371000, p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = p2 - p1, dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private void doTick() {
        long now = System.currentTimeMillis();
        State s = State.I;
        if (s.gpsStatus != State.OFF && now - s.gpsTime > 6000) {
            s.gpsStatus = State.SEARCH;
            s.gpsMsg = "Suche GPS";
        }
        if (s.hrStatus == State.ON && now - s.hrTime > 6000) {
            s.hrStatus = State.SEARCH;
            s.hrMsg = "Kein Pulssignal";
        }
        double altRaw = currentAlt(now);
        if (!Double.isNaN(altRaw))
            altSmooth = Double.isNaN(altSmooth) ? altRaw : altSmooth + 0.08 * (altRaw - altSmooth);
        double alt = altSmooth;
        s.alt = alt;
        // The bike's raw range (a254) jumps several km at a time, especially in the cold.
        // Smooth it over ~80 s so the Reichweite tile rises/falls gradually like Flow does.
        if (s.range >= 0)
            s.rangeSmooth = Float.isNaN(s.rangeSmooth) ? s.range : s.rangeSmooth + 0.012f * (s.range - s.rangeSmooth);
        long dt = Math.min(2000, Math.max(0, now - lastTick));
        lastTick = now;
        tickCount++;

        // Smoothed live values (GPS speed and bike power jitter; Flow shows a smoothed curve).
        float rawSpd = s.speedKmh(now);
        if (Float.isNaN(rawSpd)) s.dispSpeed = Float.NaN;
        else if (rawSpd <= 0.1f) s.dispSpeed = 0f;                 // standing → snap to 0, no lingering value
        else s.dispSpeed = Float.isNaN(s.dispSpeed) ? rawSpd : s.dispSpeed + 0.35f * (rawSpd - s.dispSpeed);
        int rawPw = s.powerNow(now);
        if (rawPw < 0) s.dispPower = -1;
        else s.dispPower = s.dispPower < 0 ? rawPw : Math.round(s.dispPower + 0.3f * (rawPw - s.dispPower));

        if (s.recording && !s.paused && writer != null) {
            s.elapsedMs += dt;
            boolean gps = s.gpsFresh(now);
            boolean bike = s.bosch();
            float spd = s.speedKmh(now);
            boolean moving = !Float.isNaN(spd) && spd >= 2f;
            if (moving) s.movingMs += dt;
            if (!Float.isNaN(spd) && spd > s.maxSpeed && spd < 120) s.maxSpeed = spd;

            // distance: wheel sensor (odometer) when the bike is connected, otherwise GPS
            double gpsD = 0;
            if (gps && (Float.isNaN(s.acc) || s.acc <= 30)) {
                if (Double.isNaN(prevLat)) {
                    prevLat = s.lat; prevLon = s.lon;
                } else {
                    double dd = haversine(prevLat, prevLon, s.lat, s.lon);
                    float a = Float.isNaN(s.acc) ? 5 : s.acc;
                    if (dd >= Math.max(2, a * 0.5) || (moving && dd >= 1)) {
                        if (dd < 200) gpsD = dd;
                        prevLat = s.lat; prevLon = s.lon;
                    }
                }
            }
            double odoD = -1;
            if (bike && s.odo >= 0) {
                if (lastOdo >= 0) {
                    long x = s.odo - lastOdo;
                    if (x >= 0 && x < 300) odoD = x;
                }
                lastOdo = s.odo;
            }
            s.distM += odoD >= 0 ? odoD : gpsD;

            // Altitude needs a settling period: the barometer/GPS altitude drifts toward its
            // true value over the first ~1-2 min after start (e.g. 23 m → 34 m here), which is
            // not real climbing. So for the first 90 s we only follow the altitude (reference
            // tracks it, no gain). After that, count a sustained change of 5 m as gain/loss.
            if (!Double.isNaN(alt)) {
                if (Double.isNaN(refAlt) || s.elapsedMs < 90_000) refAlt = alt;
                else if (alt - refAlt >= 5) { s.gainM += alt - refAlt; refAlt = alt; }
                else if (refAlt - alt >= 5) { s.lossM += refAlt - alt; refAlt = alt; }
            }

            int hrv = s.hrFresh(now) ? s.hr : -1;
            if (hrv > 0) { s.sumHr += hrv; s.nHr++; s.maxHr = Math.max(s.maxHr, hrv); }
            int pw = s.powerNow(now);
            int cad = s.cadenceNow(now);
            if (pw >= 0) {
                if (moving) { s.sumPower += pw; s.nPower++; }
                s.maxPower = Math.max(s.maxPower, pw);
            }
            if (cad > 0) { s.sumCad += cad; s.nCad++; }
            if (s.socStart < 0 && bike && s.soc >= 0) s.socStart = s.soc;

            writer.write(now,
                    gps ? s.lat : Double.NaN, gps ? s.lon : Double.NaN, alt, spd, hrv, pw, cad,
                    bike ? s.bikeSpeed : Float.NaN, bike ? s.soc : -1, bike ? s.odo : -1,
                    s.distM, gps ? s.gpsSpeed : Float.NaN, gps ? s.acc : Float.NaN,
                    bike ? s.assist : -1, bike ? s.motorNow(now) : -1, bike ? s.range : -1);
        }
        if (tickCount % 5 == 0) updateNotification();
    }

    // ------------------------------------------------------------------ notification

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Aufzeichnung", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        State s = State.I;
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title, text;
        if (s.recording) {
            title = s.paused ? "Aufzeichnung pausiert" : "Aufzeichnung läuft";
            text = Ui.f2(s.distM / 1000) + " km · " + Ui.dur(s.elapsedMs);
        } else {
            title = "eBike Tracker";
            text = "Sensoren verbunden, keine Aufzeichnung";
        }
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try { nm.notify(NOTIF_ID, buildNotification()); } catch (SecurityException ignored) {}
        }
    }
}
