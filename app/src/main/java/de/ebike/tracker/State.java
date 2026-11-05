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

    // Smoothed values for the live display (raw GPS/bike data jitters a lot).
    float dispSpeed = Float.NaN;
    int dispPower = -1;

    // heart rate
    int hr = -1;
    long hrTime;

    // Bosch Live Data Interface
    // power = RIDER power (signal 985b, verified against Flow); motorPower = MOTOR power (985d).
    int power = -1, motorPower = -1, cadence = -1, soc = -1, light = 0, assist = -1;
    float bikeSpeed = Float.NaN, brightness = Float.NaN;
    long odo = -1, bikeClock, boschTime;
    long bikeSpeedTime, cadTime, powerTime, motorTime;
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
        // When the eBike is connected its wheel speed is authoritative. The bike stops
        // sending when standing (the last value "sticks"), so a stale value means the bike
        // is stopped → 0. We do NOT fall back to GPS here: GPS jitters to 2–5 km/h at a
        // standstill, which is exactly the "5.4 km/h while standing" problem.
        if (bosch()) {
            if (!Float.isNaN(bikeSpeed) && now - bikeSpeedTime < FRESH) return bikeSpeed;
            return 0f;
        }
        if (gpsFresh(now) && !Float.isNaN(gpsSpeed)) return gpsSpeed * 3.6f;
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

    // --- Real power (verified against Flow's .fit): 985b = rider, 985d = motor ------
    // ASSIST_FACTOR is only used for the range scaling below.
    private static final float[] ASSIST_FACTOR = {0f, 0.6f, 1.4f, 2.4f, 3.4f};

    /** Motor power (W): value if fresh, 0 if the link is up but nothing came for a while
     *  (coasting / not driven), -1 if no eBike. */
    int motorNow(long now) {
        if (!bosch()) return -1;
        return motorPower >= 0 && now - motorTime < FRESH ? motorPower : 0;
    }

    /** Rider + motor power (W), -1 if no eBike. */
    int totalNow(long now) {
        int r = powerNow(now), m = motorNow(now);
        if (r < 0 && m < 0) return -1;
        return (r < 0 ? 0 : r) + (m < 0 ? 0 : m);
    }

    // a254 (field `range`) is the range for MAXIMUM assist (Turbo) – it doesn't change
    // with the selected level. The bike sends no per-level range, so we scale it to the
    // current level the same way the display does:
    //   range(level) = rangeTurbo × (1 + f_turbo) / (1 + f_level)
    // Cross-checked against Flow: 38 km (Turbo) → 104 km in ECO, Flow showed ~106.
    int rangeCurrent() {
        if (range < 0) return -1;
        int lvl = (assist >= 0 && assist < ASSIST_FACTOR.length) ? assist : 0;
        float fTurbo = ASSIST_FACTOR[ASSIST_FACTOR.length - 1];
        return Math.round(range * (1 + fTurbo) / (1 + ASSIST_FACTOR[lvl]));
    }

    // Average consumption since the ride started, in Wh/km (e-car style). Based on the
    // battery % drop and the PowerTube 750 capacity, which is more reliable than the raw
    // energy signal. Momentary consumption ≈ the (estimated) motor power.
    static final int BATTERY_WH = 750;

    float consumptionWhPerKm() {
        if (!bosch() || socStart < 0 || soc < 0) return Float.NaN;
        double km = distM / 1000.0;
        if (km < 0.1) return Float.NaN;
        float used = (socStart - soc) / 100f * BATTERY_WH;
        if (used <= 0) return Float.NaN;
        return (float) (used / km);
    }
}
