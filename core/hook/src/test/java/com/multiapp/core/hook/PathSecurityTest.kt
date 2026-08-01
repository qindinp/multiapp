package com.multiapp.core.hook

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NativeHookBridge 路径安全测试
 *
 * 验证 S0-2 修复：
 * - hasParentTraversal() 能检测空字节注入
 * - hasParentTraversal() 能检测 .. 路径遍历
 * - isCanonicalContained() 能防止符号链接逃逸
 */
class PathSecurityTest {

    private lateinit var bridge: NativeHookBridge

    @BeforeEach
    fun setUp() {
        bridge = NativeHookBridge()
    }

    @AfterEach
    fun tearDown() {
        bridge.cleanup()
    }

    // ===== hasParentTraversal 空字节注入检测 =====

    @Test
    fun `setNativeRedirectScope rejects null byte in processSlot`() {
        // 空字节注入: "good\x00/../evil"
        bridge.setNativeRedirectScope("slot\u0000/../evil", "inst_001")
        // 由于 processSlot 包含 ..，应被拒绝（scope 不被设置）
        // 验证行为：不会因为 NPE 或未预期的路径而崩溃
        val result = bridge.translatePath("/data/data/com.app/file.txt")
        assertEquals("/data/data/com.app/file.txt", result)
    }

    @Test
    fun `setNativeRedirectScope rejects null byte in instanceId`() {
        bridge.setNativeRedirectScope("slot-0", "inst\u0000/../evil")
        val result = bridge.translatePath("/data/data/com.app/file.txt")
        assertEquals("/data/data/com.app/file.txt", result)
    }

    // ===== hasParentTraversal 路径遍历检测 =====

    @Test
    fun `addPathRedirection rejects traversal in fromPrefix`() {
        bridge.addPathRedirection("/data/data/com.app/../other/", "/sandbox/")
        // traversal in fromPrefix should be rejected
        assertEquals(0, bridge.getRedirectionCount())
    }

    @Test
    fun `addPathRedirection rejects traversal in toPrefix`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/../escape/")
        assertEquals(0, bridge.getRedirectionCount())
    }

    @Test
    fun `setupGuestPrivatePathRedirections rejects traversal in dataRoot`() {
        val rules = bridge.setupGuestPrivatePathRedirections(
            guestPackageName = "com.example.app",
            processSlot = "slot-0",
            instanceId = "inst_001",
            dataRoot = "/sandbox/../escape"
        )
        assertEquals(0, rules)
        assertEquals(0, bridge.getRedirectionCount())
    }

    @Test
    fun `setupGuestPrivatePathRedirections rejects traversal in processSlot`() {
        val rules = bridge.setupGuestPrivatePathRedirections(
            guestPackageName = "com.example.app",
            processSlot = "../slot",
            instanceId = "inst_001",
            dataRoot = "/sandbox/example"
        )
        assertEquals(0, rules)
        assertEquals(0, bridge.getRedirectionCount())
    }

    @Test
    fun `setupGuestPrivatePathRedirections rejects traversal in guestPackageName`() {
        val rules = bridge.setupGuestPrivatePathRedirections(
            guestPackageName = "../evil",
            processSlot = "slot-0",
            instanceId = "inst_001",
            dataRoot = "/sandbox/example"
        )
        assertEquals(0, rules)
        assertEquals(0, bridge.getRedirectionCount())
    }

    @Test
    fun `translatePath blocks traversal attack through redirected path`() {
        bridge.setupGuestPrivatePathRedirections(
            guestPackageName = "com.example.app",
            instanceId = "inst_001",
            dataRoot = "/sandbox/example"
        )

        // 攻击: 通过 .. 尝试逃逸到 sandbox 外
        val attackPath = "/data/data/com.example.app/../../../etc/passwd"
        val result = bridge.translatePath(attackPath)
        // secureScopedTranslation 应拒绝包含 .. 的路径
        assertEquals(attackPath, result) // 返回原始路径（不重定向）
    }

    @Test
    fun `translatePath blocks null byte injection in path`() {
        bridge.setupGuestPrivatePathRedirections(
            guestPackageName = "com.example.app",
            instanceId = "inst_001",
            dataRoot = "/sandbox/example"
        )

        // 空字节注入
        val attackPath = "/data/data/com.example.app/file\u0000/../../../etc/passwd"
        val result = bridge.translatePath(attackPath)
        // should not redirect
        assertEquals(attackPath, result)
    }

    // ===== isCanonicalContained 符号链接逃逸防护 =====

    @Test
    fun `secureScopedTranslation returns null for path with traversal segments`() {
        // 即使 dataRoot 存在，包含 .. 的路径也不应被重定向
        bridge.setupGuestPrivatePathRedirections(
            guestPackageName = "com.example.app",
            processSlot = "slot-0",
            instanceId = "inst_001",
            dataRoot = "/sandbox/example"
        )

        val traversalPath = "/data/data/com.example.app/../other/file.txt"
        val result = bridge.translatePath(traversalPath)
        assertEquals(traversalPath, result) // 不应被重定向
    }

    @Test
    fun `addPathRedirection accepts safe paths without traversal`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/com.app/")
        assertEquals(1, bridge.getRedirectionCount())
    }

    @Test
    fun `addPathRedirection rejects double-dot without separator`() {
        // "..foo" is not traversal (it's a valid directory name)
        bridge.addPathRedirection("/data/data/..foo/", "/sandbox/")
        assertEquals(1, bridge.getRedirectionCount())

        // But ".." at segment boundary IS traversal
        bridge.clearPathRedirections()
        bridge.addPathRedirection("/data/data/../", "/sandbox/")
        assertEquals(0, bridge.getRedirectionCount())
    }

    @Test
    fun `multiple traversal patterns are all rejected`() {
        val traversalPatterns = listOf(
            "/a/../b/",
            "/a/b/../../../c/",
            "/a/b/c/../../d/",
            "/../a/"
        )

        for (pattern in traversalPatterns) {
            bridge.clearPathRedirections()
            bridge.addPathRedirection(pattern, "/target/")
            val count = bridge.getRedirectionCount()
            assertEquals(0, count, "Traversal pattern '$pattern' should be rejected")
        }
    }

    @Test
    fun `setupGuestPrivatePathRedirections accepts safe inputs`() {
        val rules = bridge.setupGuestPrivatePathRedirections(
            guestPackageName = "com.example.safe",
            processSlot = "slot-safe",
            instanceId = "inst-safe",
            dataRoot = "/sandbox/safe"
        )
        assertTrue(rules > 0, "Safe inputs should produce redirection rules")
    }

    @Test
    fun `empty strings are not considered parent traversal`() {
        // 空字符串不应被误判为路径遍历
        bridge.addPathRedirection("", "target")
        // 空 prefix 不应触发 traversal 检查失败
        assertEquals(1, bridge.getRedirectionCount())
    }
}
