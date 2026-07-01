package com.test.minimal;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Activity for hosted-container acceptance testing.
 * Uses code-only UI so the first baseline does not depend on app resources.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MinimalApp";
    private static final String EXTRA_INSTANCE_ID = "multiapp.instanceId";
    private static final String EXTRA_HOST_PACKAGE_NAME = "multiapp.hostPackageName";
    private static final String PROXY_INSTANCE_ID = "multiapp_instanceId";
    private static final String PROXY_GUEST_AUTHORITY = "multiapp_guestAuthority";
    private static final String GUEST_PROVIDER_AUTHORITY = "com.test.minimal.probe";
    private static final String ACTION_PROBE_BROADCAST = "com.test.minimal.ACTION_PROBE_BROADCAST";
    private static final String ACTION_NEW_INTENT_PROBE = "com.test.minimal.ACTION_NEW_INTENT_PROBE";

    private TextView newIntentStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "=== MainActivity.onCreate() ===");

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        addText(layout, "MinimalTest launched", 24, 0xFF4CAF50);
        addText(layout, "", 8, 0);

        StringBuilder info = new StringBuilder();
        info.append("packageName: ").append(getPackageName()).append("\n");
        info.append("application class: ").append(getApplication().getClass().getName()).append("\n");
        info.append("classLoader: ").append(getClassLoader().getClass().getName()).append("\n");
        info.append("classLoader.parent: ")
            .append(getClassLoader().getParent() != null ? getClassLoader().getParent().getClass().getName() : "null")
            .append("\n");
        info.append("dataDir: ").append(getDataDir().getAbsolutePath()).append("\n");
        info.append("processName: ").append(android.os.Process.myPid()).append(" (pid)\n");
        info.append("taskId: ").append(getTaskId()).append("\n");

        try {
            Class.forName("com.test.minimal.MinimalApp");
            info.append("\nClass.forName(MinimalApp): OK\n");
        } catch (ClassNotFoundException e) {
            info.append("\nClass.forName(MinimalApp): FAILED: ").append(e.getMessage()).append("\n");
        }

        addText(layout, info.toString(), 16, 0xFF333333);

        try {
            String applicationInfoPackageName = getApplicationInfo().packageName;
            String label = getApplicationInfo().loadLabel(getPackageManager()).toString();
            addText(layout, "\napplicationInfo.packageName: " + applicationInfoPackageName + "\napp label: " + label, 16, 0xFF666666);
            Log.d(TAG, "applicationInfo.packageName: " + applicationInfoPackageName);
            Log.d(TAG, "app label: " + label);
        } catch (Exception e) {
            addText(layout, "\napp label read failed: " + e.getMessage(), 16, 0xFFFF9800);
        }

        String packageManagerProbe = runPackageManagerProbe();
        addText(layout, "\npackage manager probe:\n" + packageManagerProbe, 14, 0xFF4E342E);

        String storageProbe = runStorageProbe();
        addText(layout, "\nstorage probe:\n" + storageProbe, 14, 0xFF0D47A1);

        String componentProbe = runComponentProbe();
        addText(layout, "\ncomponent probe:\n" + componentProbe, 14, 0xFF1B5E20);

        Button launchSecond = new Button(this);
        launchSecond.setText("Launch SecondActivity");
        launchSecond.setOnClickListener(v -> {
            Log.d(TAG, "startActivity(SecondActivity) requested");
            startActivity(new Intent(this, SecondActivity.class));
        });
        layout.addView(launchSecond);

        newIntentStatus = new TextView(this);
        newIntentStatus.setText("onNewIntent: pending");
        newIntentStatus.setTextSize(16);
        newIntentStatus.setTextColor(0xFF6A1B9A);
        layout.addView(newIntentStatus);

        Button relaunchSingleTop = new Button(this);
        relaunchSingleTop.setText("Trigger onNewIntent");
        relaunchSingleTop.setOnClickListener(v -> {
            Log.d(TAG, "singleTop self relaunch requested");
            Intent relaunch = new Intent(this, MainActivity.class)
                .setAction(ACTION_NEW_INTENT_PROBE)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(relaunch);
        });
        layout.addView(relaunchSingleTop);

        addText(layout, "\nIf this page is visible, the hosted container reached guest Activity.onCreate().", 14, 0xFF999999);

        scroll.addView(layout);
        setContentView(scroll);

        Log.d(TAG, "=== MainActivity.onCreate() complete ===");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String action = intent != null ? intent.getAction() : "";
        String marker = "onNewIntent: " + action;
        Log.d(TAG, "=== MainActivity.onNewIntent(): " + action + " ===");
        if (newIntentStatus != null) {
            newIntentStatus.setText(marker);
        }
    }

    private String runStorageProbe() {
        StringBuilder out = new StringBuilder();

        try {
            SharedPreferences prefs = getSharedPreferences("probe", MODE_PRIVATE);
            int count = prefs.getInt("launchCount", 0) + 1;
            prefs.edit()
                .putInt("launchCount", count)
                .putString("packageName", getPackageName())
                .apply();
            out.append("prefs.launchCount: ").append(count).append("\n");
            out.append("prefs.packageName: ").append(prefs.getString("packageName", "")).append("\n");
        } catch (Exception e) {
            out.append("prefs failed: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
        }

        try {
            String payload = "package=" + getPackageName() + ",dataDir=" + getDataDir().getAbsolutePath();
            try (FileOutputStream output = openFileOutput("probe.txt", MODE_PRIVATE)) {
                output.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            byte[] bytes = new byte[4096];
            int read;
            try (FileInputStream input = openFileInput("probe.txt")) {
                read = input.read(bytes);
            }
            File filePath = getFileStreamPath("probe.txt");
            out.append("file.path: ").append(filePath.getAbsolutePath()).append("\n");
            out.append("file.content: ").append(new String(bytes, 0, Math.max(read, 0), StandardCharsets.UTF_8)).append("\n");
        } catch (Exception e) {
            out.append("file failed: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
        }

        try {
            SQLiteDatabase db = openOrCreateDatabase("probe.db", MODE_PRIVATE, null);
            db.execSQL("CREATE TABLE IF NOT EXISTS probe (id INTEGER PRIMARY KEY AUTOINCREMENT, package_name TEXT)");
            db.execSQL("INSERT INTO probe(package_name) VALUES (?)", new Object[] { getPackageName() });
            int rows = 0;
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM probe", null)) {
                if (cursor.moveToFirst()) rows = cursor.getInt(0);
            }
            db.close();
            out.append("db.path: ").append(getDatabasePath("probe.db").getAbsolutePath()).append("\n");
            out.append("db.rows: ").append(rows).append("\n");
        } catch (Exception e) {
            out.append("db failed: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
        }

        String result = out.toString();
        Log.d(TAG, "=== storage probe ===\n" + result);
        return result;
    }

    private String runComponentProbe() {
        StringBuilder out = new StringBuilder();

        try {
            Intent service = new Intent().setComponent(new ComponentName(getPackageName(), ProbeService.class.getName()));
            ComponentName started = startService(service);
            out.append("service.startResult: ").append(started != null ? started.flattenToShortString() : "null").append("\n");
        } catch (Exception e) {
            out.append("service failed: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
        }

        try {
            Intent broadcast = new Intent(ACTION_PROBE_BROADCAST)
                .setComponent(new ComponentName(getPackageName(), ProbeReceiver.class.getName()));
            sendBroadcast(broadcast);
            out.append("broadcast.sent: ").append(ACTION_PROBE_BROADCAST).append("\n");
        } catch (Exception e) {
            out.append("broadcast failed: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
        }

        try {
            String instanceId = getIntent().getStringExtra(EXTRA_INSTANCE_ID);
            String hostPackageName = getIntent().getStringExtra(EXTRA_HOST_PACKAGE_NAME);
            if (instanceId == null || instanceId.length() == 0 || hostPackageName == null || hostPackageName.length() == 0) {
                out.append("provider skipped: missing hosted extras\n");
            } else {
                Uri uri = new Uri.Builder()
                    .scheme("content")
                    .authority(hostPackageName + ".multiapp.provider.stub")
                    .appendPath("probe")
                    .appendQueryParameter(PROXY_INSTANCE_ID, instanceId)
                    .appendQueryParameter(PROXY_GUEST_AUTHORITY, GUEST_PROVIDER_AUTHORITY)
                    .build();
                try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                    String status = "null";
                    if (cursor != null && cursor.moveToFirst()) {
                        status = cursor.getString(cursor.getColumnIndexOrThrow("status"));
                    }
                    out.append("provider.queryStatus: ").append(status).append("\n");
                    out.append("provider.uri: ").append(redactUri(uri)).append("\n");
                }
            }
        } catch (Exception e) {
            out.append("provider failed: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
        }

        String result = out.toString();
        Log.d(TAG, "=== component probe ===\n" + result);
        return result;
    }

    private String redactUri(Uri uri) {
        if (uri == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (uri.getScheme() != null && uri.getScheme().length() > 0) {
            out.append(uri.getScheme()).append("://");
        }
        if (uri.getAuthority() != null && uri.getAuthority().length() > 0) {
            out.append(uri.getAuthority());
        }
        if (uri.getEncodedPath() != null) {
            out.append(uri.getEncodedPath());
        }
        if (uri.getEncodedQuery() != null || uri.getFragment() != null) {
            out.append("<redacted>");
        }
        return out.toString();
    }

    private String runPackageManagerProbe() {
        StringBuilder out = new StringBuilder();
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            out.append("pm.packageInfo.packageName: ").append(packageInfo.packageName).append("\n");
            out.append("pm.packageInfo.versionName: ").append(packageInfo.versionName).append("\n");
        } catch (Exception e) {
            out.append("pm.getPackageInfo failed: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
        }

        try {
            out.append("pm.applicationInfo.packageName: ")
                .append(getPackageManager().getApplicationInfo(getPackageName(), 0).packageName)
                .append("\n");
        } catch (Exception e) {
            out.append("pm.getApplicationInfo failed: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
        }

        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            ResolveInfo resolved = getPackageManager().resolveActivity(launcher, 0);
            out.append("pm.resolveActivity: ")
                .append(resolved != null && resolved.activityInfo != null ? resolved.activityInfo.name : "null")
                .append("\n");
        } catch (Exception e) {
            out.append("pm.resolveActivity failed: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
        }

        String result = out.toString();
        Log.d(TAG, "=== package manager probe ===\n" + result);
        return result;
    }

    private void addText(LinearLayout layout, String text, float sizeSp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        if (color != 0) tv.setTextColor(color);
        layout.addView(tv);
    }
}
