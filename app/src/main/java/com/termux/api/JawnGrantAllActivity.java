package com.termux.api;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Interactive grant-all screen for the device permissions jawn's utilities use.
 * Launched from the JawnOS dashboard ("Grant device permissions"). Covers the
 * runtime permissions behind the termux-* commands plus the special-access
 * pages (overlay, notification listener, background location).
 */
public class JawnGrantAllActivity extends Activity {

    private static final int REQUEST_CODE = 4201;

    private TextView status;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(30, 30, 30, 30);

        TextView title = new TextView(this);
        title.setTextSize(22);
        title.setText("JawnOS API permissions");
        ll.addView(title);

        status = new TextView(this);
        ll.addView(status);

        add(ll, "Request all runtime permissions", new Runnable() {
            public void run() { requestAll(); }
        });
        add(ll, "Overlay: draw over other apps", new Runnable() {
            public void run() {
                open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            }
        });
        add(ll, "Notification access (termux-notification-list)", new Runnable() {
            public void run() {
                open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, null);
            }
        });
        add(ll, "Location: allow all the time", new Runnable() {
            public void run() {
                open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            }
        });

        setContentView(ll);
    }

    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private String[] runtimePermissions() {
        ArrayList<String> l = new ArrayList<>();
        l.add(android.Manifest.permission.CAMERA);
        l.add(android.Manifest.permission.RECORD_AUDIO);
        l.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        l.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
        l.add(android.Manifest.permission.READ_SMS);
        l.add(android.Manifest.permission.SEND_SMS);
        l.add(android.Manifest.permission.READ_CALL_LOG);
        l.add(android.Manifest.permission.READ_CONTACTS);
        l.add(android.Manifest.permission.USE_BIOMETRIC);
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(android.Manifest.permission.BLUETOOTH_SCAN);
            l.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        }
        return l.toArray(new String[0]);
    }

    private void requestAll() {
        ArrayList<String> missing = new ArrayList<>();
        for (String p : runtimePermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            Toast.makeText(this, "All runtime permissions already granted", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(missing.toArray(new String[0]), REQUEST_CODE);
    }

    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        updateStatus();
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        for (String p : runtimePermissions()) {
            boolean ok = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
            sb.append(p.replace("android.permission.", "")).append(": ").append(ok ? "granted" : "missing").append('\n');
        }
        sb.append("overlay: ").append(Settings.canDrawOverlays(this) ? "granted" : "missing").append('\n');
        if (Build.VERSION.SDK_INT >= 29) {
            boolean bg = checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
            sb.append("background location: ").append(bg ? "granted" : "missing").append('\n');
        }
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        sb.append("notification access: ").append(flat != null && flat.contains(getPackageName()) ? "granted" : "missing");
        status.setText(sb.toString());
    }

    private void open(String action, Uri data) {
        try {
            Intent i = new Intent(action);
            if (data != null) i.setData(data);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "Could not open settings page", Toast.LENGTH_SHORT).show();
        }
    }

    private void add(LinearLayout ll, String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        ll.addView(b);
    }
}
