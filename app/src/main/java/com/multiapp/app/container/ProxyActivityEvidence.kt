package com.multiapp.app.container

import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityResult

data class ProxyActivityEvidence(
    val proxyActivityClassName: String,
    val token: String,
    val originPackageName: String,
    val guestActivityClassName: String,
    val recordFound: Boolean,
    val pendingNewIntent: VirtualActivityPendingNewIntent? = null,
    val result: VirtualActivityResult? = null
) {
    fun toFields(): Map<String, Any?> = linkedMapOf(
        "status" to "PROXY_ACTIVITY_BASE_ONCREATE",
        "stage" to "ACTIVITY_PROXY",
        "detail" to proxyActivityClassName,
        "token" to token,
        "originPackageName" to originPackageName,
        "guestActivityClassName" to guestActivityClassName,
        "proxyActivityClassName" to proxyActivityClassName,
        "activityRecordFound" to recordFound,
        "pendingNewIntentConsumed" to (pendingNewIntent != null),
        "pendingAction" to pendingNewIntent?.dataIntent?.action.orEmpty(),
        "pendingFlags" to (pendingNewIntent?.intentFlags ?: 0),
        "resultConsumed" to (result != null),
        "resultCode" to (result?.resultCode ?: 0)
    )

    fun toLines(): List<String> {
        return toFields().map { (key, value) -> "$key=$value" }
    }
}
