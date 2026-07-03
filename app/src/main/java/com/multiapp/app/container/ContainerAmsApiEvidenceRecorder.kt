package com.multiapp.app.container

import android.content.Context
import android.util.Log
import com.multiapp.core.loader.VirtualAmsApiEvidenceRecord
import com.multiapp.core.loader.VirtualAmsApiEvidenceRecorder

/** File-backed recorder for PR-8 AMS API interception evidence inside hosted containers. */
class ContainerAmsApiEvidenceRecorder(
    context: Context
) : VirtualAmsApiEvidenceRecorder {
    private val appContext = context.applicationContext

    override fun record(record: VirtualAmsApiEvidenceRecord) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = appContext,
                instanceId = record.instanceId,
                component = record.component.componentName,
                fields = sharedFields(record) + record.fields.filterKeys { key -> key !in SHARED_FIELD_KEYS }
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write AMS API evidence for instanceId=${record.instanceId}", error)
        }
    }

    private fun sharedFields(record: VirtualAmsApiEvidenceRecord): Map<String, Any?> = linkedMapOf(
        "status" to record.status,
        "stage" to "AMS_API_OVERLOAD",
        "instanceId" to record.instanceId,
        "originPackageName" to record.originPackageName,
        "virtualPackageName" to record.virtualPackageName,
        "api" to record.api,
        "hostFallback" to record.hostFallback
    )

    companion object {
        private const val TAG = "AmsApiEvidence"
        private val SHARED_FIELD_KEYS = setOf(
            "status",
            "stage",
            "instanceId",
            "originPackageName",
            "virtualPackageName",
            "api",
            "hostFallback"
        )
    }
}
