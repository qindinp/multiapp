package com.multiapp.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.multiapp.core.identity.DeviceIdentityPool
import com.multiapp.core.model.IdentityConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentityHookTest {

    @Test
    fun generateIdentity_returnsValidConfig() {
        val instanceId = "test_hook_001"
        val originalPkg = "com.example.target"

        val identity = DeviceIdentityPool.generateIdentity(instanceId, originalPkg)

        assertNotNull(identity)
        assertEquals(instanceId, identity.instanceId)
        assertEquals(originalPkg, identity.originalPackageName)
        assertNotNull(identity.stubPackageName)
        assertTrue(identity.stubPackageName.contains(originalPkg))
        assertTrue(identity.stubPackageName.contains("clone"))
    }

    @Test
    fun generateIdentity_uniquePerInstance() {
        val identity1 = DeviceIdentityPool.generateIdentity("hook_001", "com.example.a")
        val identity2 = DeviceIdentityPool.generateIdentity("hook_002", "com.example.a")

        assertNotEquals(identity1.instanceId, identity2.instanceId)
        assertNotEquals(identity1.stubPackageName, identity2.stubPackageName)
    }

    @Test
    fun generateIdentity_hasRequiredDeviceFields() {
        val identity = DeviceIdentityPool.generateIdentity("hook_003", "com.example.b")

        assertNotNull(identity.imei)
        assertNotNull(identity.androidId)
        assertNotNull(identity.macAddress)
        assertNotNull(identity.serial)
        assertNotNull(identity.buildModel)
        assertNotNull(identity.buildManufacturer)
        assertNotNull(identity.buildFingerprint)
        assertNotNull(identity.buildBrand)
        assertNotNull(identity.buildDevice)
        assertNotNull(identity.buildProduct)
        assertNotNull(identity.versionRelease)
        assertTrue(identity.sdkInt > 0)
    }

    @Test
    fun generateIdentity_imeiIsNotEmpty() {
        val identity = DeviceIdentityPool.generateIdentity("hook_004", "com.example.c")

        assertFalse(identity.imei.isBlank())
        assertTrue(identity.imei.length >= 14)
    }

    @Test
    fun generateIdentity_androidIdIsValidHex() {
        val identity = DeviceIdentityPool.generateIdentity("hook_005", "com.example.d")

        assertTrue(identity.androidId.matches(Regex("[0-9a-fA-F]+")))
    }

    @Test
    fun generateIdentity_macAddressFormat() {
        val identity = DeviceIdentityPool.generateIdentity("hook_006", "com.example.e")

        assertTrue(identity.macAddress.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")))
    }

    @Test
    fun generateIdentity_sameInputs_sameStubPackage() {
        val identity1 = DeviceIdentityPool.generateIdentity("same_id", "com.same.pkg")
        val identity2 = DeviceIdentityPool.generateIdentity("same_id", "com.same.pkg")

        assertEquals(identity1.stubPackageName, identity2.stubPackageName)
    }

    @Test
    fun generateIdentity_differentOriginalPkg_differentStub() {
        val identity1 = DeviceIdentityPool.generateIdentity("id_a", "com.pkg.one")
        val identity2 = DeviceIdentityPool.generateIdentity("id_b", "com.pkg.two")

        assertNotEquals(identity1.stubPackageName, identity2.stubPackageName)
    }
}
