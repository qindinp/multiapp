package com.multiapp.app.container

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Pure JVM tests for [ContainerActivity].
 *
 * Only constant values and companion-object contracts are testable without
 * Robolectric or an instrumented runner. The [ContainerActivity.createIntent]
 * method produces an Android [android.content.Intent] which requires a real
 * or shadowed Android environment -- those tests belong in androidTest.
 */
class ContainerActivityTest {

    @Test
    @DisplayName("EXTRA_INSTANCE_ID constant has correct value")
    fun extraInstanceIdConstant() {
        assertEquals("multiapp.instanceId", ContainerActivity.EXTRA_INSTANCE_ID)
    }

    @Test
    @DisplayName("EXTRA_INSTALL_ORIGIN constant has correct value")
    fun extraInstallOriginConstant() {
        assertEquals("multiapp.installOrigin", ContainerActivity.EXTRA_INSTALL_ORIGIN)
    }

    @Test
    @DisplayName("EXTRA_INSTANCE_ID key matches expected intent contract")
    fun extraInstanceIdKeyMatchesContract() {
        // Ensures the constant string used in Manifest extras matches what the
        // Activity reads in onCreate. If someone renames the key in one place
        // but not the other, this assertion holds them to the contract.
        val expectedKey = "multiapp.instanceId"
        assertEquals(expectedKey, ContainerActivity.EXTRA_INSTANCE_ID)
    }

    @Test
    @DisplayName("EXTRA_INSTALL_ORIGIN key matches expected intent contract")
    fun extraInstallOriginKeyMatchesContract() {
        val expectedKey = "multiapp.installOrigin"
        assertEquals(expectedKey, ContainerActivity.EXTRA_INSTALL_ORIGIN)
    }
}
