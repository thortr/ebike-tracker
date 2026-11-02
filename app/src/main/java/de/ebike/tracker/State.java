package de.ebike.tracker;

/** Live values and the running ride. Only touched on the main thread. */
final class State {
    static final State I = new State();

    static final int OFF = 0, SEARCH = 1, ON = 2;

    // connection status
    int gpsStatus = OFF, hrStatus = OFF, boschStatus = OFF;
    String gpsMsg = "Aus", hrMsg = "Kein Pulsgurt gewählt", boschMsg = "Aus";
    String boschAddr;

    // GPS / barometer
    double lat = Double.NaN, lon = Double.NaN, alt = Double.NaN;
    float gpsSpeed = Float.NaN, acc = Float.NaN;
    long gpsTime;
    boolean hasBaro;

    // heart rate
    int hr = -1;
    long hrTime;

    // Bosch Live Data Interface
    int power = -1, cadence = -1, soc = -1, light = 0, assist = -1;
    float bikeSpeed = Float.NaN, brightness = Float.NaN;
    long odo = -1, bikeClock, boschTime;
    long bikeSpeedTime, cadTime, powerTime;
    boolean locked, charger, notDriving;

    // --- additional reverse-engineered signals from the private 0011 channel ---
    // Range estimate for the current assist level, in km (a254). Persists.
    int range = -1;
    // Remaining battery energy, Wh (8091 / 10). Persists.
    float energyWh = Float.NaN;
    // Trip values the bike itself keeps (its "Tagesfahrt"). The app also has its
    // own GPS distance and timer; these are the bike's own figures for comparison.
    int tripTimeS = -1;      // a243 – trip time, seconds
    int tripDist = -1;       // a251 – trip distance (unit ~1/16 km, still being verified)
    // Trip statistics as the Flow app shows them (verified against Flow screenshots):
    float trAvgSpeed = Float.NaN, trMaxSpeed = Float.NaN;   // a246 / a247  (x100 km/h)
    int trAvgCad = -1, trMaxCad = -1;                       // a248 / a249  (x2 rpm)
    int trAvgPower = -1, trMaxPower = -1;                   // a24a / a24b  (W)
    // Torque-like signals (no clean watt mapping found; kept for experiments):
    int riderTorque = -1;    // 9815 – rises with rider power (corr 0.83)
    int motorTorque = -1;    // 9814 – inverse to speed (motor torque curve)

    // current ride
    boolean recording, paused;
    long startTime, elapsedMs, movingMs;
    double distM, gainM, lossM;
    float maxSpeed;
    int maxHr, maxPower;
    double sumHr, sumPower, sumCad;
    int nHr, nPower, nCad;
    int socStart = -1;

    void resetRide() {
        recording = false; paused = false;
        startTime = 0; elapsedMs = 0; movingMs = 0;
        distM = 0; gainM = 0; lossM = 0;
        maxSpeed = 0; maxHr = 0; maxPower = 0;
        sumHr = 0; sumPower = 0; sumCad = 0;
        nHr = 0; nPower = 0; nCad = 0;
        socStart = -1;
    }

    boolean gpsFresh(long now) { return !Double.isNaN(lat) && now - gpsTime < 3000; }

    boolean hrFresh(long now) { return hr > 0 && now - hrTime < 5000; }

    /** The bike only sends changes, so a value is valid as long as the link is up. */
    boolean bosch() { return boschStatus == ON; }

    // These three change only while riding; the bike stops sending them when they
    // stop, so a stale value means "standing / not pedalling" → fall back to 0.
    private static final long FRESH = 2500;

    float speedKmh(long now) {
        if (bosch() && !Float.isNaN(bikeSpeed) && now - bikeSpeedTime < FRESH) return bikeSpeed;
        if (gpsFresh(now) && !Float.isNaN(gpsSpeed)) return gpsSpeed * 3.6f;
        if (bosch() && !Float.isNaN(bikeSpeed)) return 0f;
        return Float.NaN;
    }

    /** Current cadence: the value if fresh, 0 if the link is up but nothing came for
     *  a while (not pedalling), -1 if no eBike. */
    int cadenceNow(long now) {
        if (!bosch()) return -1;
        return cadence >= 0 && now - cadTime < FRESH ? cadence : 0;
    }

    int powerNow(long now) {
        if (!bosch()) return -1;
        return power >= 0 && now - powerTime < FRESH ? power : 0;
    }

    // --- Estimated motor assistance -----------------------------------------
    // The Bosch interface exposes no measured motor power, so we estimate it the
    // way the Flow app's graphic does: rider power x the support factor of the
    // current assist level, capped at the motor's 600 W maximum (from signal 9874).
    // Factors are the Performance Line CX defaults (OFF/ECO/TOUR+/AUTO/TURBO);
    // the bike doesn't send the per-level settings, so these are fixed estimates.
    private static final float[] ASSIST_FACTOR = {0f, 0.6f, 1.4f, 2.4f, 3.4f};
    static final int MOTOR_MAX_W = 600;

    int motorPowerEst(long now) {
        int rp = powerNow(now);
        if (rp < 0) return -1;                 // no eBike connected
        int lvl = (assist >= 0 && assist < ASSIST_FACTOR.length) ? assist : 0;
        return Math.min(Math.round(rp * ASSIST_FACTOR[lvl]), MOTOR_MAX_W);
    }

    int totalPowerEst(long now) {
        int rp = powerNow(now);
        if (rp < 0) return -1;
        int m = motorPowerEst(now);
        return rp + (m < 0 ? 0 : m);
    }
}
