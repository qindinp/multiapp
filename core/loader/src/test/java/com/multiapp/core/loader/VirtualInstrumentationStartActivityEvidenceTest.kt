package com.multiapp.core.loader

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualInstrumentationStartActivityEvidenceTest {

    @Test
    fun `hostedStartActivitySource prefers explicit target activity`() {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val target = mockk<Activity>(relaxed = true)
        val who = mockk<Activity>(relaxed = true)

        val resolved = instrumentation.hostedStartActivitySource(target = target, who = who)

        assertSame(target, resolved)
    }

    @Test
    fun `hostedStartActivitySource falls back to Activity context for non Activity overloads`() {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val who = mockk<Activity>(relaxed = true)

        val resolved = instrumentation.hostedStartActivitySource(who = who)

        assertSame(who, resolved)
    }

    @Test
    fun `hostedStartActivitySource ignores non Activity context`() {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val who = mockk<Context>(relaxed = true)

        val resolved = instrumentation.hostedStartActivitySource(who = who)

        assertEquals(null, resolved)
    }

    @Test
    fun `remap evidence records overload and unsupported result routing`(@TempDir filesDir: File) {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))

        invokePrivate(
            instrumentation,
            "writeRemapEvidence",
            arrayOf(
                File::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Integer.TYPE,
                String::class.java,
                String::class.java
            ),
            filesDir,
            "inst-001",
            "com.test.minimal.DetailActivity",
            "com.multiapp.app.container.ProxyActivitySingleTop0",
            "execStartActivity:activity-options",
            42,
            "explicit",
            "singleTop"
        )

        val lines = remapEvidenceLines(filesDir)
        assertTrue("status=GUEST_ACTIVITY_REMAP" in lines)
        assertTrue("stage=ACTIVITY_START_REMAP" in lines)
        assertTrue("api=execStartActivity:activity-options" in lines)
        assertTrue("hostFallback=false" in lines)
        assertTrue("requestCode=42" in lines)
        assertTrue("resultRequested=true" in lines)
        assertTrue("activityResultVerdict=UNSUPPORTED" in lines)
        assertTrue("activityResultVerdictReason=HOST_PROXY_RESULT_ROUTING_NOT_IMPLEMENTED" in lines)
        assertTrue("launchMode=singleTop" in lines)
    }

    @Test
    fun `skipped remap evidence records host fallback and not requested result`(@TempDir filesDir: File) {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val intent = mockk<Intent>(relaxed = true) {
            every { action } returns "com.test.ACTION"
            every { component } returns null
            every { dataString } returns null
        }

        invokePrivate(
            instrumentation,
            "writeRemapSkippedEvidence",
            arrayOf(
                File::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Integer.TYPE,
                Intent::class.java
            ),
            filesDir,
            "inst-001",
            "INTENT_NOT_RESOLVED",
            "execStartActivity:string-options",
            -1,
            intent
        )

        val lines = remapEvidenceLines(filesDir)
        assertTrue("status=GUEST_ACTIVITY_REMAP_SKIPPED" in lines)
        assertTrue("api=execStartActivity:string-options" in lines)
        assertTrue("hostFallback=true" in lines)
        assertTrue("requestCode=-1" in lines)
        assertTrue("resultRequested=false" in lines)
        assertTrue("activityResultVerdict=NOT_REQUESTED" in lines)
        assertTrue("activityResultVerdictReason=" in lines)
        assertTrue("reason=INTENT_NOT_RESOLVED" in lines)
    }

    private fun remapEvidenceLines(filesDir: File): List<String> {
        val file = File(filesDir, "hosted_launch_evidence/inst-001.activity-remap.properties")
        assertTrue(file.isFile)
        return file.readLines().map { it.trim() }
    }

    private fun invokePrivate(
        target: Any,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any?
    ) {
        val method = target.javaClass.getDeclaredMethod(methodName, *parameterTypes)
        method.isAccessible = true
        method.invoke(target, *args)
    }
}
