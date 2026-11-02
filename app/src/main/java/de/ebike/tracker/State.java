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
    boolean locked, charger, notDriving;

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

    float speedKmh(long now) {
        if (bosch() && !Float.isNaN(bikeSpeed)) return bikeSpeed;
        if (gpsFresh(now) && !Float.isNaN(gpsSpeed)) return gpsSpeed * 3.6f;
        return Float.NaN;
    }
}
