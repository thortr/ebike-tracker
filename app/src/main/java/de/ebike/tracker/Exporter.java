package de.ebike.tracker;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Writes GPX or CSV files into Downloads/eBike Tracker. */
final class Exporter {
    private Exporter() {}

    static String baseName(RideStore.Ride r) {
        return "Fahrt_" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date(r.start));
    }

    static Uri gpx(Context c, RideStore.Ride r, RideStore.Samples s) throws IOException {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder b = new StringBuilder(s.n * 220 + 600);
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<gpx version=\"1.1\" creator=\"eBike Tracker\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
                .append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n");
        b.append(" <metadata><time>").append(iso.format(new Date(r.start))).append("</time></metadata>\n");
        b.append(" <trk>\n  <name>").append(baseName(r).replace('_', ' ')).append("</name>\n  <type>cycling</type>\n  <trkseg>\n");
        for (int i = 0; i < s.n; i++) {
            if (Double.isNaN(s.lat[i]) || Double.isNaN(s.lon[i])) continue;
            b.append("   <trkpt lat=\"").append(String.format(Locale.US, "%.6f", s.lat[i]))
                    .append("\" lon=\"").append(String.format(Locale.US, "%.6f", s.lon[i])).append("\">");
            if (!Float.isNaN(s.alt[i])) b.append("<ele>").append(String.format(Locale.US, "%.1f", s.alt[i])).append("</ele>");
            b.append("<time>").append(iso.format(new Date(s.t[i]))).append("</time>");
            boolean hr = s.hr[i] > 0, cad = s.cadence[i] >= 0, pw = s.power[i] >= 0;
            if (hr || cad || pw) {
                b.append("<extensions>");
                if (pw) b.append("<power>").append(s.power[i]).append("</power>");
                if (hr || cad) {
                    b.append("<gpxtpx:TrackPointExtension>");
                    if (hr) b.append("<gpxtpx:hr>").append(s.hr[i]).append("</gpxtpx:hr>");
                    if (cad) b.append("<gpxtpx:cad>").append(s.cadence[i]).append("</gpxtpx:cad>");
                    b.append("</gpxtpx:TrackPointExtension>");
                }
                b.append("</extensions>");
            }
            b.append("</trkpt>\n");
        }
        b.append("  </trkseg>\n </trk>\n</gpx>\n");
        return save(c, baseName(r) + ".gpx", "application/gpx+xml", b.toString().getBytes(StandardCharsets.UTF_8));
    }

    static Uri csv(Context c, RideStore.Ride r) throws IOException {
        // Same columns as stored, plus a readable local time, ';' separated for German Excel.
        SimpleDateFormat local = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY);
        StringBuilder b = new StringBuilder();
        b.append("Zeit;Breite;Länge;Höhe m;Tempo km/h;Puls bpm;Leistung W;Trittfrequenz U/min;Tempo eBike km/h;Akku %;Kilometerstand m;Distanz m;Tempo GPS m/s;GPS-Genauigkeit m;Unterstützung;Motor W;Reichweite km\n");
        try (BufferedReader rd = new BufferedReader(new FileReader(r.csv))) {
            String line = rd.readLine();
            while ((line = rd.readLine()) != null) {
                String[] p = line.split(",", -1);
                if (p.length < 12) continue;
                long t;
                try { t = Long.parseLong(p[0]); } catch (NumberFormatException e) { continue; }
                b.append(local.format(new Date(t)));
                for (int i = 1; i < p.length; i++) b.append(';').append(p[i].replace('.', ','));
                b.append('\n');
            }
        }
        // BOM so Excel detects UTF-8
        byte[] body = b.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF; out[1] = (byte) 0xBB; out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return save(c, baseName(r) + ".csv", "text/csv", out);
    }

    private static Uri save(Context c, String name, String mime, byte[] data) throws IOException {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/eBike Tracker");
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Speichern im Download-Ordner nicht möglich");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("Datei konnte nicht geöffnet werden");
            os.write(data);
        }
        return uri;
    }
}
