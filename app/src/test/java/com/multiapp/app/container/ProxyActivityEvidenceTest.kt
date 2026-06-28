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
            result = VirtualActivityResult(resultCode = 42)
        ).toLines()

        assertTrue("status=PROXY_ACTIVITY_BASE_ONCREATE" in lines)
        assertTrue("pendingNewIntentConsumed=true" in lines)
        assertTrue("pendingAction=com.test.ACTION" in lines)
        assertTrue("pendingFlags=7" in lines)
        assertTrue("resultConsumed=true" in lines)
        assertTrue("resultCode=42" in lines)
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
        assertTrue("pendingAction=" in lines)
        assertTrue("pendingFlags=0" in lines)
        assertTrue("resultConsumed=false" in lines)
        assertTrue("resultCode=0" in lines)
    }
}
