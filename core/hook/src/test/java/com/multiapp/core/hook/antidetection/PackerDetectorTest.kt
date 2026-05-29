package com.multiapp.core.hook.antidetection

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import timber.log.Timber
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals

class PackerDetectorTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        mockkObject(Timber.Forest)
        val mockTree = mockk<Timber.Tree>(relaxed = true)
        every { Timber.Forest.tag(any()) } returns mockTree
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // =========================================================================
    // Helper: create a minimal zip with the given entry names (empty content)
    // =========================================================================

    private fun createZip(vararg entries: String): Path {
        val zipPath = tempDir.resolve("test_${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(zipPath.toFile())).use { zos ->
            for (name in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.closeEntry()
            }
        }
        return zipPath
    }

    /**
     * Create a zip with one entry whose bytes are [content] encoded as ISO-8859-1.
     * Used for DEX class-pattern and manifest-pattern tests.
     */
    private fun createZipWithContent(entryName: String, content: String): Path {
        val zipPath = tempDir.resolve("test_${System.nanoTime()}.zip")
        val bytes = content.toByteArray(Charsets.ISO_8859_1)
        ZipOutputStream(FileOutputStream(zipPath.toFile())).use { zos ->
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(bytes)
            zos.closeEntry()
        }
        return zipPath
    }

    // =========================================================================
    // 1. No packer detected -> "unknown"
    // =========================================================================

    @Nested
    inner class NoPackerDetected {

        @Test
        fun `empty zip with no entries returns unknown`() {
            val apkPath = createZip().toString()
            assertEquals("unknown", PackerDetector.detect(apkPath))
        }

        @Test
        fun `zip with only assets returns unknown`() {
            val apkPath = createZip("assets/config.json", "res/layout/main.xml").toString()
            assertEquals("unknown", PackerDetector.detect(apkPath))
        }

        @Test
        fun `zip with non-matching native libs returns unknown`() {
            val apkPath = createZip(
                "lib/arm64-v8a/libapp.so",
                "lib/arm64-v8a/libflutter.so"
            ).toString()
            assertEquals("unknown", PackerDetector.detect(apkPath))
        }
    }

    // =========================================================================
    // 2. Empty / null APK path handling
    // =========================================================================

    @Nested
    inner class InvalidPathHandling {

        @Test
        fun `empty string path returns unknown`() {
            assertEquals("unknown", PackerDetector.detect(""))
        }

        @Test
        fun `non-existent path returns unknown`() {
            assertEquals("unknown", PackerDetector.detect("/no/such/file.apk"))
        }

        @Test
        fun `path with special characters returns unknown when file missing`() {
            assertEquals("unknown", PackerDetector.detect("/path/with spaces/and 特殊字符.apk"))
        }
    }

    // =========================================================================
    // 3. Corrupt / unreadable APK file
    // =========================================================================

    @Nested
    inner class CorruptApk {

        @Test
        fun `corrupt zip file returns unknown instead of throwing`() {
            val corruptPath = tempDir.resolve("corrupt.apk")
            corruptPath.toFile().writeBytes(byteArrayOf(0x00, 0x01, 0x02))
            assertEquals("unknown", PackerDetector.detect(corruptPath.toString()))
        }

        @Test
        fun `empty file returns unknown`() {
            val emptyPath = tempDir.resolve("empty.apk")
            emptyPath.toFile().createNewFile()
            assertEquals("unknown", PackerDetector.detect(emptyPath.toString()))
        }
    }

    // =========================================================================
    // 4. Native library detection
    // =========================================================================

    @Nested
    inner class NativeLibDetection {

        @Test
        fun `libjiagu dot so detected as 360 Jiagu`() {
            val apkPath = createZip("lib/arm64-v8a/libjiagu.so").toString()
            assertEquals("360 Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `libjiagu64 dot so detected as 360 Jiagu`() {
            val apkPath = createZip("lib/arm64-v8a/libjiagu64.so").toString()
            assertEquals("360 Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `libjiagu_x86 dot so detected as 360 Jiagu`() {
            val apkPath = createZip("lib/x86/libjiagu_x86.so").toString()
            assertEquals("360 Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `libshellx-super dot 2019 dot so detected as Tencent Jiagu`() {
            val apkPath = createZip("lib/arm64-v8a/libshellx-super.2019.so").toString()
            assertEquals("Tencent Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `libshella-release dot so detected as Tencent Jiagu`() {
            val apkPath = createZip("lib/armeabi-v7a/libshella-release.so").toString()
            assertEquals("Tencent Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `libshellx-super dot so detected as Tencent Jiagu`() {
            val apkPath = createZip("lib/arm64-v8a/libshellx-super.2019.so").toString()
            assertEquals("Tencent Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `libexec dot so detected as iJiami`() {
            val apkPath = createZip("lib/arm64-v8a/libexec.so").toString()
            assertEquals("iJiami", PackerDetector.detect(apkPath))
        }

        @Test
        fun `libsgmain dot so detected as Alibaba`() {
            val apkPath = createZip("lib/arm64-v8a/libsgmain.so").toString()
            assertEquals("Alibaba", PackerDetector.detect(apkPath))
        }

        @Test
        fun `libsgsecuritybody dot so detected as Alibaba`() {
            val apkPath = createZip("lib/armeabi-v7a/libsgsecuritybody.so").toString()
            assertEquals("Alibaba", PackerDetector.detect(apkPath))
        }

        @Test
        fun `native lib inside deep nested path is still detected`() {
            val apkPath = createZip("lib/armeabi-v7a/sub/dir/libjiagu.so").toString()
            // File(it.name).name extracts only the filename segment
            assertEquals("360 Jiagu", PackerDetector.detect(apkPath))
        }
    }

    // =========================================================================
    // 5. DEX class name detection (via binary content search)
    // =========================================================================

    @Nested
    inner class DexClassDetection {

        @Test
        fun `com_qihoo_util pattern in DEX detected as 360 Jiagu`() {
            val apkPath = createZipWithContent(
                "classes.dex",
                "Lcom/qihoo/util/StubApp;"
            ).toString()
            assertEquals("360 Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `com_stub_StubApp pattern in DEX detected as 360 Jiagu`() {
            val apkPath = createZipWithContent(
                "classes.dex",
                "com/stub/StubApp"
            ).toString()
            assertEquals("360 Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `com_tencent_StubShell pattern in DEX detected as Tencent Jiagu`() {
            val apkPath = createZipWithContent(
                "classes.dex",
                "Lcom/tencent/StubShell/RunTimeInfo;"
            ).toString()
            assertEquals("Tencent Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `com_shell_SuperApplication pattern in DEX detected as iJiami`() {
            val apkPath = createZipWithContent(
                "classes.dex",
                "Lcom/shell/SuperApplication;"
            ).toString()
            assertEquals("iJiami", PackerDetector.detect(apkPath))
        }

        @Test
        fun `com_ijiami_armc pattern in DEX detected as iJiami`() {
            val apkPath = createZipWithContent(
                "classes.dex",
                "Lcom/ijiami/armc/ArmChannel;"
            ).toString()
            assertEquals("iJiami", PackerDetector.detect(apkPath))
        }

        @Test
        fun `com_secnium pattern in DEX detected as Bangcle`() {
            val apkPath = createZipWithContent(
                "classes2.dex",
                "Lcom/secnium/StubApplication;"
            ).toString()
            assertEquals("Bangcle", PackerDetector.detect(apkPath))
        }

        @Test
        fun `com_secshell pattern in DEX detected as Bangcle`() {
            val apkPath = createZipWithContent(
                "classes.dex",
                "Lcom/secshell/SecShell;"
            ).toString()
            assertEquals("Bangcle", PackerDetector.detect(apkPath))
        }

        @Test
        fun `com_alibaba_fix pattern in DEX detected as Alibaba`() {
            val apkPath = createZipWithContent(
                "classes.dex",
                "Lcom/alibaba/fix/FixApplication;"
            ).toString()
            assertEquals("Alibaba", PackerDetector.detect(apkPath))
        }

        @Test
        fun `pattern in secondary DEX is also detected`() {
            val apkPath = createZipWithContent(
                "classes3.dex",
                "Lcom/qihoo/util/Qihoo360;"
            ).toString()
            assertEquals("360 Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `non-matching DEX content returns unknown`() {
            val apkPath = createZipWithContent(
                "classes.dex",
                "Lcom/example/app/MainActivity;"
            ).toString()
            assertEquals("unknown", PackerDetector.detect(apkPath))
        }
    }

    // =========================================================================
    // 6. AndroidManifest component detection
    // =========================================================================

    @Nested
    inner class ManifestDetection {

        @Test
        fun `manifest with com_stub_StubApp detected as 360 Jiagu`() {
            val apkPath = createZipWithContent(
                "AndroidManifest.xml",
                "<application android:name=\"com.stub.StubApp\">"
            ).toString()
            assertEquals("360 Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `manifest with com_qihoo detected as 360 Jiagu`() {
            val apkPath = createZipWithContent(
                "AndroidManifest.xml",
                "provider android:name=\"com.qihoo.util.MetaReader\""
            ).toString()
            assertEquals("360 Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `manifest with com_tencent_StubShell detected as Tencent Jiagu`() {
            val apkPath = createZipWithContent(
                "AndroidManifest.xml",
                "<activity android:name=\"com.tencent.StubShell.TxAppEntry\""
            ).toString()
            assertEquals("Tencent Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `manifest with com_ijiami detected as iJiami`() {
            val apkPath = createZipWithContent(
                "AndroidManifest.xml",
                "<application android:name=\"com.ijiami.stub.StubApp\">"
            ).toString()
            assertEquals("iJiami", PackerDetector.detect(apkPath))
        }

        @Test
        fun `manifest with com_shell_SuperApplication detected as iJiami`() {
            val apkPath = createZipWithContent(
                "AndroidManifest.xml",
                "<application android:name=\"com.shell.SuperApplication\">"
            ).toString()
            assertEquals("iJiami", PackerDetector.detect(apkPath))
        }

        @Test
        fun `manifest with com_secnium detected as Bangcle`() {
            val apkPath = createZipWithContent(
                "AndroidManifest.xml",
                "<application android:name=\"com.secnium.wrapper.StubApplication\">"
            ).toString()
            assertEquals("Bangcle", PackerDetector.detect(apkPath))
        }

        @Test
        fun `manifest with com_alibaba_fix detected as Alibaba`() {
            val apkPath = createZipWithContent(
                "AndroidManifest.xml",
                "<application android:name=\"com.alibaba.fix.FixPatchApplication\">"
            ).toString()
            assertEquals("Alibaba", PackerDetector.detect(apkPath))
        }

        @Test
        fun `missing AndroidManifest returns unknown when no other markers exist`() {
            val apkPath = createZip("res/layout/main.xml", "resources.arsc").toString()
            assertEquals("unknown", PackerDetector.detect(apkPath))
        }
    }

    // =========================================================================
    // 7. Detection priority: native libs > DEX classes > manifest
    // =========================================================================

    @Nested
    inner class DetectionPriority {

        @Test
        fun `native lib takes priority over DEX class pattern`() {
            // Contains 360 Jiagu native lib AND Tencent Jiagu DEX class
            // Native lib detection runs first -> 360 Jiagu wins
            val apkPath = createZipWithPriorityMarkers(
                nativeLibs = listOf("lib/arm64-v8a/libjiagu.so"),
                dexContent = "Lcom/tencent/StubShell/RunTimeInfo;",
                manifestContent = null
            )
            assertEquals("360 Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `native lib takes priority over manifest pattern`() {
            // Contains Alibaba native lib AND Bangcle manifest marker
            val apkPath = createZipWithPriorityMarkers(
                nativeLibs = listOf("lib/arm64-v8a/libsgmain.so"),
                dexContent = null,
                manifestContent = "com.secnium.wrapper"
            )
            assertEquals("Alibaba", PackerDetector.detect(apkPath))
        }

        @Test
        fun `DEX class takes priority over manifest when no native lib matches`() {
            // No matching native libs; Tencent DEX class AND iJiami manifest marker
            val apkPath = createZipWithPriorityMarkers(
                nativeLibs = listOf("lib/arm64-v8a/libapp.so"),
                dexContent = "Lcom/tencent/StubShell/RunTimeInfo;",
                manifestContent = "com.ijiami.stub"
            )
            assertEquals("Tencent Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `manifest is used as last resort`() {
            // No native lib, no DEX class pattern, only manifest marker
            val apkPath = createZipWithContent(
                "AndroidManifest.xml",
                "<application android:name=\"com.shell.SuperApplication\">"
            ).toString()
            assertEquals("iJiami", PackerDetector.detect(apkPath))
        }

        @Test
        fun `within native libs first matching pattern in when block wins`() {
            // Contains both Tencent and Alibaba libs.
            // when-block priority: libjiagu > libshella-/libshellx- > libexec > libsgmain
            // Tencent (libshellx) is checked before Alibaba (libsgmain) -> Tencent wins
            val apkPath = createZip(
                "lib/arm64-v8a/libshellx-release.so",
                "lib/arm64-v8a/libsgmain.so"
            ).toString()
            assertEquals("Tencent Jiagu", PackerDetector.detect(apkPath))
        }

        /**
         * Helper: build a zip with native libs, optional DEX content, and optional manifest.
         */
        private fun createZipWithPriorityMarkers(
            nativeLibs: List<String>,
            dexContent: String?,
            manifestContent: String?
        ): String {
            val zipPath = tempDir.resolve("priority_${System.nanoTime()}.zip")
            ZipOutputStream(FileOutputStream(zipPath.toFile())).use { zos ->
                for (lib in nativeLibs) {
                    zos.putNextEntry(ZipEntry(lib))
                    zos.closeEntry()
                }
                if (dexContent != null) {
                    zos.putNextEntry(ZipEntry("classes.dex"))
                    zos.write(dexContent.toByteArray(Charsets.ISO_8859_1))
                    zos.closeEntry()
                }
                if (manifestContent != null) {
                    zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    zos.write(manifestContent.toByteArray(Charsets.ISO_8859_1))
                    zos.closeEntry()
                }
            }
            return zipPath.toString()
        }
    }

    // =========================================================================
    // 8. Multiple DEX files - first matching DEX wins
    // =========================================================================

    @Nested
    inner class MultipleDexFiles {

        @Test
        fun `first matching DEX entry determines result`() {
            val apkPath = createZipWithMultipleDex(
                "classes.dex" to "Lcom/example/app/MainActivity;",
                "classes2.dex" to "Lcom/qihoo/util/StubApp;",
                "classes3.dex" to "Lcom/tencent/StubShell/RunTimeInfo;"
            ).toString()
            // classes2.dex matches 360 Jiagu first in dexEntries iteration
            assertEquals("360 Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `later DEX used when earlier DEX has no match`() {
            val apkPath = createZipWithMultipleDex(
                "classes.dex" to "Lcom/example/app/MainActivity;",
                "classes2.dex" to "Lcom/example/app/Utils;",
                "classes3.dex" to "Lcom/secnium/StubApp;"
            ).toString()
            assertEquals("Bangcle", PackerDetector.detect(apkPath))
        }

        private fun createZipWithMultipleDex(vararg dexFiles: Pair<String, String>): Path {
            val zipPath = tempDir.resolve("multidex_${System.nanoTime()}.zip")
            ZipOutputStream(FileOutputStream(zipPath.toFile())).use { zos ->
                for ((name, content) in dexFiles) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.ISO_8859_1))
                    zos.closeEntry()
                }
            }
            return zipPath
        }
    }

    // =========================================================================
    // 9. Non-.so files in lib/ are ignored
    // =========================================================================

    @Nested
    inner class NonSoFilesIgnored {

        @Test
        fun `files in lib without dot so extension are ignored`() {
            val apkPath = createZip(
                "lib/arm64-v8a/libjiagu.txt",
                "lib/arm64-v8a/readme.md"
            ).toString()
            assertEquals("unknown", PackerDetector.detect(apkPath))
        }

        @Test
        fun `so files outside lib directory are ignored`() {
            val apkPath = createZip(
                "assets/libjiagu.so",
                "classes/libsgmain.so"
            ).toString()
            assertEquals("unknown", PackerDetector.detect(apkPath))
        }
    }

    // =========================================================================
    // 10. libDexHelper.so behavior (ambiguous between iJiami and Bangcle)
    // =========================================================================

    @Nested
    inner class LibDexHelperBehavior {

        @Test
        fun `libDexHelper dot so alone does not match any packer by native lib`() {
            // libDexHelper is listed inside the iJiami when-branch but only
            // libexec.so triggers an actual return. libDexHelper falls through
            // without matching -> native detection returns null -> falls to DEX/manifest.
            val apkPath = createZip("lib/arm64-v8a/libDexHelper.so").toString()
            // With no DEX or manifest markers -> unknown
            assertEquals("unknown", PackerDetector.detect(apkPath))
        }

        @Test
        fun `libDexHelper with Bangcle DEX class detected as Bangcle`() {
            val apkPath = createZipWithMultipleEntries(
                nativeLibs = listOf("lib/arm64-v8a/libDexHelper.so"),
                dexContent = "Lcom/secnium/StubApplication;"
            ).toString()
            assertEquals("Bangcle", PackerDetector.detect(apkPath))
        }

        private fun createZipWithMultipleEntries(
            nativeLibs: List<String>,
            dexContent: String
        ): Path {
            val zipPath = tempDir.resolve("dexhelper_${System.nanoTime()}.zip")
            ZipOutputStream(FileOutputStream(zipPath.toFile())).use { zos ->
                for (lib in nativeLibs) {
                    zos.putNextEntry(ZipEntry(lib))
                    zos.closeEntry()
                }
                zos.putNextEntry(ZipEntry("classes.dex"))
                zos.write(dexContent.toByteArray(Charsets.ISO_8859_1))
                zos.closeEntry()
            }
            return zipPath
        }
    }

    // =========================================================================
    // 11. Realistic multi-feature APK simulation
    // =========================================================================

    @Nested
    inner class RealisticApk {

        @Test
        fun `typical 360 Jiagu APK with jiagu lib and qihoo classes`() {
            val apkPath = createRealisticZip(
                nativeLibs = listOf(
                    "lib/arm64-v8a/libjiagu64.so",
                    "lib/armeabi-v7a/libjiagu.so"
                ),
                dexContent = "Lcom/qihoo/util/StubApp;Lcom/stub/StubApp;",
                manifestContent = "com.stub.StubApp"
            ).toString()
            assertEquals("360 Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `typical Tencent Jiagu APK with shellx lib`() {
            val apkPath = createRealisticZip(
                nativeLibs = listOf(
                    "lib/arm64-v8a/libshellx-super.2019.so",
                    "lib/arm64-v8a/libBugly.so"
                ),
                dexContent = "Lcom/tencent/StubShell/RunTimeInfo;",
                manifestContent = "com.tencent.StubShell.TxAppEntry"
            ).toString()
            assertEquals("Tencent Jiagu", PackerDetector.detect(apkPath))
        }

        @Test
        fun `typical clean APK returns unknown`() {
            val apkPath = createRealisticZip(
                nativeLibs = listOf(
                    "lib/arm64-v8a/libapp.so",
                    "lib/arm64-v8a/libflutter.so"
                ),
                dexContent = "Lcom/example/app/MainActivity;Landroid/app/Application;",
                manifestContent = "com.example.app.MainActivity"
            ).toString()
            assertEquals("unknown", PackerDetector.detect(apkPath))
        }

        private fun createRealisticZip(
            nativeLibs: List<String>,
            dexContent: String,
            manifestContent: String
        ): Path {
            val zipPath = tempDir.resolve("realistic_${System.nanoTime()}.zip")
            ZipOutputStream(FileOutputStream(zipPath.toFile())).use { zos ->
                // Standard APK structure
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write(manifestContent.toByteArray(Charsets.ISO_8859_1))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("classes.dex"))
                zos.write(dexContent.toByteArray(Charsets.ISO_8859_1))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("resources.arsc"))
                zos.closeEntry()

                for (lib in nativeLibs) {
                    zos.putNextEntry(ZipEntry(lib))
                    zos.closeEntry()
                }

                // Some regular assets
                zos.putNextEntry(ZipEntry("res/layout/activity_main.xml"))
                zos.closeEntry()
            }
            return zipPath
        }
    }
}
