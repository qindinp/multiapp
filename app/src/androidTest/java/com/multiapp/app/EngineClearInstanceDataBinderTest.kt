package com.multiapp.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualizationEngine
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EngineClearInstanceDataBinderTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var virtualizationEngine: VirtualizationEngine

    @Before
    fun injectEngine() {
        hiltRule.inject()
    }

    @Test
    fun clearMissingInstanceReturnsTheAuthoritativeEngineFailureAcrossBinder() {
        val instanceId = "missing-clear-binder-contract"

        val result = virtualizationEngine.clearInstanceData(instanceId)

        assertEquals(EngineResultStatus.FAIL, result.status)
        assertEquals("clearInstanceData", result.operation)
        assertEquals(instanceId, result.instanceId)
        assertEquals("instance_not_found:$instanceId", result.message)
    }
}
