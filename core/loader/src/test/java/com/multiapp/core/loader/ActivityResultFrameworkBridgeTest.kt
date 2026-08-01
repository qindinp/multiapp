package com.multiapp.core.loader

import android.app.Activity
import android.content.Intent
import android.os.IBinder
import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityResultFrameworkBridgeTest {
    @AfterTest
    fun tearDown() {
        VirtualActivityResultFrameworkBridge.clearForTests()
    }

    @Test
    fun `finish boundary records once and real ActivityResultItem completes delivery`(@TempDir filesDir: File) {
        val operations = RecordingOperations()
        val childFrameworkToken = mockk<IBinder>()
        val sourceFrameworkToken = mockk<IBinder>()
        val resultIntent = mockk<Intent>(relaxed = true) {
            every { action } returns "com.test.RESULT"
            every { dataString } returns null
            every { flags } returns 0
            every { categories } returns emptySet()
            every { extras } returns null
        }
        VirtualActivityResultFrameworkBridge.install(operations)
        VirtualActivityResultFrameworkBridge.remember(
            childFrameworkToken,
            identity("child-token", "com.test.SecondActivity", filesDir)
        )
        VirtualActivityResultFrameworkBridge.remember(
            sourceFrameworkToken,
            identity("source-token", "com.test.MainActivity", filesDir)
        )

        val finishArgs = arrayOf<Any?>(childFrameworkToken, Activity.RESULT_OK, resultIntent, 0)
        VirtualActivityResultFrameworkBridge.captureFinishActivity("finishActivity", finishArgs)
        VirtualActivityResultFrameworkBridge.captureFinishActivity("finishActivity", finishArgs)

        assertEquals(1, operations.finishRecords.size)
        assertEquals("child-token", operations.finishRecords.single().second)
        assertEquals(Activity.RESULT_OK, operations.finishRecords.single().third)

        val transaction = FakeClientTransaction(
            listOf(
                FakeActivityResultItem(
                    sourceFrameworkToken,
                    listOf(FakeResultInfo("guest", 4242, Activity.RESULT_OK, resultIntent))
                )
            )
        )
        val deliveries = VirtualActivityResultFrameworkBridge.captureActivityResults(transaction)
        VirtualActivityResultFrameworkBridge.completeActivityResultDelivery(deliveries)

        assertEquals(1, deliveries.size)
        assertEquals(4242, deliveries.single().requestCode)
        assertEquals(listOf("inst-001" to "source-token"), operations.consumeCalls)
        val evidence = File(
            filesDir,
            "hosted_launch_evidence/${HostedActivityEvidenceFiles.result("inst-001")}"
        ).readText()
        assertTrue("status=ACTIVITY_FINISH_RESULT_RECORDED" in evidence)
        assertTrue("stage=ACTIVITY_RESULT_FRAMEWORK_DELIVERY" in evidence)
        assertTrue("status=ACTIVITY_RESULT_DELIVERED" in evidence)
        assertTrue("frameworkDispatchMode=ANDROID_ACTIVITY_RESULT_ITEM" in evidence)
        assertTrue("syntheticDispatchAttempted=false" in evidence)
    }

    private fun identity(token: String, className: String, filesDir: File) =
        HostedFrameworkActivityIdentity(
            instanceId = "inst-001",
            virtualActivityToken = token,
            guestActivityClassName = className,
            hostFilesDir = filesDir
        )

    private class RecordingOperations : VirtualActivityOperations {
        val finishRecords = mutableListOf<Triple<String, String, Int>>()
        val consumeCalls = mutableListOf<Pair<String, String>>()
        private var pending: VirtualActivityResult? = VirtualActivityResult(
            resultCode = Activity.RESULT_OK,
            dataIntent = VirtualIntentSnapshot(action = "com.test.RESULT"),
            requestCode = 4242,
            resultWho = "guest"
        )

        override fun consumePendingNewIntent(instanceId: String, token: String): VirtualActivityPendingNewIntent? = null

        override fun recordActivityResultForFinish(
            instanceId: String,
            token: String,
            resultCode: Int,
            dataIntent: VirtualIntentSnapshot?
        ): VirtualActivityFinishResultRecord {
            finishRecords += Triple(instanceId, token, resultCode)
            return VirtualActivityFinishResultRecord(
                instanceId = instanceId,
                sourceToken = "source-token",
                requestCode = 4242,
                resultCode = resultCode,
                dataIntent = dataIntent,
                recorded = true,
                reason = ""
            )
        }

        override fun setActivityResult(
            instanceId: String,
            token: String,
            resultCode: Int,
            dataIntent: VirtualIntentSnapshot?,
            requestCode: Int,
            resultWho: String?,
            frameworkDispatchAttempted: Boolean,
            frameworkDispatchInvoked: Boolean
        ): Boolean = true

        override fun consumeActivityResult(instanceId: String, token: String): VirtualActivityResult? {
            consumeCalls += instanceId to token
            return pending.also { pending = null }
        }

        override fun consumeActivityResultForResumeFallback(instanceId: String, token: String): VirtualActivityResult? = null

        override fun markActivityResultDispatchState(
            instanceId: String,
            token: String,
            frameworkDispatchAttempted: Boolean,
            frameworkDispatchInvoked: Boolean
        ): Boolean = true

        override fun finishActivity(instanceId: String, token: String): Boolean = true
    }

    @Suppress("unused")
    private class FakeClientTransaction(private val mTransactionItems: List<Any>)

    @Suppress("unused")
    private class FakeActivityResultItem(
        private val mActivityToken: IBinder,
        private val mResultInfoList: List<Any>
    )

    @Suppress("unused")
    private class FakeResultInfo(
        private val mResultWho: String?,
        private val mRequestCode: Int,
        private val mResultCode: Int,
        private val mData: Intent?
    )
}
