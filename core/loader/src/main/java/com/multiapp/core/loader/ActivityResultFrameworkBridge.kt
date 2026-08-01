package com.multiapp.core.loader

import android.content.Intent
import android.os.IBinder
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.model.virtual.VirtualActivityResult
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

data class HostedFrameworkActivityIdentity(
    val instanceId: String,
    val virtualActivityToken: String,
    val guestActivityClassName: String,
    val hostFilesDir: File
)

data class FrameworkActivityResultDelivery(
    val identity: HostedFrameworkActivityIdentity,
    val requestCode: Int,
    val resultCode: Int,
    val resultWho: String?,
    val data: Intent?
)

/**
 * Observes Android's real finishActivity -> ActivityResultItem path.
 *
 * The bridge never dispatches a result. It records the engine route before the framework call and
 * consumes that record only after ActivityThread has handled the corresponding transaction.
 */
object VirtualActivityResultFrameworkBridge {
    private const val EVIDENCE_DIR = "hosted_launch_evidence"
    private val identities = ConcurrentHashMap<IBinder, HostedFrameworkActivityIdentity>()
    private val finishReceipts = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var operations: VirtualActivityOperations? = null

    fun install(activityOperations: VirtualActivityOperations) {
        operations = activityOperations
    }

    fun remember(frameworkToken: IBinder, identity: HostedFrameworkActivityIdentity) {
        identities[frameworkToken] = identity
    }

    fun forget(frameworkToken: IBinder?) {
        if (frameworkToken != null) identities.remove(frameworkToken)
    }

    fun captureFinishActivity(methodName: String, args: Array<out Any?>?) {
        if (methodName != "finishActivity" || args == null) return
        val frameworkToken = args.firstOrNull { it is IBinder } as? IBinder ?: return
        val identity = identities[frameworkToken] ?: return
        val tokenIndex = args.indexOfFirst { it === frameworkToken }
        val resultCode = args.drop(tokenIndex + 1).firstOrNull { it is Int } as? Int ?: return
        val data = args.drop(tokenIndex + 1).firstOrNull { it is Intent } as? Intent
        val receipt = listOf(
            identity.instanceId,
            identity.virtualActivityToken,
            resultCode.toString(),
            data?.action.orEmpty(),
            data?.dataString.orEmpty()
        ).joinToString("\u0000")
        if (!finishReceipts.add(receipt)) return

        val recorded = operations?.recordActivityResultForFinish(
            instanceId = identity.instanceId,
            token = identity.virtualActivityToken,
            resultCode = resultCode,
            dataIntent = data?.toVirtualIntentSnapshot()
        ) ?: VirtualActivityFinishResultRecord(
            instanceId = identity.instanceId,
            resultCode = resultCode,
            dataIntent = data?.toVirtualIntentSnapshot(),
            recorded = false,
            reason = "ACTIVITY_RESULT_AUTHORITY_NOT_INSTALLED"
        )
        writeEvidence(
            identity = identity,
            lines = listOf(
                "status=${if (recorded.recorded) "ACTIVITY_FINISH_RESULT_RECORDED" else "ACTIVITY_FINISH_RESULT_SKIPPED"}",
                "stage=ACTIVITY_FINISH_RESULT_CAPTURE",
                "instanceId=${identity.instanceId}",
                "guestActivityClassName=${identity.guestActivityClassName}",
                "token=<redacted>",
                "sourceToken=${if (recorded.sourceToken.isNullOrBlank()) "" else "<redacted>"}",
                "requestCode=${recorded.requestCode}",
                "resultCode=$resultCode",
                "virtualResultRecorded=${recorded.recorded}",
                "virtualResultReason=${recorded.reason}",
                "dataAction=${data?.action.orEmpty()}",
                "dataUri=${data?.dataString?.let(EvidenceSanitizer::redactUriForEvidence).orEmpty()}",
                "frameworkDispatchMode=ANDROID_NATIVE_RESULT_ROUTE",
                "syntheticDispatchAttempted=false"
            ),
            append = true
        )
    }

    fun captureActivityResults(messageObject: Any?): List<FrameworkActivityResultDelivery> {
        if (messageObject == null) return emptyList()
        val transactionToken = readField(messageObject, listOf("mActivityToken", "activityToken")) as? IBinder
        return transactionItems(messageObject).flatMap { item ->
            if (!item.javaClass.name.endsWith("ActivityResultItem")) return@flatMap emptyList()
            val frameworkToken = (readField(
                item,
                listOf("mActivityToken", "activityToken")
            ) as? IBinder) ?: transactionToken ?: return@flatMap emptyList()
            val identity = identities[frameworkToken] ?: return@flatMap emptyList()
            val resultInfo = readField(
                item,
                listOf("mResultInfoList", "mResultInfo", "mResults", "results")
            ) as? Iterable<*> ?: return@flatMap emptyList()
            resultInfo.mapNotNull { result ->
                result ?: return@mapNotNull null
                val requestCode = readField(result, listOf("mRequestCode", "requestCode")) as? Int
                    ?: return@mapNotNull null
                val resultCode = readField(result, listOf("mResultCode", "resultCode")) as? Int
                    ?: return@mapNotNull null
                FrameworkActivityResultDelivery(
                    identity = identity,
                    requestCode = requestCode,
                    resultCode = resultCode,
                    resultWho = readField(result, listOf("mResultWho", "resultWho")) as? String,
                    data = readField(result, listOf("mData", "data")) as? Intent
                )
            }
        }
    }

    fun completeActivityResultDelivery(deliveries: List<FrameworkActivityResultDelivery>) {
        deliveries.forEach { delivery ->
            val consumed = operations?.consumeActivityResult(
                instanceId = delivery.identity.instanceId,
                token = delivery.identity.virtualActivityToken
            )
            val delivered = consumed.matches(delivery)
            val reason = when {
                consumed == null -> "NO_VIRTUAL_RESULT_RECORD"
                consumed.requestCode != delivery.requestCode -> "VIRTUAL_RESULT_REQUEST_CODE_MISMATCH"
                consumed.resultCode != delivery.resultCode -> "VIRTUAL_RESULT_CODE_MISMATCH"
                else -> ""
            }
            writeEvidence(
                identity = delivery.identity,
                lines = listOf(
                    "status=${if (delivered) "ACTIVITY_RESULT_DELIVERED" else "ACTIVITY_RESULT_PARTIAL"}",
                    "stage=ACTIVITY_RESULT_FRAMEWORK_DELIVERY",
                    "instanceId=${delivery.identity.instanceId}",
                    "guestActivityClassName=${delivery.identity.guestActivityClassName}",
                    "token=<redacted>",
                    "requestCode=${delivery.requestCode}",
                    "resultCode=${delivery.resultCode}",
                    "resultWho=${delivery.resultWho.orEmpty()}",
                    "virtualResultConsumed=${consumed != null}",
                    "virtualResultRequestCode=${consumed?.requestCode ?: -1}",
                    "virtualResultCode=${consumed?.resultCode ?: 0}",
                    "dataAction=${delivery.data?.action.orEmpty()}",
                    "virtualDataAction=${consumed?.dataIntent?.action.orEmpty()}",
                    "frameworkDispatchMode=ANDROID_ACTIVITY_RESULT_ITEM",
                    "syntheticDispatchAttempted=false",
                    "reason=$reason"
                ),
                append = true
            )
        }
    }

    internal fun clearForTests() {
        identities.clear()
        finishReceipts.clear()
        operations = null
    }

    private fun VirtualActivityResult?.matches(delivery: FrameworkActivityResultDelivery): Boolean =
        this != null && requestCode == delivery.requestCode && resultCode == delivery.resultCode

    private fun transactionItems(messageObject: Any): List<Any> {
        val direct = if (messageObject.javaClass.name.endsWith("ActivityResultItem")) {
            listOf(messageObject)
        } else {
            emptyList()
        }
        if (direct.isNotEmpty()) return direct
        val value = readField(
            messageObject,
            listOf("mTransactionItems", "mActivityCallbacks", "mCallbacks", "callbacks")
        )
        return when (value) {
            is Iterable<*> -> value.filterNotNull()
            is Array<*> -> value.filterNotNull()
            else -> emptyList()
        }
    }

    private fun readField(target: Any, names: List<String>): Any? {
        names.forEach { name ->
            findField(target.javaClass, name)?.let { field ->
                runCatching {
                    field.isAccessible = true
                    return field.get(target)
                }
            }
        }
        return null
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { return current.getDeclaredField(name) }
            current = current.superclass
        }
        return null
    }

    private fun Intent.toVirtualIntentSnapshot() = com.multiapp.core.model.virtual.VirtualIntentSnapshot(
        flags = runCatching { flags }.getOrDefault(0),
        action = runCatching { action }.getOrNull(),
        dataUri = runCatching { dataString }.getOrNull(),
        categories = runCatching { categories.orEmpty().toSet() }.getOrDefault(emptySet()),
        extras = runCatching { extras?.keySet()?.associateWith { "<present>" }.orEmpty() }.getOrDefault(emptyMap())
    )

    private fun writeEvidence(
        identity: HostedFrameworkActivityIdentity,
        lines: List<String>,
        append: Boolean
    ) {
        runCatching {
            val evidenceDir = File(identity.hostFilesDir, EVIDENCE_DIR).apply { mkdirs() }.canonicalFile
            val file = File(evidenceDir, HostedActivityEvidenceFiles.result(identity.instanceId)).canonicalFile
            require(file.parentFile == evidenceDir) { "Activity result evidence path escapes evidence dir" }
            val text = lines.joinToString("\n", transform = EvidenceSanitizer::sanitizeEvidenceLine)
            if (append && file.isFile) file.appendText("\n---\n$text") else file.writeText(text)
        }
    }
}
