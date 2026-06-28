package com.test.minimal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "MinimalApp";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "=== broadcast probe === status=DELIVERED action=" + intent.getAction());
    }
}
