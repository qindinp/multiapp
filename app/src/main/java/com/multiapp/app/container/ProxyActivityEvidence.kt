package com.multiapp.app.container

import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityResult

data class ProxyActivityEvidence(
    val proxyActivityClassName: String,
    val token: String,
    val originPackageName: String,
    val guestActivityClassName: String,
    val recordFound: Boolean,
    val recordRecovered: Boolean = false,
    val pendingNewIntent: VirtualActivityPendingNewIntent? = null,
    val result: VirtualActivityResult? = null,
    val pendingNewIntentConsumed: Boolean = pendingNewIntent != null,
    val resultConsumed: Boolean = result != null,
    val lifecycleEvent: String = "onCreate",
    val taskDescriptionLabel: String = "",
    val substitutionVerdict: String = "UNSUBSTITUTED_PROXY",
    val fallbackAction: String = ""
) {
    fun toFields(): Map<String, Any?> = linkedMapOf(
        "status" to if (lifecycleEvent == "onNewIntent") {
            "PROXY_ACTIVITY_BASE_ONNEWINTENT"
        } else {
            "PROXY_ACTIVITY_BASE_ONCREATE"
        },
        "stage" to "ACTIVITY_PROXY",
        "detail" to proxyActivityClassName,
        "lifecycleEvent" to lifecycleEvent,
        "token" to token,
        "originPackageName" to originPackageName,
        "guestActivityClassName" to guestActivityClassName,
        "proxyActivityClassName" to proxyActivityClassName,
        "taskDescriptionLabel" to taskDescriptionLabel,
        "substitutionVerdict" to substitutionVerdict,
        "fallbackAction" to fallbackAction,
        "activityRecordFound" to recordFound,
        "activityRecordRecovered" to recordRecovered,
        "pendingNewIntentObserved" to (pendingNewIntent != null),
        "pendingNewIntentConsumed" to pendingNewIntentConsumed,
        "pendingAction" to pendingNewIntent?.dataIntent?.action.orEmpty(),
        "pendingFlags" to (pendingNewIntent?.intentFlags ?: 0),
        "resultObserved" to (result != null),
        "resultConsumed" to resultConsumed,
        "resultCode" to (result?.resultCode ?: 0)
    )

    fun toLines(): List<String> {
        return toFields().map { (key, value) -> "$key=$value" }
    }
}
