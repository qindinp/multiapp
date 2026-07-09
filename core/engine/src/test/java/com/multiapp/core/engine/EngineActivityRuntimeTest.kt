package com.multiapp.core.engine

import android.content.Intent
import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.model.virtual.VirtualActivityRecord
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineActivityRuntimeTest {

    @Test
    fun `proxy slots are exposed through engine facade`() {
        val hostPackageName = "com.multiapp.app"

        val classNames = EngineProxyActivitySlots.classNames(hostPackageName)
        val launchModes = EngineProxyActivitySlots.launchModeByClassName(hostPackageName)

        assertEquals(24, classNames.size)
        assertEquals("com.multiapp.app.container.ProxyActivity0", classNames.first())
        assertEquals(null, launchModes["com.multiapp.app.container.ProxyActivity0"])
        assertEquals("singleTop", launchModes["com.multiapp.app.container.ProxyActivitySingleTop0"])
        assertEquals("singleTask", launchModes["com.multiapp.app.container.ProxyActivitySingleTask0"])
    }

    @Test
    fun `proxy observation recovers missing record from scalar extras`() {
        val manager = VirtualActivityRecordManager()
        val records = EngineProxyActivityRecords(manager)
        val proxyIntent = proxyIntent(
            launchMode = "singleTop",
            taskAffinity = "com.example.task",
            flagsValue = Intent.FLAG_ACTIVITY_NEW_TASK
        )

        val observation = records.observeProxyIntent(
            EngineProxyActivityObserveRequest(
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivitySingleTop0",
                proxyIntent = proxyIntent,
                instanceId = "inst-001",
                token = "token-001",
                guestActivityClassName = "com.example.app.MainActivity",
                originPackageName = "com.example.app"
            )
        )

        val recoveredRecord = assertNotNull(manager.resolve("token-001"))
        assertFalse(observation.recordFound)
        assertTrue(observation.recordRecovered)
        assertEquals("singleTop", recoveredRecord.launchMode)
        assertEquals("com.example.task", recoveredRecord.taskAffinity)
        assertEquals(0, recoveredRecord.intentFlags)
    }

    @Test
    fun `proxy observation does not recover incomplete identity`() {
        val manager = VirtualActivityRecordManager()
        val observation = EngineProxyActivityRecords(manager).observeProxyIntent(
            EngineProxyActivityObserveRequest(
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                proxyIntent = proxyIntent(),
                instanceId = "",
                token = "token-001",
                guestActivityClassName = "com.example.app.MainActivity",
                originPackageName = "com.example.app"
            )
        )

        assertFalse(observation.recordFound)
        assertFalse(observation.recordRecovered)
        assertEquals(null, manager.resolve("token-001"))
    }

    @Test
    fun `proxy observation rejects existing token with mismatched owner`() {
        val manager = VirtualActivityRecordManager()
        manager.register(
            activityRecord(
                token = "token-001",
                instanceId = "inst-001",
                originPackageName = "com.example.app",
                guestActivityClassName = "com.example.app.MainActivity",
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0"
            )
        )

        val observation = EngineProxyActivityRecords(manager).observeProxyIntent(
            EngineProxyActivityObserveRequest(
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                proxyIntent = proxyIntent(),
                instanceId = "inst-evil",
                token = "token-001",
                guestActivityClassName = "com.evil.app.MainActivity",
                originPackageName = "com.evil.app"
            )
        )

        assertFalse(observation.recordFound)
        assertFalse(observation.recordRecovered)
        assertEquals("inst-001", manager.resolve("token-001")?.instanceId)
    }

    @Test
    fun `proxy observation does not recover forged token over different slot owner`() {
        val manager = VirtualActivityRecordManager()
        manager.register(
            activityRecord(
                token = "token-001",
                instanceId = "inst-001",
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                taskAffinity = "com.example.app:inst-001"
            )
        )

        val observation = EngineProxyActivityRecords(manager).observeProxyIntent(
            EngineProxyActivityObserveRequest(
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                proxyIntent = proxyIntent(token = "token-forged", taskAffinity = "com.evil.app:inst-evil"),
                instanceId = "inst-evil",
                token = "token-forged",
                guestActivityClassName = "com.evil.app.MainActivity",
                originPackageName = "com.evil.app"
            )
        )

        assertFalse(observation.recordFound)
        assertFalse(observation.recordRecovered)
        assertEquals("token-001", manager.resolveByProxy("com.multiapp.app.container.ProxyActivity0")?.token)
        assertEquals(null, manager.resolve("token-forged"))
    }

    @Test
    fun `proxy observation recovers same slot owner with different activity token`() {
        val manager = VirtualActivityRecordManager()
        manager.register(
            activityRecord(
                token = "token-001",
                guestActivityClassName = "com.example.app.RootActivity",
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                taskAffinity = "com.example.app:inst-001"
            )
        )

        val observation = EngineProxyActivityRecords(manager).observeProxyIntent(
            EngineProxyActivityObserveRequest(
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                proxyIntent = proxyIntent(token = "token-detail", taskAffinity = "com.example.app:inst-001"),
                instanceId = "inst-001",
                token = "token-detail",
                guestActivityClassName = "com.example.app.DetailActivity",
                originPackageName = "com.example.app"
            )
        )

        assertFalse(observation.recordFound)
        assertTrue(observation.recordRecovered)
        assertEquals("token-detail", manager.resolve("token-detail")?.token)
    }

    private fun activityRecord(
        token: String,
        instanceId: String = "inst-001",
        originPackageName: String = "com.example.app",
        guestActivityClassName: String = "com.example.app.MainActivity",
        proxyActivityClassName: String = "com.multiapp.app.container.ProxyActivity0",
        launchMode: String? = null,
        taskAffinity: String? = null
    ): VirtualActivityRecord = VirtualActivityRecord(
        token = token,
        instanceId = instanceId,
        originPackageName = originPackageName,
        guestActivityClassName = guestActivityClassName,
        proxyActivityClassName = proxyActivityClassName,
        launchMode = launchMode,
        taskAffinity = taskAffinity
    )

    private fun proxyIntent(
        launchMode: String? = null,
        taskAffinity: String? = null,
        flagsValue: Int = 0,
        token: String = "token-001"
    ): Intent = mockk(relaxed = true) {
        every { getStringExtra("multiapp.guestActivityLaunchMode") } returns launchMode
        every { getStringExtra("multiapp.guestTaskAffinity") } returns taskAffinity
        every { getStringExtra("multiapp.virtualActivityToken") } returns token
        every { flags } returns flagsValue
    }
}
