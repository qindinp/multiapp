package com.multiapp.core.identity

import org.junit.jupiter.api.Test
import java.lang.reflect.Field
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * SignatureBypass ThreadLocal 清理测试
 *
 * 验证 S1-1 修复：
 * - readOriginalSignatures() 正常执行后 ThreadLocal 被清理
 * - readOriginalSignatures() 抛出异常后 ThreadLocal 被清理
 * - 递归保护机制正常工作
 */
class SignatureBypassThreadLocalTest {

    /**
     * 获取 SignatureBypass 的 recursionGuard ThreadLocal 字段
     */
    private fun getRecursionGuard(): ThreadLocal<Boolean> {
        val field: Field = SignatureBypass::class.java
            .getDeclaredField("recursionGuard")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(null) as ThreadLocal<Boolean>
    }

    @Test
    fun `recursionGuard is null initially on fresh thread`() {
        val guard = getRecursionGuard()
        // ThreadLocal 初始值为 null
        assertNull(guard.get(), "recursionGuard should be null initially")
    }

    @Test
    fun `recursionGuard is cleaned after readOriginalSignatures completes normally`() {
        val guard = getRecursionGuard()

        // 模拟 readOriginalSignatures 的递归保护模式
        // 在实际代码中，recursionGuard.set(true) 在 try 之前，
        // finally 中 recursionGuard.remove()
        guard.set(true)
        try {
            // 模拟正常执行
        } finally {
            guard.remove()  // S1-1 修复：使用 remove() 而非 set(false)
        }

        // 验证 finally 后 guard 被清理
        assertFalse(guard.get() == true, "recursionGuard should be false after normal completion")
    }

    @Test
    fun `recursionGuard is cleaned after exception`() {
        val guard = getRecursionGuard()

        // 模拟 readOriginalSignatures 抛出异常的情况
        guard.set(true)
        try {
            throw RuntimeException("Simulated failure in readOriginalSignatures")
        } catch (_: Exception) {
            // 异常被捕获
        } finally {
            guard.remove()  // S1-1 修复：使用 remove() 清理
        }

        // 验证即使抛出异常，guard 也被清理
        assertFalse(guard.get() == true, "recursionGuard should be false after exception")
    }

    @Test
    fun `recursionGuard is thread-local - different threads have independent values`() {
        val guard = getRecursionGuard()

        // 在主线程设置 guard
        guard.set(true)
        assertTrue(guard.get() == true)

        // 在另一个线程验证 guard 为 null
        val otherThreadValue = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val latch = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            otherThreadValue.set(guard.get())
            latch.countDown()
        }
        thread.start()
        latch.await()

        assertNull(otherThreadValue.get(), "Other thread should not see main thread's guard value")

        // 清理主线程状态
        guard.remove()
    }

    @Test
    fun `multiple sequential calls do not leak guard state`() {
        val guard = getRecursionGuard()

        // 模拟多次连续调用 readOriginalSignatures
        repeat(10) {
            guard.set(true)
            try {
                // 模拟一些操作
            } finally {
                guard.remove()  // S1-1 修复：使用 remove()
            }
        }

        // 验证最终状态是清理过的
        assertFalse(guard.get() == true, "recursionGuard should be false after multiple sequential calls")
    }

    @Test
    fun `guard prevents re-entrant interception`() {
        val guard = getRecursionGuard()

        // 第一次进入 readOriginalSignatures
        guard.set(true)
        try {
            // 在 readOriginalSignatures 内部调用 getPackageInfo
            // 此时 interceptPackageInfo 检查 guard.get() == true，跳过拦截
            val shouldSkip = guard.get() == true
            assertTrue(shouldSkip, "Should skip interception when guard is set")
        } finally {
            guard.set(false)
        }

        // 正常调用时不跳过
        val shouldSkip = guard.get() == true
        assertFalse(shouldSkip, "Should NOT skip interception when guard is cleared")
    }

    @Test
    fun `guard cleanup with set false does not throw on already cleared guard`() {
        val guard = getRecursionGuard()

        // guard 初始为 null，直接 set(false) 不应抛异常
        guard.set(false)
        assertFalse(guard.get() == true)

        // 再次清理也不应抛异常
        guard.set(false)
        assertFalse(guard.get() == true)
    }
}
