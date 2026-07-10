package com.multiapp.app.container

import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyActivityEvidenceTest {
    @Test
    fun `evidence lines include consumed pending new intent and result`() {
        val lines = ProxyActivityEvidence(
            proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
            token = "token-1",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.MainActivity",
            recordFound = true,
            pendingNewIntent = VirtualActivityPendingNewIntent(
                eventId = 1L,
                sourceToken = "token-2",
                intentFlags = 7,
                dataIntent = VirtualIntentSnapshot(action = "com.test.ACTION")
            ),
            result = VirtualActivityResult(resultCode = 42),
            taskDescriptionLabel = "com.test.minimal #inst-001",
            taskId = 3,
            taskAffinity = "com.test.minimal:inst-001",
            launchMode = "singleTop",
            intentFlags = 0x10000000.toInt()
        ).toLines()

        assertTrue("status=PROXY_ACTIVITY_BASE_ONCREATE" in lines)
        assertTrue("pendingNewIntentConsumed=true" in lines)
        assertTrue("pendingNewIntentObserved=true" in lines)
        assertTrue("pendingAction=com.test.ACTION" in lines)
        assertTrue("pendingFlags=7" in lines)
        assertTrue("resultConsumed=true" in lines)
        assertTrue("resultObserved=true" in lines)
        assertTrue("resultCode=42" in lines)
        assertTrue("taskDescriptionLabel=com.test.minimal #inst-001" in lines)
        assertTrue("taskId=3" in lines)
        assertTrue("taskAffinity=com.test.minimal:inst-001" in lines)
        assertTrue("launchMode=singleTop" in lines)
        assertTrue("intentFlags=268435456" in lines)
        assertTrue("substitutionVerdict=UNSUBSTITUTED_PROXY" in lines)
        assertTrue("fallbackAction=" in lines)
        assertTrue("activityRecordRecovered=false" in lines)
    }

    @Test
    fun `evidence lines use stable empty values when no event was consumed`() {
        val lines = ProxyActivityEvidence(
            proxyActivityClassName = "ProxyActivity0",
            token = "token-1",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.MainActivity",
            recordFound = false
        ).toLines()

        assertTrue("pendingNewIntentConsumed=false" in lines)
        assertTrue("pendingNewIntentObserved=false" in lines)
        assertTrue("pendingAction=" in lines)
        assertTrue("pendingFlags=0" in lines)
        assertTrue("resultConsumed=false" in lines)
        assertTrue("resultObserved=false" in lines)
        assertTrue("resultCode=0" in lines)
        assertTrue("substitutionVerdict=UNSUBSTITUTED_PROXY" in lines)
        assertTrue("activityRecordRecovered=false" in lines)
    }

    @Test
    fun `evidence lines include proxy fallback action`() {
        val lines = ProxyActivityEvidence(
            proxyActivityClassName = "ProxyActivity0",
            token = "token-1",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.MainActivity",
            recordFound = false,
            fallbackAction = "finishUnsubstitutedProxy"
        ).toLines()

        assertTrue("substitutionVerdict=UNSUBSTITUTED_PROXY" in lines)
        assertTrue("fallbackAction=finishUnsubstitutedProxy" in lines)
    }

    @Test
    fun `evidence can observe pending intent and result without consuming them`() {
        val lines = ProxyActivityEvidence(
            proxyActivityClassName = "ProxyActivity0",
            token = "token-1",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.MainActivity",
            recordFound = true,
            pendingNewIntent = VirtualActivityPendingNewIntent(
                eventId = 1L,
                sourceToken = "token-2",
                intentFlags = 7,
                dataIntent = VirtualIntentSnapshot(action = "com.test.ACTION")
            ),
            result = VirtualActivityResult(resultCode = 42),
            pendingNewIntentConsumed = false,
            resultConsumed = false,
            fallbackAction = "prewarmAndRelaunchProxy"
        ).toLines()

        assertTrue("pendingNewIntentObserved=true" in lines)
        assertTrue("pendingNewIntentConsumed=false" in lines)
        assertTrue("resultObserved=true" in lines)
        assertTrue("resultConsumed=false" in lines)
        assertTrue("fallbackAction=prewarmAndRelaunchProxy" in lines)
    }

    @Test
    fun `evidence lines distinguish onNewIntent relaunch`() {
        val lines = ProxyActivityEvidence(
            proxyActivityClassName = "ProxyActivitySingleTop0",
            token = "token-1",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.MainActivity",
            recordFound = true,
            lifecycleEvent = "onNewIntent"
        ).toLines()

        assertTrue("status=PROXY_ACTIVITY_BASE_ONNEWINTENT" in lines)
        assertTrue("lifecycleEvent=onNewIntent" in lines)
    }

    @Test
    fun `evidence lines mark recovered proxy record`() {
        val lines = ProxyActivityEvidence(
            proxyActivityClassName = "ProxyActivity0",
            token = "token-1",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.MainActivity",
            recordFound = false,
            recordRecovered = true
        ).toLines()

        assertTrue("activityRecordFound=false" in lines)
        assertTrue("activityRecordRecovered=true" in lines)
    }

    @Test
    fun `evidence lines redact activity token`() {
        val rawToken = "raw-activity-token-super-secret"
        val lines = ProxyActivityEvidence(
            proxyActivityClassName = "ProxyActivity0",
            token = rawToken,
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.MainActivity",
            recordFound = true
        ).toLines()

        assertTrue("token=<redacted>" in lines)
        assertTrue(lines.none { it.contains(rawToken) }, "evidence leaked raw token in $lines")
    }

    @Test
    fun `task description label includes origin package and short instance id`() {
        val label = ProxyTaskDescriptions.label(
            originPackageName = "com.tencent.mobileqq",
            instanceId = "ffc737401234abcd"
        )

        assertTrue(label == "com.tencent.mobileqq #ffc73740")
    }
}
