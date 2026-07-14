package com.multiapp.core.loader

import android.os.Bundle
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.util.IdentityHashMap

internal class MockAndroidBundleSupport : AutoCloseable {
    private val values = IdentityHashMap<Bundle, MutableMap<String, Any?>>()

    init {
        mockkConstructor(Bundle::class)
        every { anyConstructed<Bundle>().putString(any(), any()) } answers {
            valuesFor(self as Bundle)[firstArg()] = secondArg<String?>()
        }
        every { anyConstructed<Bundle>().getString(any()) } answers {
            valuesFor(self as Bundle)[firstArg()] as? String
        }
        every { anyConstructed<Bundle>().putBoolean(any(), any()) } answers {
            valuesFor(self as Bundle)[firstArg()] = secondArg<Boolean>()
        }
        every { anyConstructed<Bundle>().putInt(any(), any()) } answers {
            valuesFor(self as Bundle)[firstArg()] = secondArg<Int>()
        }
        every { anyConstructed<Bundle>().putLong(any(), any()) } answers {
            valuesFor(self as Bundle)[firstArg()] = secondArg<Long>()
        }
        every { anyConstructed<Bundle>().putFloat(any(), any()) } answers {
            valuesFor(self as Bundle)[firstArg()] = secondArg<Float>()
        }
        every { anyConstructed<Bundle>().putDouble(any(), any()) } answers {
            valuesFor(self as Bundle)[firstArg()] = secondArg<Double>()
        }
    }

    override fun close() {
        values.clear()
        unmockkConstructor(Bundle::class)
    }

    private fun valuesFor(bundle: Bundle): MutableMap<String, Any?> =
        values.getOrPut(bundle) { linkedMapOf() }
}
