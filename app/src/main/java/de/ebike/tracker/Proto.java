package de.ebike.tracker;

/**
 * Minimal decoder for the Bosch "LiveData" protobuf message
 * (Bosch eBike Live Data Interface v1, package com.bosch.ebike).
 * Unknown fields are skipped, as the specification requires.
 */
final class Proto {
    private Proto() {}

    /** Merges all fields found in {@code b} into {@code s}. Returns the number of known fields. */
    static int decode(byte[] b, State s) {
        int i = 0, known = 0;
        while (i < b.length) {
            long[] k = varint(b, i);
            if (k == null) break;
            i = (int) k[1];
            int field = (int) (k[0] >>> 3);
            int wire = (int) (k[0] & 7);
            if (wire == 0) {
                long[] v = varint(b, i);
                if (v == null) break;
                i = (int) v[1];
                long x = v[0];
                known++;
                switch (field) {
                    case 1: s.bikeSpeed = x / 100f; break;            // 1/100 km/h
                    case 2: s.cadence = (int) x; break;                // rpm (int32)
                    case 5: s.power = (int) x; break;                  // rider power, W
                    case 9: s.brightness = x / 1000f; break;           // 1/1000 lux
                    case 10: s.soc = (int) x; break;                   // battery %
                    case 11: s.bikeClock = x; break;                   // unix seconds
                    case 12: s.odo = x; break;                         // odometer, m
                    case 17: s.light = (int) x; break;                 // 1 off, 2 on
                    case 21: s.locked = x != 0; break;
                    case 22: s.charger = x != 0; break;
                    case 25: s.notDriving = x != 0; break;
                    default: known--; break;
                }
            } else if (wire == 1) {
                i += 8;
            } else if (wire == 5) {
                i += 4;
            } else if (wire == 2) {
                long[] len = varint(b, i);
                if (len == null) break;
                i = (int) len[1] + (int) len[0];
            } else {
                break;
            }
        }
        return known;
    }

    /** Returns {value, nextIndex} or null if the buffer ends inside the varint. */
    static long[] varint(byte[] b, int i) {
        long v = 0;
        int shift = 0;
        while (i < b.length && shift < 70) {
            int x = b[i++] & 0xff;
            v |= ((long) (x & 0x7f)) << shift;
            if ((x & 0x80) == 0) return new long[]{v, i};
            shift += 7;
        }
        return null;
    }
}
