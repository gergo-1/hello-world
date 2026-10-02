package com.signalscout.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class CoverageRadarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Point> towers = new ArrayList<>();
    private double userLat = Double.NaN;
    private double userLon = Double.NaN;
    private double edgeLat = Double.NaN;
    private double edgeLon = Double.NaN;
    private double heading = Double.NaN;
    private double radiusKm = 5.0;
    private int towerCount = 0;

    private static class Point {
        final double lat;
        final double lon;
        Point(double lat, double lon) { this.lat = lat; this.lon = lon; }
    }

    public CoverageRadarView(Context context) { super(context); init(); }
    public CoverageRadarView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setBackgroundColor(Color.rgb(7, 16, 29));
        setMinimumHeight(dp(320));
    }

    public void setSnapshot(JSONObject json) {
        towers.clear();
        userLat = json.optDouble("lat", Double.NaN);
        userLon = json.optDouble("lon", Double.NaN);
        heading = json.optDouble("heading", Double.NaN);
        radiusKm = json.optDouble("radiusKm", 5.0);
        towerCount = json.optInt("towerCount", 0);

        JSONObject edge = json.optJSONObject("edge");
        if (edge != null) {
            edgeLat = edge.optDouble("lat", Double.NaN);
            edgeLon = edge.optDouble("lon", Double.NaN);
        } else {
            edgeLat = Double.NaN;
            edgeLon = Double.NaN;
        }

        JSONArray arr = json.optJSONArray("towers");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t != null) {
                    double lat = t.optDouble("lat", Double.NaN);
                    double lon = t.optDouble("lon", Double.NaN);
                    if (!Double.isNaN(lat) && !Double.isNaN(lon)) towers.add(new Point(lat, lon));
                }
            }
        }
        invalidate();
    }

    public void setRadiusKm(double km) {
        radiusKm = km;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) * 0.42f;
        double displayKm = Math.max(8.0, Math.min(22.0, radiusKm * 2.6));
        float pxPerKm = (float) (r / displayKm);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dpF(1));
        paint.setColor(Color.rgb(38, 57, 78));
        for (int i = 1; i <= 4; i++) {
            float rr = r * i / 4f;
            c.drawCircle(cx, cy, rr, paint);
        }
        c.drawLine(cx - r, cy, cx + r, cy, paint);
        c.drawLine(cx, cy - r, cx, cy + r, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dpF(11));
        paint.setColor(Color.rgb(133, 151, 172));
        c.drawText("N", cx - dpF(4), cy - r - dpF(8), paint);
        c.drawText("E", cx + r + dpF(8), cy + dpF(4), paint);
        c.drawText("S", cx - dpF(4), cy + r + dpF(18), paint);
        c.drawText("W", cx - r - dpF(18), cy + dpF(4), paint);

        paint.setTextSize(dpF(10));
        String scaleText = String.format("%.0f km", displayKm);
        c.drawText(scaleText, cx + r - dpF(42), cy + r + dpF(18), paint);

        if (!Double.isNaN(userLat) && !Double.isNaN(userLon)) {
            float coveragePx = (float) (radiusKm * pxPerKm);
            for (Point t : towers) {
                float[] p = project(t.lat, t.lon, cx, cy, pxPerKm);
                if (p == null) continue;
                if (p[0] < -coveragePx || p[0] > w + coveragePx || p[1] < -coveragePx || p[1] > h + coveragePx) continue;

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpF(1));
                paint.setColor(Color.argb(50, 251, 191, 36));
                c.drawCircle(p[0], p[1], coveragePx, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(251, 191, 36));
                c.drawCircle(p[0], p[1], dpF(3.5f), paint);
            }

            if (!Double.isNaN(heading)) {
                double br = Math.toRadians(heading);
                float len = r * 0.86f;
                float ex = cx + (float) (Math.sin(br) * len);
                float ey = cy - (float) (Math.cos(br) * len);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpF(3));
                paint.setColor(Color.rgb(125, 211, 252));
                Path path = new Path();
                path.moveTo(cx, cy);
                path.lineTo(ex, ey);
                c.drawPath(path, paint);
            }

            if (!Double.isNaN(edgeLat) && !Double.isNaN(edgeLon)) {
                float[] p = project(edgeLat, edgeLon, cx, cy, pxPerKm);
                if (p != null) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.rgb(251, 113, 133));
                    c.drawCircle(p[0], p[1], dpF(7), paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dpF(2));
                    paint.setColor(Color.argb(180, 251, 113, 133));
                    c.drawCircle(p[0], p[1], dpF(12), paint);
                }
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(94, 234, 212));
            c.drawCircle(cx, cy, dpF(7), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpF(3));
            paint.setColor(Color.rgb(8, 49, 45));
            c.drawCircle(cx, cy, dpF(10), paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dpF(11));
        paint.setColor(Color.rgb(147, 164, 186));
        c.drawText("Coverage radar • " + towerCount + " mapped masts", dpF(12), dpF(20), paint);
    }

    private float[] project(double lat, double lon, float cx, float cy, float pxPerKm) {
        if (Double.isNaN(userLat) || Double.isNaN(userLon)) return null;
        double northKm = (lat - userLat) * 111.32;
        double eastKm = (lon - userLon) * 111.32 * Math.cos(Math.toRadians(userLat));
        return new float[]{cx + (float) (eastKm * pxPerKm), cy - (float) (northKm * pxPerKm)};
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private float dpF(float v) { return v * getResources().getDisplayMetrics().density; }
}
