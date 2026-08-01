package com.multiapp.app.container

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class EngineForegroundLaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = EngineForegroundLaunchRequest.fromIntent(intent) ?: return
        runCatching {
            EngineReadyActivityLauncher(context).launchFromHost(request)
        }.onFailure { error ->
            Log.e(TAG, "Host foreground launch dispatch failed for ${request.instanceId}", error)
        }
    }

    private companion object {
        const val TAG = "EngineForegroundLaunch"
    }
}
