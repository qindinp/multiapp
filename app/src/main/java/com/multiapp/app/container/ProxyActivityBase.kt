package com.multiapp.app.container

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.multiapp.core.loader.VirtualActivityRecordManager

abstract class ProxyActivityBase : Activity() {

    companion object {
        private const val TAG = "ProxyActivity"

        const val EXTRA_VIRTUAL_ACTIVITY_TOKEN = "multiapp.virtualActivityToken"
        const val EXTRA_INSTANCE_ID = "multiapp.instanceId"
        const val EXTRA_ORIGIN_PACKAGE_NAME = "multiapp.originPackageName"
        const val EXTRA_GUEST_ACTIVITY_CLASS_NAME = "multiapp.guestActivityClassName"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
        val token = intent.getStringExtra(EXTRA_VIRTUAL_ACTIVITY_TOKEN).orEmpty()
        val guestActivity = intent.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS_NAME).orEmpty()
        val originPackage = intent.getStringExtra(EXTRA_ORIGIN_PACKAGE_NAME).orEmpty()
        val manager = VirtualActivityRecordManager.global
        val record = manager.resolve(token)
        val pendingNewIntent = manager.consumePendingNewIntent(token)
        val result = manager.consumeResult(token)

        if (instanceId.isNullOrBlank()) {
            Log.e(TAG, "Proxy launched without instanceId: proxy=${javaClass.name}")
            finish()
            return
        }

        Log.i(
            TAG,
                "Proxy resumed: proxy=${javaClass.name}, instanceId=$instanceId, " +
                "token=$token, origin=$originPackage, guest=$guestActivity, recordFound=${record != null}"
        )
        writeProxyEvidence(
            instanceId = instanceId,
            evidence = ProxyActivityEvidence(
                proxyActivityClassName = javaClass.name,
                token = token,
                originPackageName = originPackage,
                guestActivityClassName = guestActivity,
                recordFound = record != null,
                pendingNewIntent = pendingNewIntent,
                result = result
            )
        )
    }

    private fun writeProxyEvidence(
        instanceId: String,
        evidence: ProxyActivityEvidence
    ) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = this,
                instanceId = instanceId,
                component = "activity-proxy",
                fields = evidence.toFields()
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write proxy evidence for instanceId=$instanceId", error)
        }
    }
}

class ProxyActivity0 : ProxyActivityBase()

class ProxyActivity1 : ProxyActivityBase()

class ProxyActivitySingleTop0 : ProxyActivityBase()

class ProxyActivitySingleTop1 : ProxyActivityBase()

class ProxyActivitySingleTask0 : ProxyActivityBase()

class ProxyActivitySingleTask1 : ProxyActivityBase()
