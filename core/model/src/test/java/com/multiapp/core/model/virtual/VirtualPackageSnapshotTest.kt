package com.multiapp.core.model.virtual

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VirtualPackageSnapshotTest {

    @Test
    fun `runtime path contract exposes base before split paths`() {
        val snapshot = snapshot(
            sourceDir = "/runtime/base.apk",
            publicSourceDir = "/public/base.apk",
            splitSourceDirs = listOf(
                "/runtime/split_config.arm64_v8a.apk",
                "/runtime/split_feature.reader.apk"
            ),
            splitPublicSourceDirs = listOf(
                "/public/split_config.arm64_v8a.apk",
                "/public/split_feature.reader.apk"
            ),
            splitNames = listOf("config.arm64_v8a", "feature.reader")
        )

        assertEquals(
            listOf(
                "/runtime/base.apk",
                "/runtime/split_config.arm64_v8a.apk",
                "/runtime/split_feature.reader.apk"
            ),
            snapshot.codeSourceDirs
        )
        assertEquals(
            listOf(
                "/public/base.apk",
                "/public/split_config.arm64_v8a.apk",
                "/public/split_feature.reader.apk"
            ),
            snapshot.publicResourceDirs
        )
    }

    @Test
    fun `split public dirs default to split source dirs`() {
        val snapshot = snapshot(
            sourceDir = "/runtime/base.apk",
            splitSourceDirs = listOf("/runtime/split_config.en.apk"),
            splitNames = listOf("config.en")
        )

        assertEquals(
            listOf("/runtime/base.apk", "/runtime/split_config.en.apk"),
            snapshot.publicResourceDirs
        )
    }

    @Test
    fun `public source dir must not be blank`() {
        assertFailsWith<IllegalArgumentException> {
            snapshot(publicSourceDir = "")
        }
    }

    private fun snapshot(
        sourceDir: String = "/runtime/base.apk",
        publicSourceDir: String = sourceDir,
        splitSourceDirs: List<String> = emptyList(),
        splitPublicSourceDirs: List<String> = splitSourceDirs,
        splitNames: List<String> = emptyList()
    ) = VirtualPackageSnapshot(
        instanceId = "instance-1",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.virtual.instance1",
        applicationLabel = "Example",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 23,
        sourceDir = sourceDir,
        publicSourceDir = publicSourceDir,
        splitSourceDirs = splitSourceDirs,
        splitPublicSourceDirs = splitPublicSourceDirs,
        splitNames = splitNames,
        dataDir = "/data/user/0/com.multiapp.app/files/instances/instance-1"
    )
}
