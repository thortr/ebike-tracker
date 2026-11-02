package de.ebike.tracker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raster-tile map (OpenStreetMap standard or Esri satellite) with the ride track drawn
 * on top and a movable position marker. No Google SDK / API key / Play Services needed –
 * tiles are fetched over plain HTTPS, cached in memory and on disk.
 */
final class MapView extends View {
    private static final int TILE = 256;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    private double[] lat = new double[0], lon = new double[0];
    private float[] val = new float[0];
    private float vMin, vMax;
    private int n;
    private boolean satellite;
    private double markLat = Double.NaN, markLon = Double.NaN;
    private boolean markActive;

    private int zoom = -1;
    private final float d;
    private final File tileDir;
    private final LruCache<String, Bitmap> cache;
    private final ConcurrentHashMap<String, Boolean> loading = new ConcurrentHashMap<>();

    private final Paint bg = new Paint(), seg = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            tp = new Paint(Paint.FILTER_BITMAP_FLAG), mk = new Paint(Paint.ANTI_ALIAS_FLAG);

    MapView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        tileDir = new File(c.getCacheDir(), "tiles");
        if (!tileDir.exists()) tileDir.mkdirs();
        int mem = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cache = new LruCache<String, Bitmap>(Math.min(mem / 6, 12 * 1024)) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
        };
        bg.setColor(0xFFE8E6E1);
        seg.setStyle(Paint.Style.STROKE);
        seg.setStrokeWidth(4f * d);
        seg.setStrokeCap(Paint.Cap.ROUND);
        seg.setStrokeJoin(Paint.Join.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * d);
        ring.setColor(Color.WHITE);
    }

    void setData(RideStore.Samples s, float[] colorBy) {
        int c = 0;
        for (int i = 0; i < s.n; i++) if (!Double.isNaN(s.lat[i])) c++;
        lat = new double[c]; lon = new double[c]; val = new float[c];
        int j = 0;
        vMin = Float.MAX_VALUE; vMax = -Float.MAX_VALUE;
        for (int i = 0; i < s.n; i++) {
            if (Double.isNaN(s.lat[i])) continue;
            lat[j] = s.lat[i]; lon[j] = s.lon[i];
            float v = colorBy == null ? Float.NaN : colorBy[i];
            val[j] = v;
            if (!Float.isNaN(v)) { vMin = Math.min(vMin, v); vMax = Math.max(vMax, v); }
            j++;
        }
        n = c;
        if (vMin > vMax) { vMin = 0; vMax = 1; }
        zoom = -1;
        invalidate();
    }

    void setSatellite(boolean sat) {
        if (sat == satellite) return;
        satellite = sat;
        invalidate();
    }

    boolean isSatellite() { return satellite; }

    /** Show (or hide) a marker at a geographic point. active=false hides it. */
    void setMarker(double la, double lo, boolean active) {
        markLat = la; markLon = lo; markActive = active;
        invalidate();
    }

    // ---- Web-Mercator world pixels at the current zoom ----
    private double worldX(double lo) { return (lo + 180) / 360 * TILE * (1L << zoom); }

    private double worldY(double la) {
        double s = Math.sin(Math.toRadians(la));
        s = Math.max(-0.9999, Math.min(0.9999, s));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * TILE * (1L << zoom);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(w * 0.78f));
    }

    private void pickZoom(float w, float h) {
        double minLa = 90, maxLa = -90, minLo = 180, maxLo = -180;
        for (int i = 0; i < n; i++) {
            minLa = Math.min(minLa, lat[i]); maxLa = Math.max(maxLa, lat[i]);
            minLo = Math.min(minLo, lon[i]); maxLo = Math.max(maxLo, lon[i]);
        }
        float pad = 24 * d;
        for (int z = 18; z >= 2; z--) {
            zoom = z;
            double spanX = Math.abs(worldX(maxLo) - worldX(minLo));
            double spanY = Math.abs(worldY(minLa) - worldY(maxLa));
            if (spanX <= w - 2 * pad && spanY <= h - 2 * pad) break;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        RectF frame = new RectF(0, 0, w, h);
        c.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(frame, 16 * d, 16 * d, android.graphics.Path.Direction.CW);
        c.clipPath(clip);
        c.drawRect(frame, bg);

        if (n < 2) { c.restore(); return; }
        if (zoom < 0) pickZoom(w, h);

        // centre the bounding box
        double cx = (worldX(minLon()) + worldX(maxLon())) / 2;
        double cy = (worldY(minLat()) + worldY(maxLat())) / 2;
        double viewLeft = cx - w / 2, viewTop = cy - h / 2;

        int nTiles = 1 << zoom;
        int x0 = (int) Math.floor(viewLeft / TILE), x1 = (int) Math.floor((viewLeft + w) / TILE);
        int y0 = (int) Math.floor(viewTop / TILE), y1 = (int) Math.floor((viewTop + h) / TILE);
        for (int tx = x0; tx <= x1; tx++) {
            for (int ty = y0; ty <= y1; ty++) {
                if (tx < 0 || ty < 0 || tx >= nTiles || ty >= nTiles) continue;
                Bitmap bmp = tile(tx, ty, zoom);
                float dx = (float) (tx * TILE - viewLeft), dy = (float) (ty * TILE - viewTop);
                if (bmp != null) c.drawBitmap(bmp, dx, dy, tp);
            }
        }

        // track
        int step = Math.max(1, n / 2000);
        float px = 0, py = 0;
        boolean first = true;
        for (int i = 0; i < n; i += step) {
            float x = (float) (worldX(lon[i]) - viewLeft), y = (float) (worldY(lat[i]) - viewTop);
            if (!first) { seg.setColor(colorFor(val[i])); c.drawLine(px, py, x, y, seg); }
            px = x; py = y; first = false;
        }
        // start / end
        float sx = (float) (worldX(lon[0]) - viewLeft), sy = (float) (worldY(lat[0]) - viewTop);
        float ex = (float) (worldX(lon[n - 1]) - viewLeft), ey = (float) (worldY(lat[n - 1]) - viewTop);
        dot.setColor(Ui.OK); c.drawCircle(sx, sy, 6 * d, dot); c.drawCircle(sx, sy, 6 * d, ring);
        dot.setColor(Ui.DANGER); c.drawCircle(ex, ey, 6 * d, dot); c.drawCircle(ex, ey, 6 * d, ring);

        // movable marker
        if (markActive && !Double.isNaN(markLat)) {
            float mx = (float) (worldX(markLon) - viewLeft), my = (float) (worldY(markLat) - viewTop);
            mk.setColor(Color.WHITE); c.drawCircle(mx, my, 9 * d, mk);
            mk.setColor(Ui.ACCENT); c.drawCircle(mx, my, 6.5f * d, mk);
        }

        // attribution
        tp.setColor(0x99000000);
        c.restore();
    }

    private double minLat() { double m = 90; for (int i = 0; i < n; i++) m = Math.min(m, lat[i]); return m; }
    private double maxLat() { double m = -90; for (int i = 0; i < n; i++) m = Math.max(m, lat[i]); return m; }
    private double minLon() { double m = 180; for (int i = 0; i < n; i++) m = Math.min(m, lon[i]); return m; }
    private double maxLon() { double m = -180; for (int i = 0; i < n; i++) m = Math.max(m, lon[i]); return m; }

    private Bitmap tile(int x, int y, int z) {
        String key = (satellite ? "s_" : "m_") + z + "_" + x + "_" + y;
        Bitmap b = cache.get(key);
        if (b != null) return b;
        File f = new File(tileDir, key + ".png");
        if (f.exists()) {
            b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) { cache.put(key, b); return b; }
        }
        fetch(key, x, y, z, f);
        return null;
    }

    private void fetch(String key, int x, int y, int z, File f) {
        if (loading.putIfAbsent(key, Boolean.TRUE) != null) return;
        final boolean sat = satellite;
        POOL.execute(() -> {
            try {
                String url = sat
                        ? "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/" + z + "/" + y + "/" + x
                        : "https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png";
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setRequestProperty("User-Agent", "eBikeTracker/1.0 (personal use)");
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                con.connect();
                if (con.getResponseCode() == 200) {
                    try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    }
                    Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (bmp != null) {
                        cache.put(key, bmp);
                        postInvalidate();
                    }
                }
                con.disconnect();
            } catch (Exception ignored) {
            } finally {
                loading.remove(key);
            }
        });
    }

    private static int lerp(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bgc = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bgc - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private int colorFor(float v) {
        if (Float.isNaN(v) || vMax - vMin < 1e-3f) return Ui.ACCENT;
        float t = (v - vMin) / (vMax - vMin);
        t = Math.max(0, Math.min(1, t));
        return t < 0.5f ? lerp(0xFF4DA8FF, 0xFFFFD34D, t * 2) : lerp(0xFFFFD34D, 0xFFFF4D5E, (t - 0.5f) * 2);
    }
}
