package de.ebike.tracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** Line chart of one value over distance. Touch and drag to read single values. */
final class ChartView extends View {
    private float[] xs = new float[0], ys = new float[0];
    private final int color;
    private final String title, unit;
    private final int decimals;
    private float minY, maxY, maxX, avg, peak;
    private int touch = -1;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            grid = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG),
            head = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG),
            mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    ChartView(Context c, String title, String unit, int color, int decimals) {
        super(c);
        this.title = title;
        this.unit = unit;
        this.color = color;
        this.decimals = decimals;
        d = c.getResources().getDisplayMetrics().density;
        line.setColor(color);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        grid.setColor(Ui.LINE);
        grid.setStrokeWidth(1f * d);
        label.setColor(Ui.MUTED);
        label.setTextSize(11f * d);
        label.setFontFeatureSettings("tnum");
        head.setColor(color);
        head.setTextSize(13f * d);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bg.setColor(Ui.CARD);
        mark.setColor(Ui.FG);
        mark.setStrokeWidth(1.2f * d);
    }

    /** x in km, y in the chart's unit; NaN = no value. */
    void setData(float[] x, float[] y) {
        int n = Math.min(x.length, y.length);
        int max = 900;
        if (n > max) {
            float[] nx = new float[max], ny = new float[max];
            for (int b = 0; b < max; b++) {
                int a0 = (int) ((long) b * n / max), a1 = (int) ((long) (b + 1) * n / max);
                double sx = 0, sy = 0;
                int c = 0;
                for (int i = a0; i < a1; i++) if (!Float.isNaN(y[i])) { sy += y[i]; c++; }
                sx = x[Math.min(n - 1, (a0 + a1) / 2)];
                nx[b] = (float) sx;
                ny[b] = c == 0 ? Float.NaN : (float) (sy / c);
            }
            x = nx; y = ny; n = max;
        }
        xs = x; ys = y;
        minY = Float.MAX_VALUE; maxY = -Float.MAX_VALUE; maxX = 0;
        double sum = 0;
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (!Float.isNaN(xs[i])) maxX = Math.max(maxX, xs[i]);
            float v = ys[i];
            if (Float.isNaN(v)) continue;
            minY = Math.min(minY, v); maxY = Math.max(maxY, v);
            sum += v; cnt++;
        }
        if (cnt == 0) { minY = 0; maxY = 1; }
        avg = cnt == 0 ? Float.NaN : (float) (sum / cnt);
        peak = cnt == 0 ? Float.NaN : maxY;
        if (maxY - minY < 1e-3f) { maxY += 1; minY -= 1; }
        float pad = (maxY - minY) * 0.08f;
        maxY += pad;
        minY = minY >= 0 && minY - pad < 0 ? 0 : minY - pad;
        if (maxX <= 0) maxX = 1;
        line.setColor(color);
        invalidate();
    }

    private String fmt(float v) {
        if (Float.isNaN(v)) return "–";
        return decimals == 0 ? Ui.f0(v) : Ui.f1(v);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(176 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawRoundRect(new RectF(0, 0, w, h), 16 * d, 16 * d, bg);
        float left = 14 * d, right = w - 14 * d, top = 40 * d, bottom = h - 26 * d;

        c.drawText(title, left, 24 * d, head);
        String stats = "Ø " + fmt(avg) + " · max " + fmt(peak) + " " + unit;
        if (touch >= 0 && touch < ys.length) {
            stats = Ui.f2(xs[touch]) + " km: " + fmt(ys[touch]) + " " + unit;
        }
        float sw = label.measureText(stats);
        Paint sp = touch >= 0 ? head : label;
        c.drawText(stats, right - (touch >= 0 ? head.measureText(stats) : sw), 24 * d, sp);

        for (int g = 0; g <= 2; g++) {
            float y = top + (bottom - top) * g / 2f;
            c.drawLine(left, y, right, y, grid);
            float val = maxY - (maxY - minY) * g / 2f;
            c.drawText(fmt(val), left + 2 * d, y - 3 * d, label);
        }
        String xl = Ui.f1(maxX) + " km";
        c.drawText("0", left, h - 8 * d, label);
        c.drawText(xl, right - label.measureText(xl), h - 8 * d, label);

        if (xs.length < 2) return;
        Path p = new Path(), area = new Path();
        boolean pen = false;
        float firstX = 0, lastX = 0;
        for (int i = 0; i < xs.length; i++) {
            float v = ys[i];
            float px = left + (right - left) * xs[i] / maxX;
            if (Float.isNaN(v) || Float.isNaN(xs[i])) {
                if (pen) { area.lineTo(lastX, bottom); area.lineTo(firstX, bottom); area.close(); }
                pen = false;
                continue;
            }
            float py = bottom - (bottom - top) * (v - minY) / (maxY - minY);
            if (!pen) { p.moveTo(px, py); area.moveTo(px, bottom); area.lineTo(px, py); firstX = px; pen = true; }
            else { p.lineTo(px, py); area.lineTo(px, py); }
            lastX = px;
        }
        if (pen) { area.lineTo(lastX, bottom); area.lineTo(firstX, bottom); area.close(); }
        fill.setShader(new LinearGradient(0, top, 0, bottom, (color & 0x00FFFFFF) | 0x55000000, (color & 0x00FFFFFF), Shader.TileMode.CLAMP));
        c.drawPath(area, fill);
        c.drawPath(p, line);

        if (touch >= 0 && touch < xs.length) {
            float px = left + (right - left) * xs[touch] / maxX;
            c.drawLine(px, top, px, bottom, mark);
            if (!Float.isNaN(ys[touch])) {
                float py = bottom - (bottom - top) * (ys[touch] - minY) / (maxY - minY);
                c.drawCircle(px, py, 4 * d, head);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (xs.length < 2) return false;
        float left = 14 * d, right = getWidth() - 14 * d;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                getParent().requestDisallowInterceptTouchEvent(true);
                float km = (e.getX() - left) / (right - left) * maxX;
                int best = 0;
                float bd = Float.MAX_VALUE;
                for (int i = 0; i < xs.length; i++) {
                    float dd = Math.abs(xs[i] - km);
                    if (dd < bd) { bd = dd; best = i; }
                }
                touch = best;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touch = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
