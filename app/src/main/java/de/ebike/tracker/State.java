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
    // a254: a range-like value the bike sends while riding. It does NOT match Flow's range and
    // is no longer used for the display (kept for diagnostics). Persists.
    int range = -1;
    // 9857: remaining range per assist mode in km, [ECO, TOUR+, AUTO, TURBO] – the exact values
    // Flow shows. null until the bike has sent it (~20 s after connecting). Persists.
    int[] modeRange;
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
        if (gpsFresh(now) && !Float.isNaN(gpsSpeed)) {
            float kmh = gpsSpeed * 3.6f;
            // A stationary phone still reports a few km/h of GPS "speed", worse on the weak,
            // short-lived fixes you get indoors. Treat a small speed or a poor fix as standing.
            if (kmh < 3f) return 0f;
            if (!Float.isNaN(acc) && acc > 30f) return 0f;
            return kmh;
        }
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

    /** Remaining range (km) for the selected assist level, taken straight from the bike's own
     *  per-mode range list (9857) – the same number Flow shows. No estimation or scaling.
     *  -1 until the bike has sent the list, or if no assist level is selected. */
    int rangeCurrent() {
        if (modeRange == null || assist < 1 || assist > modeRange.length) return -1;
        return modeRange[assist - 1];
    }

    /** Remaining range in km for the selected assist level (see rangeCurrent). */
    int rangeNow() {
        return rangeCurrent();
    }

    static final int BATTERY_WH = 750;

    /**
     * Consumption in Wh/km (e-car style), consistent with the range shown for the current
     * level: remaining charge ÷ remaining range. This differs sensibly per assist level
     * (little in Eco, a lot in Turbo) instead of showing nearly the same everywhere.
     */
    float consumptionWhPerKm() {
        int r = rangeCurrent();
        if (!bosch() || r <= 0 || soc < 0) return Float.NaN;
        return soc / 100f * BATTERY_WH / r;
    }
}
