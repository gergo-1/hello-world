package com.signalscout.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrackingService extends Service implements LocationListener {
    public static final String ACTION_START = "com.signalscout.app.START";
    public static final String ACTION_CONFIG = "com.signalscout.app.CONFIG";
    public static final String ACTION_UPDATE = "com.signalscout.app.UPDATE";

    private static final String CH_TRACK = "signal_scout_tracking";
    private static final String CH_ALERT = "signal_scout_alerts";
    private static final int NOTIF_TRACK = 100;
    private static final int NOTIF_ALERT = 101;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LocationManager locationManager;
    private NotificationManager notifications;
    private volatile List<Tower> towers = Collections.emptyList();
    private volatile boolean fetching = false;

    private Location lastLocation;
    private double lastHeading = Double.NaN;
    private Location fetchCenter;
    private long lastFetchAt = 0L;
    private long lastNotifyUpdate = 0L;
    private boolean alerted = false;

    private int leadMin = 3;
    private double radiusKm = 5.0;
    private String selectedOperator = "All mapped masts";

    private static class Tower {
        double lat, lon;
        String operator, technology;
        Tower(double lat, double lon, String operator, String technology) {
            this.lat = lat; this.lon = lon;
            this.operator = operator == null ? "" : operator;
            this.technology = technology == null ? "" : technology;
        }
    }

    private static class EdgeResult {
        double distanceM;
        double lat, lon;
        EdgeResult(double distanceM, double lat, double lon) {
            this.distanceM = distanceM; this.lat = lat; this.lon = lon;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createChannels();
        startForeground(NOTIF_TRACK, trackingNotification("Waiting for GPS…"));
        loadPrefs();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            readConfig(intent);
            String action = intent.getAction();
            if (ACTION_START.equals(action)) startLocations();
            else if (ACTION_CONFIG.equals(action) && lastLocation != null) handleLocation(lastLocation);
        }
        return START_STICKY;
    }

    private void readConfig(Intent i) {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        if (i.hasExtra("leadMin")) leadMin = i.getIntExtra("leadMin", leadMin);
        if (i.hasExtra("radiusKm")) radiusKm = i.getDoubleExtra("radiusKm", radiusKm);
        if (i.hasExtra("operator")) selectedOperator = i.getStringExtra("operator");
        if (selectedOperator == null || selectedOperator.isEmpty()) selectedOperator = "All mapped masts";
        p.edit().putInt("leadMin", leadMin).putFloat("radiusKm", (float) radiusKm).putString("operator", selectedOperator).apply();
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        leadMin = p.getInt("leadMin", 3);
        radiusKm = p.getFloat("radiusKm", 5f);
        selectedOperator = p.getString("operator", "All mapped masts");
    }

    private void startLocations() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, this, Looper.getMainLooper());
        } catch (Exception ignored) {}
        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this, Looper.getMainLooper());
        } catch (Exception ignored) {}

        Location best = null;
        try { best = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
        if (best == null) {
            try { best = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
        }
        if (best != null) handleLocation(best);
    }

    @Override public void onLocationChanged(Location location) { handleLocation(location); }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    private void handleLocation(Location loc) {
        double speed = loc.hasSpeed() ? Math.max(0, loc.getSpeed()) : Double.NaN;
        double heading = loc.hasBearing() ? normalize(loc.getBearing()) : Double.NaN;

        if (lastLocation != null) {
            double dt = Math.max(0.001, (loc.getElapsedRealtimeNanos() - lastLocation.getElapsedRealtimeNanos()) / 1_000_000_000.0);
            float d = lastLocation.distanceTo(loc);
            if ((Double.isNaN(speed) || speed == 0) && dt < 20 && d > 2) speed = d / dt;
            if (Double.isNaN(heading) && d > 6) heading = bearing(lastLocation.getLatitude(), lastLocation.getLongitude(), loc.getLatitude(), loc.getLongitude());
        }

        if (!Double.isNaN(heading) && (Double.isNaN(speed) || speed > 0.8)) lastHeading = heading;
        if (Double.isNaN(heading)) heading = lastHeading;

        lastLocation = new Location(loc);

        boolean needFetch = towers.isEmpty() || fetchCenter == null ||
                fetchCenter.distanceTo(loc) > 4500 ||
                System.currentTimeMillis() - lastFetchAt > 8 * 60_000L;
        if (needFetch) fetchTowers(loc.getLatitude(), loc.getLongitude());

        Snapshot s = calculate(loc, speed, heading);
        broadcast(s);
        updateForeground(s);
        maybeAlert(s);
    }

    private static class Snapshot {
        double lat, lon, accuracy, speedKph, heading, nearestKm, edgeKm, etaSec, radiusKm;
        boolean riskNow;
        String message, selectedOperator;
        int towerCount;
        EdgeResult edge;
        List<Tower> visible = new ArrayList<>();
        List<String> operators = new ArrayList<>();
    }

    private Snapshot calculate(Location loc, double speedMps, double heading) {
        Snapshot s = new Snapshot();
        s.lat = loc.getLatitude();
        s.lon = loc.getLongitude();
        s.accuracy = loc.hasAccuracy() ? loc.getAccuracy() : Double.NaN;
        s.speedKph = Double.isNaN(speedMps) ? Double.NaN : speedMps * 3.6;
        s.heading = heading;
        s.radiusKm = radiusKm;
        s.selectedOperator = selectedOperator;

        List<Tower> active = activeTowers();
        s.towerCount = active.size();
        s.operators = operatorNames();

        if (!active.isEmpty()) {
            double min = Double.POSITIVE_INFINITY;
            List<Tower> sorted = new ArrayList<>(active);
            sorted.sort(Comparator.comparingDouble(t -> distanceM(s.lat, s.lon, t.lat, t.lon)));
            for (Tower t : sorted) min = Math.min(min, distanceM(s.lat, s.lon, t.lat, t.lon));
            s.nearestKm = min / 1000.0;
            for (int i = 0; i < Math.min(120, sorted.size()); i++) s.visible.add(sorted.get(i));
        } else s.nearestKm = Double.NaN;

        if (active.isEmpty()) {
            s.edgeKm = Double.NaN;
            s.etaSec = Double.NaN;
            s.message = towers.isEmpty() ? "Looking for mapped mobile masts nearby…" : "No mapped masts match this operator filter.";
            return s;
        }

        if (Double.isNaN(heading)) {
            s.edgeKm = Double.NaN;
            s.etaSec = Double.NaN;
            s.message = "Move a little so the app can determine your travel direction.";
            return s;
        }

        EdgeResult edge = predictEdge(s.lat, s.lon, heading, active);
        s.edge = edge;
        if (edge == null) {
            s.edgeKm = Double.NaN;
            s.etaSec = Double.NaN;
            s.message = "No estimated coverage edge found within 20 km on your current heading.";
        } else {
            s.edgeKm = edge.distanceM / 1000.0;
            s.riskNow = edge.distanceM <= 0.1;
            if (!Double.isNaN(speedMps) && speedMps > 0.5) {
                s.etaSec = edge.distanceM / speedMps;
                if (s.riskNow) s.message = "You are outside the current mapped-radius coverage estimate.";
                else s.message = String.format("Estimated edge %.1f km ahead if speed and direction stay similar.", s.edgeKm);
            } else {
                s.etaSec = Double.NaN;
                s.message = s.riskNow ? "You are outside the current mapped-radius coverage estimate." :
                        String.format("Estimated edge %.1f km ahead. Start moving for a time estimate.", s.edgeKm);
            }
        }
        return s;
    }

    private List<Tower> activeTowers() {
        if ("All mapped masts".equals(selectedOperator)) return towers;
        String needle = selectedOperator.toLowerCase();
        List<Tower> out = new ArrayList<>();
        for (Tower t : towers) if (t.operator.toLowerCase().contains(needle)) out.add(t);
        return out;
    }

    private List<String> operatorNames() {
        Set<String> set = new HashSet<>();
        for (Tower t : towers) if (t.operator != null && !t.operator.trim().isEmpty()) set.add(t.operator.trim());
        List<String> out = new ArrayList<>(set);
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private EdgeResult predictEdge(double lat, double lon, double heading, List<Tower> active) {
        if (!covered(lat, lon, active)) return new EdgeResult(0, lat, lon);
        for (double d = 100; d <= 20_000; d += 100) {
            double[] p = destination(lat, lon, heading, d);
            if (!covered(p[0], p[1], active)) return new EdgeResult(d, p[0], p[1]);
        }
        return null;
    }

    private boolean covered(double lat, double lon, List<Tower> active) {
        for (Tower t : active) {
            double r = inferredRadiusKm(t) * 1000.0;
            if (distanceM(lat, lon, t.lat, t.lon) <= r) return true;
        }
        return false;
    }

    private double inferredRadiusKm(Tower t) {
        double fallback = radiusKm;
        String tech = t.technology.toLowerCase();
        if (tech.contains("3500") || tech.contains("3600")) return Math.min(fallback, 2.0);
        if (tech.contains("2600")) return Math.min(fallback, 3.0);
        if (tech.contains("1800")) return Math.max(3.5, Math.min(fallback, 6.0));
        if (tech.contains("800") || tech.contains("900")) return Math.max(fallback, 10.0);
        return fallback;
    }

    private void fetchTowers(double lat, double lon) {
        if (fetching) return;
        fetching = true;
        executor.execute(() -> {
            Exception failure = null;
            String query = "[out:json][timeout:22];(" +
                    "nwr(around:25000," + lat + "," + lon + ")[\"communication:mobile_phone\"=\"yes\"];" +
                    "nwr(around:25000," + lat + "," + lon + ")[\"antenna:type\"=\"mobile_phone\"];" +
                    "nwr(around:25000," + lat + "," + lon + ")[\"telecom\"=\"antenna\"][\"communication:mobile_phone\"=\"yes\"];" +
                    ");out center tags;";

            String[] endpoints = {
                    "https://overpass-api.de/api/interpreter",
                    "https://overpass.kumi.systems/api/interpreter"
            };

            for (String endpoint : endpoints) {
                try {
                    URL url = new URL(endpoint);
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(26000);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                    c.setRequestProperty("User-Agent", "SignalScout/0.1 Android");
                    byte[] body = ("data=" + URLEncoder.encode(query, "UTF-8")).getBytes(StandardCharsets.UTF_8);
                    try (OutputStream out = c.getOutputStream()) { out.write(body); }
                    if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) throw new Exception("HTTP " + c.getResponseCode());

                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }

                    JSONObject root = new JSONObject(sb.toString());
                    JSONArray elements = root.optJSONArray("elements");
                    List<Tower> found = new ArrayList<>();
                    Set<String> seen = new HashSet<>();
                    if (elements != null) {
                        for (int i = 0; i < elements.length(); i++) {
                            JSONObject e = elements.optJSONObject(i);
                            if (e == null) continue;
                            double elat = e.has("lat") ? e.optDouble("lat", Double.NaN) : e.optJSONObject("center") != null ? e.optJSONObject("center").optDouble("lat", Double.NaN) : Double.NaN;
                            double elon = e.has("lon") ? e.optDouble("lon", Double.NaN) : e.optJSONObject("center") != null ? e.optJSONObject("center").optDouble("lon", Double.NaN) : Double.NaN;
                            if (Double.isNaN(elat) || Double.isNaN(elon)) continue;
                            String key = String.format("%.5f,%.5f", elat, elon);
                            if (!seen.add(key)) continue;
                            JSONObject tags = e.optJSONObject("tags");
                            String op = "";
                            String tech = "";
                            if (tags != null) {
                                op = first(tags.optString("operator", ""), tags.optString("brand", ""), tags.optString("network", ""));
                                tech = first(tags.optString("technology:mobile_phone", ""), tags.optString("communication:mobile_phone", ""), tags.optString("frequency", ""));
                            }
                            found.add(new Tower(elat, elon, op, tech));
                        }
                    }
                    towers = found;
                    lastFetchAt = System.currentTimeMillis();
                    fetchCenter = new Location("tower_fetch");
                    fetchCenter.setLatitude(lat);
                    fetchCenter.setLongitude(lon);
                    fetching = false;
                    if (lastLocation != null) handleLocation(lastLocation);
                    return;
                } catch (Exception e) { failure = e; }
            }
            fetching = false;
            Snapshot s = calculate(lastLocation != null ? lastLocation : dummyLocation(lat, lon), Double.NaN, lastHeading);
            s.message = "Tower lookup failed" + (failure != null ? ": " + failure.getMessage() : ".");
            broadcast(s);
        });
    }

    private Location dummyLocation(double lat, double lon) {
        Location l = new Location("fallback");
        l.setLatitude(lat); l.setLongitude(lon);
        return l;
    }

    private String first(String... values) {
        for (String s : values) if (s != null && !s.trim().isEmpty()) return s.trim();
        return "";
    }

    private void broadcast(Snapshot s) {
        try {
            JSONObject j = new JSONObject();
            j.put("tracking", true);
            j.put("lat", s.lat);
            j.put("lon", s.lon);
            if (!Double.isNaN(s.accuracy)) j.put("accuracy", s.accuracy);
            if (!Double.isNaN(s.speedKph)) j.put("speedKph", s.speedKph);
            if (!Double.isNaN(s.heading)) j.put("heading", s.heading);
            if (!Double.isNaN(s.nearestKm)) j.put("nearestKm", s.nearestKm);
            if (!Double.isNaN(s.edgeKm)) j.put("edgeKm", s.edgeKm);
            if (!Double.isNaN(s.etaSec)) j.put("etaSec", s.etaSec);
            j.put("radiusKm", s.radiusKm);
            j.put("riskNow", s.riskNow);
            j.put("message", s.message);
            j.put("towerCount", s.towerCount);
            j.put("selectedOperator", s.selectedOperator);

            JSONArray ops = new JSONArray();
            for (String op : s.operators) ops.put(op);
            j.put("operators", ops);

            JSONArray arr = new JSONArray();
            for (Tower t : s.visible) {
                JSONObject x = new JSONObject();
                x.put("lat", t.lat);
                x.put("lon", t.lon);
                x.put("operator", t.operator);
                arr.put(x);
            }
            j.put("towers", arr);

            if (s.edge != null) {
                JSONObject e = new JSONObject();
                e.put("lat", s.edge.lat);
                e.put("lon", s.edge.lon);
                j.put("edge", e);
            }

            Intent i = new Intent(ACTION_UPDATE);
            i.setPackage(getPackageName());
            i.putExtra("json", j.toString());
            sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    private void maybeAlert(Snapshot s) {
        boolean near = s.riskNow || (!Double.isNaN(s.etaSec) && s.etaSec <= leadMin * 60.0);
        if (near && !alerted) {
            alerted = true;
            String body = s.riskNow ? "You are outside the current mapped coverage estimate." :
                    String.format("Estimated signal edge in about %d min (%.1f km) if speed and heading stay similar.",
                            Math.max(1, Math.round((float)(s.etaSec / 60.0))), s.edgeKm);
            Notification n = new Notification.Builder(this, CH_ALERT)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Signal may drop soon")
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent())
                    .build();
            notifications.notify(NOTIF_ALERT, n);
        } else if (!near || (!Double.isNaN(s.etaSec) && s.etaSec > leadMin * 60.0 + 90)) {
            alerted = false;
        }
    }

    private void updateForeground(Snapshot s) {
        long now = System.currentTimeMillis();
        if (now - lastNotifyUpdate < 4000) return;
        lastNotifyUpdate = now;
        String line;
        if (s.riskNow) line = "Coverage risk now";
        else if (!Double.isNaN(s.etaSec)) line = "Edge ~" + Math.max(1, Math.round((float)(s.etaSec / 60.0))) + " min • " + Math.round(s.speedKph) + " km/h";
        else if (!Double.isNaN(s.speedKph)) line = Math.round(s.speedKph) + " km/h • estimating edge";
        else line = "Tracking location";
        notifications.notify(NOTIF_TRACK, trackingNotification(line));
    }

    private Notification trackingNotification(String line) {
        return new Notification.Builder(this, CH_TRACK)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Signal Scout is tracking")
                .setContentText(line)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(openAppIntent())
                .build();
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel track = new NotificationChannel(CH_TRACK, "Signal Scout tracking", NotificationManager.IMPORTANCE_LOW);
        track.setDescription("Persistent notification while background GPS tracking is active.");
        notifications.createNotificationChannel(track);

        NotificationChannel alert = new NotificationChannel(CH_ALERT, "Coverage warnings", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("Warnings before the estimated coverage edge.");
        alert.enableVibration(true);
        notifications.createNotificationChannel(alert);
    }

    @Override public void onDestroy() {
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        executor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private double normalize(double d) {
        d %= 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    private double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return normalize(Math.toDegrees(Math.atan2(y, x)));
    }

    private double distanceM(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2)*Math.sin(dLon/2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    private double[] destination(double lat, double lon, double bearing, double meters) {
        final double R = 6371000.0;
        double br = Math.toRadians(bearing), d = meters / R;
        double la1 = Math.toRadians(lat), lo1 = Math.toRadians(lon);
        double la2 = Math.asin(Math.sin(la1)*Math.cos(d) + Math.cos(la1)*Math.sin(d)*Math.cos(br));
        double lo2 = lo1 + Math.atan2(Math.sin(br)*Math.sin(d)*Math.cos(la1), Math.cos(d)-Math.sin(la1)*Math.sin(la2));
        return new double[]{Math.toDegrees(la2), ((Math.toDegrees(lo2)+540)%360)-180};
    }
}
