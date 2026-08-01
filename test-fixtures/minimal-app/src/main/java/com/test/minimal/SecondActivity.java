package com.test.minimal;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SecondActivity extends Activity {
    private static final String TAG = "MinimalApp";
    private static final String ACTION_ACTIVITY_RESULT_PROBE = "com.test.minimal.ACTION_ACTIVITY_RESULT_PROBE";
    private static final String ACTION_ACTIVITY_RESULT_RESPONSE = "com.test.minimal.ACTION_ACTIVITY_RESULT_RESPONSE";

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

        Button finishButton = new Button(this);
        finishButton.setText("Finish SecondActivity");
        finishButton.setOnClickListener(v -> {
            Log.d(TAG, "SecondActivity.finish() requested");
            finish();
        });
        layout.addView(finishButton);

        setContentView(layout);
        Log.d(TAG, "=== SecondActivity.onCreate() complete ===");

        if (ACTION_ACTIVITY_RESULT_PROBE.equals(getIntent().getAction())) {
            Intent result = new Intent().setAction(ACTION_ACTIVITY_RESULT_RESPONSE);
            setResult(RESULT_OK, result);
            Log.d(TAG, "SecondActivity returning Activity result");
            finish();
        }
    }
}
