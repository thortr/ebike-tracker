package de.ebike.tracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Shape of the route (no map tiles), coloured by power when available, else by speed. */
final class TrackView extends View {
    private double[] lat = new double[0], lon = new double[0];
    private float[] val = new float[0];
    private float vMin, vMax;
    private int n;
    private final float d;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), seg = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    TrackView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        bg.setColor(Ui.CARD);
        seg.setStyle(Paint.Style.STROKE);
        seg.setStrokeWidth(3.2f * d);
        seg.setStrokeCap(Paint.Cap.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * d);
        ring.setColor(Ui.CARD);
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
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(w * 0.72f));
    }

    private static int lerp(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bgc = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bgc - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private int colorFor(float v) {
        if (Float.isNaN(v)) return Ui.ACCENT;
        float t = (v - vMin) / (vMax - vMin);
        t = Math.max(0, Math.min(1, t));
        return t < 0.5f ? lerp(0xFF4DA8FF, 0xFFFFD34D, t * 2) : lerp(0xFFFFD34D, 0xFFFF4D5E, (t - 0.5f) * 2);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawRoundRect(new RectF(0, 0, w, h), 16 * d, 16 * d, bg);
        if (n < 2) return;
        double minLa = 90, maxLa = -90, minLo = 180, maxLo = -180;
        for (int i = 0; i < n; i++) {
            minLa = Math.min(minLa, lat[i]); maxLa = Math.max(maxLa, lat[i]);
            minLo = Math.min(minLo, lon[i]); maxLo = Math.max(maxLo, lon[i]);
        }
        double k = Math.cos(Math.toRadians((minLa + maxLa) / 2));
        double spanX = Math.max(1e-6, (maxLo - minLo) * k), spanY = Math.max(1e-6, maxLa - minLa);
        float pad = 18 * d;
        double scale = Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY);
        double ox = (w - spanX * scale) / 2, oy = (h - spanY * scale) / 2;
        int step = Math.max(1, n / 1500);
        float px = 0, py = 0;
        for (int i = 0; i < n; i += step) {
            float x = (float) (ox + (lon[i] - minLo) * k * scale);
            float y = (float) (h - oy - (lat[i] - minLa) * scale);
            if (i > 0) {
                seg.setColor(colorFor(val[i]));
                c.drawLine(px, py, x, y, seg);
            }
            px = x; py = y;
        }
        float sx = (float) (ox + (lon[0] - minLo) * k * scale), sy = (float) (h - oy - (lat[0] - minLa) * scale);
        float ex = (float) (ox + (lon[n - 1] - minLo) * k * scale), ey = (float) (h - oy - (lat[n - 1] - minLa) * scale);
        dot.setColor(Ui.OK);
        c.drawCircle(sx, sy, 6 * d, dot);
        c.drawCircle(sx, sy, 6 * d, ring);
        dot.setColor(Ui.DANGER);
        c.drawCircle(ex, ey, 6 * d, dot);
        c.drawCircle(ex, ey, 6 * d, ring);
    }
}
