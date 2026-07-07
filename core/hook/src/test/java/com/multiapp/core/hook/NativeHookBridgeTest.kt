package com.multiapp.core.hook

import com.multiapp.core.model.VirtualConstants
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeHookBridgeTest {

    private lateinit var bridge: NativeHookBridge

    @BeforeEach
    fun setUp() {
        bridge = NativeHookBridge()
    }

    @AfterEach
    fun tearDown() {
        bridge.cleanup()
    }

    // ===== translatePath tests =====

    @Test
    fun `translatePath returns original when no redirections`() {
        val result = bridge.translatePath("/data/data/com.example.app/files/config.json")
        assertEquals("/data/data/com.example.app/files/config.json", result)
    }

    @Test
    fun `translatePath applies longest prefix match`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        bridge.addPathRedirection("/data/data/com.app/cache/", "/sandbox_cache/")

        // Shorter prefix matches normal file
        assertEquals("/sandbox/shared_prefs/prefs.xml", bridge.translatePath("/data/data/com.app/shared_prefs/prefs.xml"))

        // Longer prefix matches cache file (longest prefix wins)
        assertEquals("/sandbox_cache/item.db", bridge.translatePath("/data/data/com.app/cache/item.db"))
    }

    @Test
    fun `translatePath returns dev null for hidden paths`() {
        bridge.hidePath("/system/xbin/su")

        val result = bridge.translatePath("/system/xbin/su")
        assertEquals("/dev/null", result)
    }

    @Test
    fun `translatePath caches results for repeated lookups`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")

        val first = bridge.translatePath("/data/data/com.app/files/data.bin")
        val second = bridge.translatePath("/data/data/com.app/files/data.bin")

        assertEquals(first, second)
        assertEquals("/sandbox/files/data.bin", second)
    }

    @Test
    fun `translatePath invalidates cache when redirections change`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox_old/")
        assertEquals("/sandbox_old/file.txt", bridge.translatePath("/data/data/com.app/file.txt"))

        bridge.addPathRedirection("/data/data/com.app/", "/sandbox_new/")
        assertEquals("/sandbox_new/file.txt", bridge.translatePath("/data/data/com.app/file.txt"))
    }

    @Test
    fun `translatePath returns original for partial prefix match`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")

        // Path shares prefix characters but not a full prefix boundary
        val result = bridge.translatePath("/data/data/com.app_extra/file.txt")
        assertEquals("/data/data/com.app_extra/file.txt", result)
    }

    // ===== addPathRedirection and removePathRedirection tests =====

    @Test
    fun `addPathRedirection increases redirection count`() {
        assertEquals(0, bridge.getRedirectionCount())
        bridge.addPathRedirection("/from/", "/to/")
        assertEquals(1, bridge.getRedirectionCount())
        bridge.addPathRedirection("/from2/", "/to2/")
        assertEquals(2, bridge.getRedirectionCount())
    }

    @Test
    fun `addPathRedirection overwrites existing rule for same prefix`() {
        bridge.addPathRedirection("/data/data/com.app/", "/old_sandbox/")
        bridge.addPathRedirection("/data/data/com.app/", "/new_sandbox/")

        assertEquals(1, bridge.getRedirectionCount())
        assertEquals("/new_sandbox/file.txt", bridge.translatePath("/data/data/com.app/file.txt"))
    }

    @Test
    fun `removePathRedirection decreases redirection count`() {
        bridge.addPathRedirection("/from1/", "/to1/")
        bridge.addPathRedirection("/from2/", "/to2/")
        assertEquals(2, bridge.getRedirectionCount())

        bridge.removePathRedirection("/from1/")
        assertEquals(1, bridge.getRedirectionCount())
    }

    @Test
    fun `removePathRedirection stops translation for removed prefix`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        bridge.removePathRedirection("/data/data/com.app/")

        assertEquals("/data/data/com.app/file.txt", bridge.translatePath("/data/data/com.app/file.txt"))
    }

    @Test
    fun `removePathRedirection is safe for nonexistent prefix`() {
        bridge.removePathRedirection("/does/not/exist/")
        assertEquals(0, bridge.getRedirectionCount())
    }

    // ===== clearPathRedirections tests =====

    @Test
    fun `clearPathRedirections removes all rules`() {
        bridge.addPathRedirection("/from1/", "/to1/")
        bridge.addPathRedirection("/from2/", "/to2/")
        bridge.addPathRedirection("/from3/", "/to3/")
        assertEquals(3, bridge.getRedirectionCount())

        bridge.clearPathRedirections()
        assertEquals(0, bridge.getRedirectionCount())
    }

    @Test
    fun `clearPathRedirections causes translatePath to return original`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        bridge.clearPathRedirections()

        assertEquals("/data/data/com.app/file.txt", bridge.translatePath("/data/data/com.app/file.txt"))
    }

    @Test
    fun `clearPathRedirections is safe when already empty`() {
        bridge.clearPathRedirections()
        assertEquals(0, bridge.getRedirectionCount())
    }

    // ===== setupAppRedirections tests =====

    @Test
    fun `setupAppRedirections creates data directory redirection`() {
        bridge.setupAppRedirections("com.whatsapp", "inst_001", "/sandbox/whatsapp")

        assertEquals(
            "/sandbox/whatsapp/databases/msgstore.db",
            bridge.translatePath("/data/data/com.whatsapp/databases/msgstore.db")
        )
    }

    @Test
    fun `setupAppRedirections creates user-0 directory redirection`() {
        bridge.setupAppRedirections("com.whatsapp", "inst_001", "/sandbox/whatsapp")

        assertEquals(
            "/sandbox/whatsapp/shared_prefs/prefs.xml",
            bridge.translatePath("/data/user/0/com.whatsapp/shared_prefs/prefs.xml")
        )
    }

    @Test
    fun `setupAppRedirections creates external data redirection`() {
        bridge.setupAppRedirections("com.whatsapp", "inst_001", "/sandbox/whatsapp")

        assertEquals(
            "/sandbox/whatsapp/external_data/media/photo.jpg",
            bridge.translatePath("/storage/emulated/0/Android/data/com.whatsapp/media/photo.jpg")
        )
    }

    @Test
    fun `setupAppRedirections creates sdcard Android data redirection`() {
        bridge.setupAppRedirections("com.whatsapp", "inst_001", "/sandbox/whatsapp")

        assertEquals(
            "/sandbox/whatsapp/external_data/file.txt",
            bridge.translatePath("/sdcard/Android/data/com.whatsapp/file.txt")
        )
    }

    @Test
    fun `setupAppRedirections creates obb redirection`() {
        bridge.setupAppRedirections("com.whatsapp", "inst_001", "/sandbox/whatsapp")

        assertEquals(
            "/sandbox/whatsapp/obb/main.obb",
            bridge.translatePath("/storage/emulated/0/Android/obb/com.whatsapp/main.obb")
        )
    }

    @Test
    fun `setupAppRedirections creates five redirection rules`() {
        bridge.setupAppRedirections("com.example.app", "inst_001", "/sandbox/app")
        assertEquals(5, bridge.getRedirectionCount())
    }

    @Test
    fun `setupAppRedirections does not affect other packages`() {
        bridge.setupAppRedirections("com.whatsapp", "inst_001", "/sandbox/whatsapp")

        val telegramPath = "/data/data/com.telegram/files/data.db"
        assertEquals(telegramPath, bridge.translatePath(telegramPath))
    }

    // ===== setupGuestPrivatePathRedirections tests =====

    @Test
    fun `setupGuestPrivatePathRedirections redirects data data path`() {
        bridge.setupGuestPrivatePathRedirections("com.example.app", "inst_001", "/sandbox/example")

        assertEquals(
            "/sandbox/example/files/config.json",
            bridge.translatePath("/data/data/com.example.app/files/config.json")
        )
    }

    @Test
    fun `setupGuestPrivatePathRedirections redirects data user zero path`() {
        bridge.setupGuestPrivatePathRedirections("com.example.app", "inst_001", "/sandbox/example")

        assertEquals(
            "/sandbox/example/shared_prefs/settings.xml",
            bridge.translatePath("/data/user/0/com.example.app/shared_prefs/settings.xml")
        )
    }

    @Test
    fun `setupGuestPrivatePathRedirections creates only private path rules`() {
        val ruleCount = bridge.setupGuestPrivatePathRedirections(
            "com.example.app",
            "inst_001",
            "/sandbox/example"
        )

        assertEquals(2, ruleCount)
        assertEquals(2, bridge.getRedirectionCount())
    }

    @Test
    fun `setupGuestPrivatePathRedirections leaves external and obb paths unchanged`() {
        bridge.setupGuestPrivatePathRedirections("com.example.app", "inst_001", "/sandbox/example")

        val externalStorage = "/storage/emulated/0/Android/data/com.example.app/files/photo.jpg"
        val sdcard = "/sdcard/Android/data/com.example.app/files/photo.jpg"
        val obb = "/storage/emulated/0/Android/obb/com.example.app/main.obb"

        assertEquals(externalStorage, bridge.translatePath(externalStorage))
        assertEquals(sdcard, bridge.translatePath(sdcard))
        assertEquals(obb, bridge.translatePath(obb))
    }

    @Test
    fun `setupGuestPrivatePathRedirections leaves proc paths and spoof content untouched`() {
        bridge.setupGuestPrivatePathRedirections("com.example.app", "inst_001", "/sandbox/example")

        assertEquals("/proc/self/maps", bridge.translatePath("/proc/self/maps"))
        assertFalse(bridge.hasFakeContent("/proc/self/maps"))
        assertFalse(bridge.hasFakeContent("/proc/self/cmdline"))
        assertFalse(bridge.hasFakeContent("/proc/self/status"))
        assertFalse(bridge.isPathHidden("/proc/self/maps"))
    }

    @Test
    fun `setupGuestPrivatePathRedirections normalizes target trailing slash`() {
        bridge.setupGuestPrivatePathRedirections("com.example.app", "inst_001", "/sandbox/example/")

        assertEquals(
            "/sandbox/example/files/config.json",
            bridge.translatePath("/data/data/com.example.app/files/config.json")
        )
    }

    @Test
    fun `setupGuestPrivatePathRedirections ignores incomplete input`() {
        val blankPackageRules = bridge.setupGuestPrivatePathRedirections("", "inst_001", "/sandbox/example")
        val blankRootRules = bridge.setupGuestPrivatePathRedirections("com.example.app", "inst_001", "")

        assertEquals(0, blankPackageRules)
        assertEquals(0, blankRootRules)
        assertEquals(0, bridge.getRedirectionCount())
        assertEquals(
            "/data/data/com.example.app/files/config.json",
            bridge.translatePath("/data/data/com.example.app/files/config.json")
        )
    }

    @Test
    fun `setupGuestPrivatePathRedirections binds same package rules to processSlot and instanceId`() {
        bridge.setupGuestPrivatePathRedirections(
            guestPackageName = "com.example.app",
            processSlot = "slot-0",
            instanceId = "inst_001",
            dataRoot = "/sandbox/inst_001"
        )
        bridge.setupGuestPrivatePathRedirections(
            guestPackageName = "com.example.app",
            processSlot = "slot-1",
            instanceId = "inst_002",
            dataRoot = "/sandbox/inst_002"
        )

        bridge.setNativeRedirectScope("slot-0", "inst_001")
        assertEquals(
            "/sandbox/inst_001/files/config.json",
            bridge.translatePath("/data/data/com.example.app/files/config.json")
        )

        bridge.setNativeRedirectScope("slot-1", "inst_002")
        assertEquals(
            "/sandbox/inst_002/files/config.json",
            bridge.translatePath("/data/data/com.example.app/files/config.json")
        )

        val evidence = bridge.getPathRedirectionEvidence()
        assertTrue(evidence.any { it.processSlot == "slot-0" && it.instanceId == "inst_001" && it.scoped })
        assertTrue(evidence.any { it.processSlot == "slot-1" && it.instanceId == "inst_002" && it.scoped })
    }

    @Test
    fun `setupGuestPrivatePathRedirections rejects parent traversal inputs`() {
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
    fun `translatePath rejects parent traversal under scoped prefix`() {
        bridge.setupGuestPrivatePathRedirections(
            guestPackageName = "com.example.app",
            processSlot = "slot-0",
            instanceId = "inst_001",
            dataRoot = "/sandbox/inst_001"
        )

        val unsafePath = "/data/data/com.example.app/../com.other.app/files/config.json"
        assertEquals(unsafePath, bridge.translatePath(unsafePath))
    }

    @Test
    fun `translatePath allows existing target when canonical path stays under dataRoot`() {
        val dataRoot = Files.createTempDirectory("multiapp-data-root").toFile()
        val existingFile = dataRoot.resolve("files/config.json")
        requireNotNull(existingFile.parentFile).mkdirs()
        existingFile.writeText("ok")

        bridge.setupGuestPrivatePathRedirections(
            guestPackageName = "com.example.app",
            processSlot = "slot-0",
            instanceId = "inst_001",
            dataRoot = dataRoot.absolutePath
        )

        assertEquals(
            existingFile.canonicalPath,
            java.io.File(bridge.translatePath("/data/data/com.example.app/files/config.json")).canonicalPath
        )
    }

    // ===== setupExternalStorageRedirections tests =====

    @Test
    fun `setupExternalStorageRedirections creates sdcard redirection`() {
        bridge.setupExternalStorageRedirections("inst_001", "/sandbox/sdcard")

        assertEquals(
            "/sandbox/sdcard/DCIM/photo.jpg",
            bridge.translatePath("/sdcard/DCIM/photo.jpg")
        )
    }

    @Test
    fun `setupExternalStorageRedirections creates storage emulated redirection`() {
        bridge.setupExternalStorageRedirections("inst_001", "/sandbox/sdcard")

        assertEquals(
            "/sandbox/sdcard/Download/file.pdf",
            bridge.translatePath("/storage/emulated/0/Download/file.pdf")
        )
    }

    @Test
    fun `setupExternalStorageRedirections creates mnt sdcard redirection`() {
        bridge.setupExternalStorageRedirections("inst_001", "/sandbox/sdcard")

        assertEquals(
            "/sandbox/sdcard/Music/song.mp3",
            bridge.translatePath("/mnt/sdcard/Music/song.mp3")
        )
    }

    @Test
    fun `setupExternalStorageRedirections creates storage self primary redirection`() {
        bridge.setupExternalStorageRedirections("inst_001", "/sandbox/sdcard")

        assertEquals(
            "/sandbox/sdcard/DCIM/video.mp4",
            bridge.translatePath("/storage/self/primary/DCIM/video.mp4")
        )
    }

    @Test
    fun `setupExternalStorageRedirections creates root without trailing slash`() {
        bridge.setupExternalStorageRedirections("inst_001", "/sandbox/sdcard")

        assertEquals("/sandbox/sdcard", bridge.translatePath("/storage/emulated/0"))
        assertEquals("/sandbox/sdcard", bridge.translatePath("/sdcard"))
    }

    @Test
    fun `setupExternalStorageRedirections creates six redirection rules`() {
        bridge.setupExternalStorageRedirections("inst_001", "/sandbox/sdcard")
        assertEquals(6, bridge.getRedirectionCount())
    }

    // ===== spoofProcSelf tests =====

    @Test
    fun `spoofProcSelf sets fake cmdline content`() {
        bridge.spoofProcSelf(12345, "com.whatsapp")

        val cmdline = bridge.getFakeContent("/proc/self/cmdline")
        assertTrue(cmdline != null)
        assertEquals("com.whatsapp", String(cmdline!!).trimEnd('\u0000'))
    }

    @Test
    fun `spoofProcSelf cmdline ends with null byte`() {
        bridge.spoofProcSelf(12345, "com.whatsapp")

        val cmdline = bridge.getFakeContent("/proc/self/cmdline")!!
        assertEquals(0.toByte(), cmdline.last())
    }

    @Test
    fun `spoofProcSelf sets fake comm content truncated to 15 chars`() {
        bridge.spoofProcSelf(12345, "com.example.verylong.package")

        val comm = bridge.getFakeContent("/proc/self/comm")
        assertTrue(comm != null)
        // Linux comm is limited to 15 chars
        assertEquals("com.example.ver\n", String(comm!!))
    }

    @Test
    fun `spoofProcSelf sets fake status content with correct pid`() {
        bridge.spoofProcSelf(54321, "com.test.app")

        val status = bridge.getFakeContent("/proc/self/status")
        assertTrue(status != null)
        val statusText = String(status!!)
        assertContains(statusText, "Pid:\t54321")
        assertContains(statusText, "PPid:\t54320")
        assertContains(statusText, "Name:\tcom.test.app")
    }

    @Test
    fun `spoofProcSelf sets comm equal to packageName when under 15 chars`() {
        bridge.spoofProcSelf(1000, "com.app")

        val comm = bridge.getFakeContent("/proc/self/comm")
        assertTrue(comm != null)
        assertEquals("com.app\n", String(comm!!))
    }

    @Test
    fun `spoofProcSelf sets comm exactly 15 chars for long package names`() {
        bridge.spoofProcSelf(1000, "com.whatsapp.messenger")

        val comm = bridge.getFakeContent("/proc/self/comm")
        assertTrue(comm != null)
        // "com.whatsapp.me" is exactly 15 chars
        val commText = String(comm!!)
        assertTrue(commText.startsWith("com.whatsapp.me"))
    }

    @Test
    fun `spoofProcSelf hasFakeContent returns true for spoofed paths`() {
        bridge.spoofProcSelf(12345, "com.test.app")

        assertTrue(bridge.hasFakeContent("/proc/self/cmdline"))
        assertTrue(bridge.hasFakeContent("/proc/self/comm"))
        assertTrue(bridge.hasFakeContent("/proc/self/status"))
    }

    // ===== filterProcMaps tests =====

    @Test
    fun `filterProcMaps hides lines containing multiapp`() {
        val maps = """
            7f000000-7f100000 r-xp 00000000 08:01 12345 /system/lib64/libc.so
            7f200000-7f300000 r-xp 00000000 08:01 12346 /data/app/com.multiapp.app/lib/arm64/libmultiapp.so
            7f400000-7f500000 r-xp 00000000 08:01 12347 /system/lib64/libm.so
        """.trimIndent()

        val filtered = bridge.filterProcMaps(maps)
        assertContains(filtered, "libc.so")
        assertContains(filtered, "libm.so")
        assertFalse(filtered.contains("multiapp"))
    }

    @Test
    fun `filterProcMaps hides lines containing libmultiapp`() {
        val maps = "7f200000-7f300000 r-xp 00000000 08:01 12346 /data/local/tmp/libmultiapp.so"

        val filtered = bridge.filterProcMaps(maps)
        assertFalse(filtered.contains("libmultiapp"))
    }

    @Test
    fun `filterProcMaps hides lines containing shadowhook`() {
        val maps = "7f200000-7f300000 r-xp 00000000 08:01 12346 /data/local/tmp/libshadowhook.so"

        val filtered = bridge.filterProcMaps(maps)
        assertFalse(filtered.contains("shadowhook"))
    }

    @Test
    fun `filterProcMaps hides lines containing lsplant`() {
        val maps = "7f200000-7f300000 r-xp 00000000 08:01 12346 /data/local/tmp/liblsplant.so"

        val filtered = bridge.filterProcMaps(maps)
        assertFalse(filtered.contains("lsplant"))
    }

    @Test
    fun `filterProcMaps hides lines containing xposed`() {
        val maps = "7f200000-7f300000 r-xp 00000000 08:01 12346 /system/framework/XposedBridge.jar"

        val filtered = bridge.filterProcMaps(maps)
        assertFalse(filtered.contains("xposed"))
    }

    @Test
    fun `filterProcMaps hides host package entries`() {
        val maps = "7f200000-7f300000 r-xp 00000000 08:01 12346 /data/app/${VirtualConstants.HOST_PACKAGE}/base.apk"

        val filtered = bridge.filterProcMaps(maps)
        assertFalse(filtered.contains(VirtualConstants.HOST_PACKAGE))
    }

    @Test
    fun `filterProcMaps preserves unrelated entries`() {
        val maps = """
            7f000000-7f100000 r-xp 00000000 08:01 12345 /system/lib64/libc.so
            7f100000-7f200000 r--p 00100000 08:01 12345 /system/lib64/libc.so
            7f300000-7f400000 r-xp 00000000 08:01 12347 /system/lib64/libm.so
            7f500000-7f600000 r-xp 00000000 08:01 12348 /data/app/com.whatsapp/base.apk
        """.trimIndent()

        val filtered = bridge.filterProcMaps(maps)
        assertContains(filtered, "libc.so")
        assertContains(filtered, "libm.so")
        assertContains(filtered, "com.whatsapp/base.apk")
    }

    @Test
    fun `filterProcMaps handles empty input`() {
        val filtered = bridge.filterProcMaps("")
        assertEquals("", filtered)
    }

    @Test
    fun `filterProcMaps is case insensitive`() {
        val maps = """
            7f000000-7f100000 r-xp 00000000 08:01 12345 /system/lib64/libc.so
            7f200000-7f300000 r-xp 00000000 08:01 12346 /data/local/tmp/MULTIAPP_helper.so
        """.trimIndent()

        val filtered = bridge.filterProcMaps(maps)
        assertContains(filtered, "libc.so")
        assertFalse(filtered.contains("MULTIAPP"))
    }

    @Test
    fun `filterProcMaps hides all hook framework entries`() {
        val maps = """
            line-with-shadowhook
            line-with-lsplant
            line-with-dobby
            line-with-bhook
            line-with-xhook
            line-with-substrate
            line-with-xposed
            clean-line-libc
        """.trimIndent()

        val filtered = bridge.filterProcMaps(maps)
        assertFalse(filtered.contains("shadowhook"))
        assertFalse(filtered.contains("lsplant"))
        assertFalse(filtered.contains("dobby"))
        assertFalse(filtered.contains("bhook"))
        assertFalse(filtered.contains("xhook"))
        assertFalse(filtered.contains("substrate"))
        assertFalse(filtered.contains("xposed"))
        assertContains(filtered, "clean-line-libc")
    }

    // ===== hidePath and unhidePath tests =====

    @Test
    fun `hidePath makes path hidden`() {
        assertFalse(bridge.isPathHidden("/system/xbin/su"))
        bridge.hidePath("/system/xbin/su")
        assertTrue(bridge.isPathHidden("/system/xbin/su"))
    }

    @Test
    fun `unhidePath reverses hidePath`() {
        bridge.hidePath("/system/xbin/su")
        assertTrue(bridge.isPathHidden("/system/xbin/su"))

        bridge.unhidePath("/system/xbin/su")
        assertFalse(bridge.isPathHidden("/system/xbin/su"))
    }

    @Test
    fun `hidePath increases hidden path count`() {
        assertEquals(0, bridge.getHiddenPathCount())
        bridge.hidePath("/path1")
        assertEquals(1, bridge.getHiddenPathCount())
        bridge.hidePath("/path2")
        assertEquals(2, bridge.getHiddenPathCount())
    }

    @Test
    fun `unhidePath decreases hidden path count`() {
        bridge.hidePath("/path1")
        bridge.hidePath("/path2")
        assertEquals(2, bridge.getHiddenPathCount())

        bridge.unhidePath("/path1")
        assertEquals(1, bridge.getHiddenPathCount())
    }

    @Test
    fun `hidePath is idempotent`() {
        bridge.hidePath("/system/xbin/su")
        bridge.hidePath("/system/xbin/su")
        assertEquals(1, bridge.getHiddenPathCount())
    }

    @Test
    fun `unhidePath is safe for nonexistent path`() {
        bridge.unhidePath("/does/not/exist")
        assertFalse(bridge.isPathHidden("/does/not/exist"))
    }

    // ===== setFakeFileContent and getFakeContent tests =====

    @Test
    fun `setFakeFileContent with ByteArray stores content`() {
        val content = byteArrayOf(0x01, 0x02, 0x03)
        bridge.setFakeFileContent("/proc/self/maps", content)

        assertTrue(bridge.hasFakeContent("/proc/self/maps"))
        assertEquals(content.toList(), bridge.getFakeContent("/proc/self/maps")!!.toList())
    }

    @Test
    fun `setFakeFileContent with String stores content as bytes`() {
        bridge.setFakeFileContent("/proc/version", "Linux version 5.4.0")

        assertTrue(bridge.hasFakeContent("/proc/version"))
        assertEquals("Linux version 5.4.0", String(bridge.getFakeContent("/proc/version")!!))
    }

    @Test
    fun `getFakeContent returns null for unregistered path`() {
        assertNull(bridge.getFakeContent("/some/random/path"))
    }

    @Test
    fun `hasFakeContent returns false for unregistered path`() {
        assertFalse(bridge.hasFakeContent("/some/random/path"))
    }

    @Test
    fun `setFakeFileContent overwrites previous content`() {
        bridge.setFakeFileContent("/proc/version", "old version")
        bridge.setFakeFileContent("/proc/version", "new version")

        assertEquals("new version", String(bridge.getFakeContent("/proc/version")!!))
    }

    // ===== spoofSystemProperty and getPropertyOverride tests =====

    @Test
    fun `spoofSystemProperty stores override`() {
        bridge.spoofSystemProperty("ro.product.model", "Pixel 7")
        assertEquals("Pixel 7", bridge.getPropertyOverride("ro.product.model"))
    }

    @Test
    fun `spoofSystemProperty overwrites previous value`() {
        bridge.spoofSystemProperty("ro.product.model", "Galaxy S23")
        bridge.spoofSystemProperty("ro.product.model", "Pixel 7")

        assertEquals("Pixel 7", bridge.getPropertyOverride("ro.product.model"))
    }

    @Test
    fun `getPropertyOverride returns null for unknown key`() {
        assertNull(bridge.getPropertyOverride("ro.unknown.property"))
    }

    @Test
    fun `spoofSystemProperties stores multiple overrides`() {
        val properties = mapOf(
            "ro.product.model" to "Pixel 7",
            "ro.product.brand" to "Google",
            "ro.hardware.chipname" to "tensor"
        )
        bridge.spoofSystemProperties(properties)

        assertEquals("Pixel 7", bridge.getPropertyOverride("ro.product.model"))
        assertEquals("Google", bridge.getPropertyOverride("ro.product.brand"))
        assertEquals("tensor", bridge.getPropertyOverride("ro.hardware.chipname"))
    }

    // ===== cleanup tests =====

    @Test
    fun `cleanup clears all state`() {
        bridge.addPathRedirection("/from/", "/to/")
        bridge.hidePath("/hidden")
        bridge.setFakeFileContent("/fake", "content")
        bridge.spoofSystemProperty("key", "value")

        bridge.cleanup()

        assertEquals(0, bridge.getRedirectionCount())
        assertEquals(0, bridge.getHiddenPathCount())
        assertFalse(bridge.hasFakeContent("/fake"))
        assertNull(bridge.getPropertyOverride("key"))
    }

    @Test
    fun `cleanup is safe when called on fresh instance`() {
        bridge.cleanup()
        assertEquals(0, bridge.getRedirectionCount())
    }

    // ===== removeAppRedirections tests =====

    @Test
    fun `removeAppRedirections removes all rules for given package`() {
        bridge.setupAppRedirections("com.whatsapp", "inst_001", "/sandbox/whatsapp")
        assertEquals(5, bridge.getRedirectionCount())

        bridge.removeAppRedirections("com.whatsapp")
        assertEquals(0, bridge.getRedirectionCount())
    }

    @Test
    fun `removeAppRedirections only removes targeted package`() {
        bridge.setupAppRedirections("com.whatsapp", "inst_001", "/sandbox/wa")
        bridge.setupAppRedirections("com.telegram", "inst_002", "/sandbox/tg")
        assertEquals(10, bridge.getRedirectionCount())

        bridge.removeAppRedirections("com.whatsapp")
        assertEquals(5, bridge.getRedirectionCount())

        // Telegram redirections still work
        assertEquals("/sandbox/tg/databases/db", bridge.translatePath("/data/data/com.telegram/databases/db"))
    }

    // ===== removeExternalStorageRedirections tests =====

    @Test
    fun `removeExternalStorageRedirections removes sdcard rules`() {
        bridge.setupExternalStorageRedirections("inst_001", "/sandbox/sdcard")
        assertEquals(6, bridge.getRedirectionCount())

        bridge.removeExternalStorageRedirections()
        assertEquals(0, bridge.getRedirectionCount())
    }

    // ===== isNativeAvailable tests =====

    @Test
    fun `isNativeAvailable returns false in unit test environment`() {
        // Native library is not available in JVM unit tests
        assertFalse(bridge.isNativeAvailable())
    }

    @Test
    fun `initNativePathRedirectHooks does not seed anti detection hidden paths`() {
        val result = bridge.initNativePathRedirectHooks(NativeHookPolicy.baseline())

        assertFalse(result)
        assertFalse(bridge.isPathHidden("/system/xbin/su"))
        assertFalse(bridge.isPathHidden("/dev/socket/qemud"))
        assertFalse(bridge.hasFakeContent("/proc/self/status"))
        assertFalse(bridge.hasFakeContent("/proc/self/cmdline"))
    }

    @Test
    fun `initNativePathRedirectHooks respects disabled path virtualization policy`() {
        val result = bridge.initNativePathRedirectHooks(NativeHookPolicy.off())

        assertFalse(result)
        assertFalse(bridge.isPathHidden("/system/xbin/su"))
    }

    // ===== edge case tests =====

    @Test
    fun `translatePath handles empty string`() {
        val result = bridge.translatePath("")
        assertEquals("", result)
    }

    @Test
    fun `translatePath handles root path`() {
        val result = bridge.translatePath("/")
        assertEquals("/", result)
    }

    @Test
    fun `addPathRedirection handles empty strings`() {
        bridge.addPathRedirection("", "redirected")
        // Empty prefix has length 0, trie doesn't match it (root node not checked in translate loop)
        assertEquals("anything", bridge.translatePath("anything"))
    }

    @Test
    fun `hidden path takes precedence over redirection`() {
        bridge.addPathRedirection("/data/data/com.app/", "/sandbox/")
        bridge.hidePath("/data/data/com.app/secret.dat")

        // Hidden path returns /dev/null even though a redirection rule matches
        assertEquals("/dev/null", bridge.translatePath("/data/data/com.app/secret.dat"))

        // Non-hidden path still uses redirection
        assertEquals("/sandbox/file.txt", bridge.translatePath("/data/data/com.app/file.txt"))
    }

    @Test
    fun `multiple redirections work independently`() {
        bridge.addPathRedirection("/data/data/com.a/", "/sandbox_a/")
        bridge.addPathRedirection("/data/data/com.b/", "/sandbox_b/")

        assertEquals("/sandbox_a/file.txt", bridge.translatePath("/data/data/com.a/file.txt"))
        assertEquals("/sandbox_b/file.txt", bridge.translatePath("/data/data/com.b/file.txt"))
        assertEquals("/data/data/com.c/file.txt", bridge.translatePath("/data/data/com.c/file.txt"))
    }

    @Test
    fun `filterProcMaps handles single line with no match`() {
        val maps = "7f000000-7f100000 r-xp 00000000 08:01 12345 /system/lib64/libc.so"
        val filtered = bridge.filterProcMaps(maps)
        assertContains(filtered, "libc.so")
    }

    @Test
    fun `filterProcMaps handles all lines matching filter`() {
        val maps = """
            /data/app/com.multiapp.app/base.apk
            /data/local/tmp/libmultiapp.so
        """.trimIndent()

        val filtered = bridge.filterProcMaps(maps)
        // All lines filtered, result should be effectively empty (just newlines)
        assertFalse(filtered.contains("multiapp"))
    }

    // ===== parseRegisterNativesEvidenceReport tests =====

    @Test
    fun `parse null report returns null`() {
        assertNull(NativeHookBridge.parseRegisterNativesEvidenceReport(null))
    }

    @Test
    fun `parse empty report returns null`() {
        assertNull(NativeHookBridge.parseRegisterNativesEvidenceReport(""))
    }

    @Test
    fun `parse report missing required fields returns null`() {
        assertNull(NativeHookBridge.parseRegisterNativesEvidenceReport("className=com.stub.StubApp"))
        assertNull(NativeHookBridge.parseRegisterNativesEvidenceReport("methodCount=10;result=0"))
    }

    @Test
    fun `parse basic report returns structured evidence`() {
        val report = "className=com.stub.StubApp\nmethodCount=10\nresult=0"
        val evidence = NativeHookBridge.parseRegisterNativesEvidenceReport(report)
        assertEquals("com.stub.StubApp", evidence?.className)
        assertEquals(10, evidence?.methodCount)
        assertEquals(0, evidence?.result)
    }

    @Test
    fun `parse report with semicolon separator`() {
        val report = "className=com.stub.StubApp;methodCount=15;result=0;source=native"
        val evidence = NativeHookBridge.parseRegisterNativesEvidenceReport(report)
        assertEquals("com.stub.StubApp", evidence?.className)
        assertEquals(15, evidence?.methodCount)
        assertEquals("native", evidence?.source)
    }

    @Test
    fun `parse boolean fields with 1_0`() {
        val report = "className=c\nmethodCount=10\nresult=0\ncallerIsJiagu=1\nallMultiAppMethods=0\nhasInterface11=1\nhasInterface20=1"
        val evidence = NativeHookBridge.parseRegisterNativesEvidenceReport(report)
        assertTrue(evidence?.callerIsJiagu == true)
        assertTrue(evidence?.allMultiAppMethods == false)
        assertTrue(evidence?.hasInterface11 == true)
        assertTrue(evidence?.hasInterface20 == true)
    }

    @Test
    fun `parse boolean fields with true_false case insensitive`() {
        val report = "className=c\nmethodCount=10\nresult=0\ncallerIsJiagu=True\nallMultiAppMethods=FALSE"
        val evidence = NativeHookBridge.parseRegisterNativesEvidenceReport(report)
        assertTrue(evidence?.callerIsJiagu == true)
        assertTrue(evidence?.allMultiAppMethods == false)
    }

    @Test
    fun `parse report with explicit originalShellPath override`() {
        val report = "className=c\nmethodCount=10\nresult=0\noriginalShellPath=1"
        val evidence = NativeHookBridge.parseRegisterNativesEvidenceReport(report)
        assertTrue(evidence?.originalShellPath == true)
    }

    @Test
    fun `parse computes originalShellPath when field absent`() {
        val report = "className=c\nmethodCount=10\nresult=0\ncallerIsJiagu=1\nhasInterface11=1\nhasInterface20=1"
        val evidence = NativeHookBridge.parseRegisterNativesEvidenceReport(report)
        assertTrue(evidence?.originalShellPath == true)
    }

    @Test
    fun `parse treats allMultiAppMethods as non original`() {
        val report = "className=c\nmethodCount=10\nresult=0\ncallerIsJiagu=1\nallMultiAppMethods=1\nhasInterface11=1\nhasInterface20=1"
        val evidence = NativeHookBridge.parseRegisterNativesEvidenceReport(report)
        assertFalse(evidence?.originalShellPath == true)
    }

    @Test
    fun `parse native unavailable report returns null`() {
        assertNull(NativeHookBridge.parseRegisterNativesEvidenceReport("native-lib-not-loaded"))
    }

    @Test
    fun `parse report with qihoo StubApp class`() {
        val report = "className=com.qihoo.util.StubApp\nmethodCount=20\nresult=0"
        val evidence = NativeHookBridge.parseRegisterNativesEvidenceReport(report)
        assertEquals("com.qihoo.util.StubApp", evidence?.className)
        assertEquals(20, evidence?.methodCount)
    }

    @Test
    fun `parse ignores unknown keys`() {
        val report = "className=c\nmethodCount=10\nresult=0\nunknownKey=ignored"
        val evidence = NativeHookBridge.parseRegisterNativesEvidenceReport(report)
        assertEquals("c", evidence?.className)
    }
}
