package com.signalscout.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 1001;
    private static final int REQ_NOTIFY = 1002;

    private TextView etaBig, prediction, speed, heading, nearest, edge, gps;
    private CoverageRadarView radar;
    private Button trackButton;
    private Spinner leadSpinner, operatorSpinner;
    private SeekBar radiusBar;
    private TextView radiusLabel;
    private boolean tracking = false;
    private boolean suppressOperator = false;
    private JSONObject lastSnapshot;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String raw = intent.getStringExtra("json");
            if (raw == null) return;
            try { render(new JSONObject(raw)); } catch (Exception ignored) {}
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(TrackingService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    @Override protected void onStop() {
        super.onStop();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    private void buildUi() {
        int bg = Color.rgb(7, 16, 29);
        int panel = Color.rgb(14, 27, 44);
        int text = Color.rgb(245, 248, 252);
        int muted = Color.rgb(147, 164, 186);
        int accent = Color.rgb(94, 234, 212);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView eyebrow = tv("LIVE COVERAGE ESTIMATOR", 11, accent, true);
        root.addView(eyebrow);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = tv("Signal Scout", 27, text, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        gps = tv("GPS idle", 12, muted, true);
        gps.setPadding(dp(10), dp(7), dp(10), dp(7));
        gps.setBackground(round(Color.rgb(18, 34, 54), dp(999), Color.rgb(48, 67, 90)));
        titleRow.addView(gps);
        root.addView(titleRow, lpTop(3));

        LinearLayout hero = card(panel);
        hero.setOrientation(LinearLayout.VERTICAL);
        TextView hk = tv("PROJECTED SIGNAL LOSS", 12, muted, true);
        hero.addView(hk);
        etaBig = tv("—", 52, text, true);
        etaBig.setTypeface(Typeface.DEFAULT_BOLD);
        hero.addView(etaBig, lpTop(4));
        prediction = tv("Start tracking to estimate where mapped coverage may end.", 14, Color.rgb(201, 212, 227), false);
        hero.addView(prediction, lpTop(4));
        trackButton = button("Start tracking", accent, Color.rgb(3, 35, 31));
        hero.addView(trackButton, lpTop(16));
        root.addView(hero, lpTop(16));

        LinearLayout stats1 = new LinearLayout(this);
        stats1.setOrientation(LinearLayout.HORIZONTAL);
        speed = stat(stats1, "Speed", "— km/h", panel, text, muted);
        heading = stat(stats1, "Heading", "—", panel, text, muted);
        root.addView(stats1, lpTop(10));

        LinearLayout stats2 = new LinearLayout(this);
        stats2.setOrientation(LinearLayout.HORIZONTAL);
        nearest = stat(stats2, "Nearest mast", "— km", panel, text, muted);
        edge = stat(stats2, "Edge ahead", "— km", panel, text, muted);
        root.addView(stats2, lpTop(10));

        radar = new CoverageRadarView(this);
        GradientDrawable radarBg = round(panel, dp(18), Color.rgb(38, 57, 78));
        radar.setBackground(radarBg);
        root.addView(radar, new LinearLayout.LayoutParams(-1, dp(390)) {{ topMargin = dp(12); }});

        LinearLayout settings = card(panel);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.addView(tv("Alert settings", 19, text, true));
        settings.addView(tv("The app uses mapped mobile masts and an adjustable radius. It is an estimate, not a carrier guarantee.", 12, muted, false), lpTop(3));

        TextView leadLabel = tv("Warn before estimated cutoff", 14, text, true);
        settings.addView(leadLabel, lpTop(15));
        leadSpinner = new Spinner(this);
        ArrayAdapter<String> leadAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"1 minute", "2 minutes", "3 minutes", "5 minutes", "10 minutes"});
        leadSpinner.setAdapter(leadAdapter);
        leadSpinner.setSelection(2);
        settings.addView(leadSpinner);

        radiusLabel = tv("Estimated mast radius: 5.0 km", 14, text, true);
        settings.addView(radiusLabel, lpTop(15));
        radiusBar = new SeekBar(this);
        radiusBar.setMax(28);
        radiusBar.setProgress(8);
        settings.addView(radiusBar);

        TextView opLabel = tv("Operator filter", 14, text, true);
        settings.addView(opLabel, lpTop(15));
        operatorSpinner = new Spinner(this);
        setOperators(new ArrayList<>());
        settings.addView(operatorSpinner);

        Button battery = button("Open battery settings", Color.rgb(30, 47, 68), text);
        settings.addView(battery, lpTop(15));

        root.addView(settings, lpTop(12));

        TextView note = tv("Prediction model: the app checks OpenStreetMap mobile-phone mast data around your GPS position, projects your current heading, and searches for the first point outside the estimated mast-radius circles. Terrain, buildings, frequencies, antenna sectors and unmapped sites can move the real cutoff substantially.", 12, Color.rgb(181, 195, 212), false);
        note.setPadding(dp(4), dp(6), dp(4), dp(4));
        root.addView(note, lpTop(10));

        trackButton.setOnClickListener(v -> {
            if (tracking) stopTracking();
            else ensureLocationAndStart();
        });

        leadSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(() -> pushConfig()));
        operatorSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(() -> {
            if (!suppressOperator) pushConfig();
        }));

        radiusBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                double km = 1.0 + progress * 0.5;
                radiusLabel.setText(String.format("Estimated mast radius: %.1f km", km));
                radar.setRadiusKm(km);
                if (fromUser) pushConfig();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { pushConfig(); }
        });

        battery.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        setContentView(scroll);
    }

    private void ensureLocationAndStart() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
        startTracking();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startTracking();
            else Toast.makeText(this, "Precise location is needed for tracking.", Toast.LENGTH_LONG).show();
        }
    }

    private void startTracking() {
        Intent i = new Intent(this, TrackingService.class);
        i.setAction(TrackingService.ACTION_START);
        putConfig(i);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        tracking = true;
        trackButton.setText("Stop tracking");
        gps.setText("GPS starting…");
    }

    private void stopTracking() {
        stopService(new Intent(this, TrackingService.class));
        tracking = false;
        trackButton.setText("Start tracking");
        gps.setText("GPS paused");
    }

    private void pushConfig() {
        if (!tracking) return;
        Intent i = new Intent(this, TrackingService.class);
        i.setAction(TrackingService.ACTION_CONFIG);
        putConfig(i);
        startService(i);
    }

    private void putConfig(Intent i) {
        int[] mins = {1, 2, 3, 5, 10};
        int pos = Math.max(0, leadSpinner.getSelectedItemPosition());
        i.putExtra("leadMin", mins[Math.min(pos, mins.length - 1)]);
        i.putExtra("radiusKm", 1.0 + radiusBar.getProgress() * 0.5);
        Object op = operatorSpinner.getSelectedItem();
        i.putExtra("operator", op == null ? "All mapped masts" : op.toString());
    }

    private void render(JSONObject j) {
        lastSnapshot = j;
        tracking = j.optBoolean("tracking", tracking);
        trackButton.setText(tracking ? "Stop tracking" : "Start tracking");

        double kph = j.optDouble("speedKph", Double.NaN);
        double h = j.optDouble("heading", Double.NaN);
        double n = j.optDouble("nearestKm", Double.NaN);
        double e = j.optDouble("edgeKm", Double.NaN);
        double eta = j.optDouble("etaSec", Double.NaN);
        double acc = j.optDouble("accuracy", Double.NaN);

        speed.setText(Double.isNaN(kph) ? "— km/h" : String.format("%.0f km/h", kph));
        heading.setText(Double.isNaN(h) ? "—" : String.format("%.0f° %s", h, compass(h)));
        nearest.setText(Double.isNaN(n) ? "— km" : String.format(n < 10 ? "%.1f km" : "%.0f km", n));
        edge.setText(Double.isNaN(e) ? "— km" : String.format(e < 10 ? "%.1f km" : "%.0f km", e));
        gps.setText(Double.isNaN(acc) ? "GPS waiting" : String.format("GPS ±%.0f m", acc));

        if (j.optBoolean("riskNow", false)) etaBig.setText("Risk now");
        else if (!Double.isNaN(eta)) etaBig.setText(formatEta(eta));
        else if (!Double.isNaN(e)) etaBig.setText(String.format("%.1f km", e));
        else etaBig.setText("—");

        prediction.setText(j.optString("message", "Tracking…"));

        JSONArray ops = j.optJSONArray("operators");
        if (ops != null) {
            List<String> list = new ArrayList<>();
            for (int x = 0; x < ops.length(); x++) {
                String s = ops.optString(x, "");
                if (!s.isEmpty()) list.add(s);
            }
            String selected = j.optString("selectedOperator", "All mapped masts");
            setOperators(list);
            suppressOperator = true;
            ArrayAdapter<?> a = (ArrayAdapter<?>) operatorSpinner.getAdapter();
            int index = a.getPosition(selected);
            operatorSpinner.setSelection(index >= 0 ? index : 0, false);
            suppressOperator = false;
        }

        radar.setSnapshot(j);
    }

    private void setOperators(List<String> operators) {
        suppressOperator = true;
        List<String> items = new ArrayList<>();
        items.add("All mapped masts");
        items.addAll(operators);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        operatorSpinner.setAdapter(adapter);
        suppressOperator = false;
    }

    private String formatEta(double sec) {
        if (sec < 60) return "<1 min";
        return Math.round(sec / 60.0) + " min";
    }

    private String compass(double h) {
        String[] p = {"N","NE","E","SE","S","SW","W","NW"};
        return p[((int)Math.round(h / 45.0)) & 7];
    }

    private TextView stat(LinearLayout row, String label, String initial, int panel, int text, int muted) {
        LinearLayout box = card(panel);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(tv(label, 11, muted, false));
        TextView value = tv(initial, 20, text, true);
        box.addView(value, lpTop(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = dp(5);
        row.addView(box, lp);
        return value;
    }

    private LinearLayout card(int color) {
        LinearLayout l = new LinearLayout(this);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(round(color, dp(18), Color.rgb(38, 57, 78)));
        return l;
    }

    private GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button button(String s, int fill, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(color);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackground(round(fill, dp(14), fill));
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        return b;
    }

    private LinearLayout.LayoutParams lpTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top);
        return lp;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable r;
        SimpleItemSelectedListener(Runnable r) { this.r = r; }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { r.run(); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }
}
