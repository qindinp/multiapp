package com.multiapp.app.container

import android.content.Context
import android.util.Log
import com.multiapp.core.loader.VirtualBroadcastRecord
import com.multiapp.core.loader.VirtualBroadcastRecorder

/** File-backed recorder for guest BroadcastReceiver dispatch inside hosted containers. */
class ContainerBroadcastEvidenceRecorder(
    context: Context
) : VirtualBroadcastRecorder {
    private val appContext = context.applicationContext

    override fun record(record: VirtualBroadcastRecord) {
        val instanceId = record.instanceId ?: return
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = appContext,
                instanceId = instanceId,
                component = "broadcast",
                fields = linkedMapOf(
                    "status" to record.result.name,
                    "stage" to "BROADCAST_RUNTIME",
                    "instanceId" to instanceId,
                    "receiverClassName" to record.receiverClassName.orEmpty(),
                    "action" to record.action.orEmpty(),
                    "result" to record.result.name
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write broadcast evidence for instanceId=$instanceId", error)
        }
    }

    companion object {
        private const val TAG = "BroadcastEvidence"
    }
}
