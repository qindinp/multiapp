package com.multiapp.core.loader

import com.multiapp.core.common.EvidenceSanitizer

internal object HostedActivityEvidenceFiles {

    fun instrumentation(instanceId: String): String = "${safeInstanceId(instanceId)}.activity-instrumentation.properties"

    fun context(instanceId: String): String = "${safeInstanceId(instanceId)}.activity-context.properties"

    fun remap(instanceId: String): String = "${safeInstanceId(instanceId)}.activity-remap.properties"

    fun lifecycle(instanceId: String): String = "${safeInstanceId(instanceId)}.activity-lifecycle.properties"

    fun newIntent(instanceId: String): String = "${safeInstanceId(instanceId)}.activity-new-intent.properties"

    fun result(instanceId: String): String = "${safeInstanceId(instanceId)}.activity-result.properties"

    private fun safeInstanceId(instanceId: String): String =
        EvidenceSanitizer.safeEvidenceSegment(instanceId, "instanceId")
}
