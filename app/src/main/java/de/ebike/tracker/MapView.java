package de.ebike.tracker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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
 * on top and a movable position marker. Supports pan (drag), pinch-zoom and double-tap
 * zoom. No Google SDK / API key / Play Services – tiles are fetched over plain HTTPS and
 * cached in memory and on disk.
 */
final class MapView extends View {
    private static final int TILE = 256;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    private double[] lat = new double[0], lon = new double[0];
    private float[] val = new float[0];
    private float vMin, vMax;
    private int n;
    private boolean satellite;
    private boolean levelMode;   // colour the track by assist level (Flow colours) instead of a scale
    private double markLat = Double.NaN, markLon = Double.NaN;
    private boolean markActive;

    // view state
    private int zoom = -1;
    private double centerLat, centerLon;
    private double scale = 1;        // live pinch factor within the current integer zoom
    private boolean fitted;

    private final float d;
    private final File tileDir;
    private final LruCache<String, Bitmap> cache;
    private final ConcurrentHashMap<String, Boolean> loading = new ConcurrentHashMap<>();
    private final ScaleGestureDetector scaleDet;
    private final GestureDetector tapDet;
    private float lastX, lastY;
    private boolean panning;

    private final Paint bg = new Paint(), seg = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            tp = new Paint(Paint.FILTER_BITMAP_FLAG), mk = new Paint(Paint.ANTI_ALIAS_FLAG);

    MapView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        tileDir = new File(c.getCacheDir(), "tiles");
        if (!tileDir.exists()) tileDir.mkdirs();
        int mem = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cache = new LruCache<String, Bitmap>(Math.min(mem / 6, 16 * 1024)) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
        };
        bg.setColor(0xFFE8E6E1);
        seg.setStyle(Paint.Style.STROKE);
        seg.setStrokeCap(Paint.Cap.ROUND);
        seg.setStrokeJoin(Paint.Join.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Color.WHITE);

        scaleDet = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector det) {
                scale *= det.getScaleFactor();
                while (scale >= 2 && zoom < 19) { scale /= 2; zoom++; }
                while (scale <= 0.5 && zoom > 3) { scale *= 2; zoom--; }
                if (scale < 0.5) scale = 0.5;
                if (scale > 2) scale = 2;
                invalidate();
                return true;
            }
        });
        tapDet = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (zoom < 19) { zoom++; invalidate(); }
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                fitted = false; invalidate();   // long-press resets to the whole track
            }
        });
    }

    void setData(RideStore.Samples s, float[] colorBy) { setData(s, colorBy, false); }

    void setData(RideStore.Samples s, float[] colorBy, boolean byLevel) {
        this.levelMode = byLevel;
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
        fitted = false; scale = 1;
        invalidate();
    }

    void setSatellite(boolean sat) { if (sat != satellite) { satellite = sat; invalidate(); } }
    boolean isSatellite() { return satellite; }

    void setMarker(double la, double lo, boolean active) {
        markLat = la; markLon = lo; markActive = active;
        invalidate();
    }

    // ---- Web-Mercator world pixels at the current integer zoom ----
    private double worldX(double lo) { return (lo + 180) / 360 * TILE * (1L << zoom); }

    private double worldY(double la) {
        double s = Math.sin(Math.toRadians(la));
        s = Math.max(-0.9999, Math.min(0.9999, s));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * TILE * (1L << zoom);
    }

    private double unLon(double wx) { return wx / (TILE * (1L << zoom)) * 360 - 180; }

    private double unLat(double wy) {
        double nn = Math.PI - 2 * Math.PI * wy / (TILE * (1L << zoom));
        return Math.toDegrees(Math.atan(Math.sinh(nn)));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(w * 0.62f));
    }

    private void fitToBounds(float w, float h) {
        double minLa = 90, maxLa = -90, minLo = 180, maxLo = -180;
        for (int i = 0; i < n; i++) {
            minLa = Math.min(minLa, lat[i]); maxLa = Math.max(maxLa, lat[i]);
            minLo = Math.min(minLo, lon[i]); maxLo = Math.max(maxLo, lon[i]);
        }
        float pad = 26 * d;
        for (int z = 18; z >= 2; z--) {
            zoom = z;
            double spanX = Math.abs(worldX(maxLo) - worldX(minLo));
            double spanY = Math.abs(worldY(minLa) - worldY(maxLa));
            if (spanX <= w - 2 * pad && spanY <= h - 2 * pad) break;
        }
        centerLat = (minLa + maxLa) / 2;
        centerLon = (minLo + maxLo) / 2;
        scale = 1;
        fitted = true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        RectF frame = new RectF(0, 0, w, h);
        c.save();
        Path clip = new Path();
        clip.addRoundRect(frame, 16 * d, 16 * d, Path.Direction.CW);
        c.clipPath(clip);
        c.drawRect(frame, bg);
        if (n < 2) { c.restore(); return; }
        if (!fitted || zoom < 0) fitToBounds(w, h);

        double cxw = worldX(centerLon), cyw = worldY(centerLat);
        c.translate(w / 2f, h / 2f);
        c.scale((float) scale, (float) scale);
        c.translate((float) -cxw, (float) -cyw);

        // visible world rectangle
        double halfW = (w / 2) / scale, halfH = (h / 2) / scale;
        double left = cxw - halfW, right = cxw + halfW, top = cyw - halfH, bottom = cyw + halfH;
        int nTiles = 1 << zoom;
        int x0 = (int) Math.floor(left / TILE), x1 = (int) Math.floor(right / TILE);
        int y0 = (int) Math.floor(top / TILE), y1 = (int) Math.floor(bottom / TILE);
        for (int tx = x0; tx <= x1; tx++) {
            for (int ty = y0; ty <= y1; ty++) {
                if (tx < 0 || ty < 0 || tx >= nTiles || ty >= nTiles) continue;
                Bitmap bmp = tile(tx, ty, zoom);
                if (bmp != null) c.drawBitmap(bmp, tx * TILE, ty * TILE, tp);
            }
        }

        // track (world coords; widths divided by scale to stay constant on screen)
        float sw = (float) scale;
        seg.setStrokeWidth(4f * d / sw);
        ring.setStrokeWidth(2.5f * d / sw);
        int step = Math.max(1, n / 2000);
        float px = 0, py = 0;
        boolean first = true;
        for (int i = 0; i < n; i += step) {
            float x = (float) worldX(lon[i]), y = (float) worldY(lat[i]);
            if (!first) { seg.setColor(colorFor(val[i])); c.drawLine(px, py, x, y, seg); }
            px = x; py = y; first = false;
        }
        float r = 6 * d / sw;
        float sx = (float) worldX(lon[0]), sy = (float) worldY(lat[0]);
        float ex = (float) worldX(lon[n - 1]), ey = (float) worldY(lat[n - 1]);
        dot.setColor(Ui.OK); c.drawCircle(sx, sy, r, dot); c.drawCircle(sx, sy, r, ring);
        dot.setColor(Ui.DANGER); c.drawCircle(ex, ey, r, dot); c.drawCircle(ex, ey, r, ring);

        if (markActive && !Double.isNaN(markLat)) {
            float mx = (float) worldX(markLon), my = (float) worldY(markLat);
            mk.setColor(Color.WHITE); c.drawCircle(mx, my, 9 * d / sw, mk);
            mk.setColor(Ui.ACCENT); c.drawCircle(mx, my, 6.5f * d / sw, mk);
        }
        c.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        getParent().requestDisallowInterceptTouchEvent(true);
        scaleDet.onTouchEvent(e);
        tapDet.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY(); panning = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                panning = false;   // second finger → pinch, not pan
                break;
            case MotionEvent.ACTION_MOVE:
                if (panning && !scaleDet.isInProgress() && e.getPointerCount() == 1) {
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    lastX = e.getX(); lastY = e.getY();
                    panBy(dx, dy);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // resume single-finger pan from the remaining finger
                int rem = e.getActionIndex() == 0 ? 1 : 0;
                lastX = e.getX(rem); lastY = e.getY(rem); panning = true;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                panning = false;
                break;
        }
        return true;
    }

    private void panBy(float dx, float dy) {
        if (zoom < 0) return;
        double cxw = worldX(centerLon), cyw = worldY(centerLat);
        cxw -= dx / scale; cyw -= dy / scale;
        int world = TILE * (1 << zoom);
        cxw = Math.max(1, Math.min(world - 1, cxw));
        cyw = Math.max(1, Math.min(world - 1, cyw));
        centerLon = unLon(cxw); centerLat = unLat(cyw);
        invalidate();
    }

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
                    if (bmp != null) { cache.put(key, bmp); postInvalidate(); }
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
        if (levelMode) return Float.isNaN(v) ? ChartView.levelColor(-1) : ChartView.levelColor(Math.round(v));
        if (Float.isNaN(v) || vMax - vMin < 1e-3f) return Ui.ACCENT;
        float t = (v - vMin) / (vMax - vMin);
        t = Math.max(0, Math.min(1, t));
        return t < 0.5f ? lerp(0xFF4DA8FF, 0xFFFFD34D, t * 2) : lerp(0xFFFFD34D, 0xFFFF4D5E, (t - 0.5f) * 2);
    }
}
