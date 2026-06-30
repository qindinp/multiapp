package com.multiapp.core.loader

import android.content.Intent
import android.content.pm.ActivityInfo
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActivityClientRecordBridgeTest {

    @Test
    fun `patch replaces ActivityClientRecord guest launch fields`() {
        val record = FakeActivityClientRecord()
        val activityInfo = ActivityInfo().apply {
            packageName = "com.test.minimal"
            name = "com.test.minimal.MainActivity"
            theme = 0x7f010001
        }
        val intent = Intent(Intent.ACTION_MAIN)
        val loadedApk = Any()

        val result = ActivityClientRecordBridge.patch(
            record = record,
            state = ActivityClientRecordRuntimeState(
                activityInfo = activityInfo,
                intent = intent,
                loadedApk = loadedApk
            )
        )

        assertTrue("activityInfo" in result.patchedFields)
        assertTrue("intent" in result.patchedFields)
        assertTrue("packageInfo" in result.patchedFields)
        assertSame(activityInfo, record.activityInfo)
        assertSame(intent, record.intent)
        assertSame(loadedApk, record.packageInfo)
    }

    @Test
    fun `patchCurrentActivityRecord finds record from ActivityThread activities map`() {
        val token = Any()
        val record = FakeActivityClientRecord().apply { this.token = token }
        val activityThread = FakeActivityThread(token, record)
        val activity = FakeActivity(token)
        val activityInfo = ActivityInfo().apply { name = "Guest" }

        val result = ActivityClientRecordBridge.patchCurrentActivityRecord(
            activityThread = activityThread,
            activity = activity,
            state = ActivityClientRecordRuntimeState(
                activityInfo = activityInfo,
                intent = Intent("guest.intent"),
                loadedApk = null
            )
        )

        assertEquals(null, result.skippedReason)
        assertSame(activityInfo, record.activityInfo)
    }

    @Test
    fun `patch preserves virtual package ActivityInfo in ActivityClientRecord`() {
        val record = FakeActivityClientRecord()
        val activityInfo = ActivityInfo().apply {
            packageName = "com.multiapp.instance.abc"
            name = "com.test.minimal.MainActivity"
        }

        val result = ActivityClientRecordBridge.patch(
            record = record,
            state = ActivityClientRecordRuntimeState(
                activityInfo = activityInfo,
                intent = Intent("guest.intent"),
                loadedApk = Any()
            )
        )

        assertEquals(null, result.skippedReason)
        assertEquals("com.multiapp.instance.abc", record.activityInfo?.packageName)
    }

    @Test
    fun `patch keeps existing package info when replacement LoadedApk is null`() {
        val originalLoadedApk = Any()
        val record = FakeActivityClientRecord().apply {
            packageInfo = originalLoadedApk
        }
        val activityInfo = ActivityInfo().apply {
            packageName = "com.multiapp.instance.abc"
            name = "com.test.minimal.MainActivity"
        }

        val result = ActivityClientRecordBridge.patch(
            record = record,
            state = ActivityClientRecordRuntimeState(
                activityInfo = activityInfo,
                intent = Intent("guest.intent"),
                loadedApk = null
            )
        )

        assertEquals(null, result.skippedReason)
        assertTrue("activityInfo" in result.patchedFields)
        assertTrue("intent" in result.patchedFields)
        assertTrue("packageInfo" !in result.patchedFields)
        assertTrue("packageInfo" in result.skippedFields)
        assertSame(originalLoadedApk, record.packageInfo)
    }

    @Suppress("unused")
    private class FakeActivityClientRecord {
        var token: Any? = null
        var activityInfo: ActivityInfo? = null
        var intent: Intent? = null
        var packageInfo: Any? = null
    }

    @Suppress("unused")
    private class FakeActivityThread(token: Any, record: FakeActivityClientRecord) {
        private val mActivities = linkedMapOf<Any?, Any?>(token to WeakReference(record))
    }

    @Suppress("unused")
    private class FakeActivity(private val token: Any) : android.app.Activity() {
        private val mToken: Any = token
    }
}
