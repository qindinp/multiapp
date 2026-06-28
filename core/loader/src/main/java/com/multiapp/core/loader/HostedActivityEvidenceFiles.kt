package com.multiapp.core.loader

internal object HostedActivityEvidenceFiles {
    fun instrumentation(instanceId: String): String = "$instanceId.activity-instrumentation.properties"

    fun context(instanceId: String): String = "$instanceId.activity-context.properties"

    fun remap(instanceId: String): String = "$instanceId.activity-remap.properties"
}
