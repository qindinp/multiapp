package com.test.minimal;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SecondActivity extends Activity {
    private static final String TAG = "MinimalApp";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "=== SecondActivity.onCreate() ===");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("SecondActivity launched");
        title.setTextSize(24);
        title.setTextColor(0xFF1565C0);
        layout.addView(title);

        TextView info = new TextView(this);
        info.setText("packageName: " + getPackageName() + "\napplication: " + getApplication().getClass().getName());
        info.setTextSize(16);
        layout.addView(info);

        setContentView(layout);
        Log.d(TAG, "=== SecondActivity.onCreate() complete ===");
    }
}
