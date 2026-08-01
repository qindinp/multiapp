package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentAuthority
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.ResolvedIntentPathPattern
import com.multiapp.core.model.virtual.ResolvedIntentPathPatternType
import com.multiapp.core.model.virtual.VirtualMetaDataValue
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.VirtualProviderPathPattern
import com.multiapp.core.model.virtual.VirtualProviderPathPatternType
import com.multiapp.core.model.virtual.VirtualProviderPathPermission
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineAuthoritativeRuntimePayloadCodecTest {

    @Test
    fun `large manifest runtime round trips without framework Bundle`() {
        val runtime = runtime(activityCount = 718)

        val payload = EngineAuthoritativeRuntimePayloadCodec.encode(runtime)
        val decoded = EngineAuthoritativeRuntimePayloadCodec.decode(payload)

        assertTrue(payload.size > 100_000)
        assertEquals(runtime, decoded)
    }

    @Test
    fun `truncated runtime payload is rejected`() {
        val payload = EngineAuthoritativeRuntimePayloadCodec.encode(runtime(activityCount = 10))

        assertNull(EngineAuthoritativeRuntimePayloadCodec.decode(payload.copyOf(payload.size - 1)))
    }

    @Test
    fun `blank optional manifest values are encoded as absent`() {
        val base = runtime(activityCount = 10)
        val input = base.copy(
            packageSnapshot = base.packageSnapshot.copy(
                taskAffinity = " ",
                activities = base.packageSnapshot.activities.mapIndexed { index, component ->
                    if (index == 0) component.copy(taskAffinity = "") else component
                }
            )
        )

        val decoded = EngineAuthoritativeRuntimePayloadCodec.decode(
            EngineAuthoritativeRuntimePayloadCodec.encode(input)
        )

        assertNull(decoded?.packageSnapshot?.taskAffinity)
        assertNull(decoded?.packageSnapshot?.activities?.first()?.taskAffinity)
    }

    @Test
    fun `duplicate provider authorities survive runtime transport in manifest order`() {
        val base = runtime(activityCount = 10)
        val first = base.packageSnapshot.providers.single()
        val second = first.copy(name = "androidx.core.content.FileProvider")
        val input = base.copy(
            packageSnapshot = base.packageSnapshot.copy(providers = listOf(first, second))
        )

        val decoded = EngineAuthoritativeRuntimePayloadCodec.decode(
            EngineAuthoritativeRuntimePayloadCodec.encode(input)
        )

        assertEquals(listOf(first, second), decoded?.packageSnapshot?.providers)
    }

    private fun runtime(activityCount: Int): VirtualInstanceRuntime {
        val sharedFilter = ResolvedIntentFilter(
            actions = listOf("android.intent.action.VIEW"),
            categories = listOf("android.intent.category.DEFAULT"),
            dataSchemes = listOf("multiapp"),
            dataMimeTypes = listOf("application/octet-stream"),
            dataAuthorities = listOf("example.test"),
            dataPaths = listOf("/entry"),
            priority = 100,
            authorityEntries = listOf(ResolvedIntentAuthority("example.test", 443)),
            pathPatterns = listOf(
                ResolvedIntentPathPattern("/entry", ResolvedIntentPathPatternType.PREFIX)
            )
        )
        val activities = (0 until activityCount).map { index ->
            ResolvedComponent(
                name = "com.tencent.mobileqq.generated.Activity$index",
                exported = index % 2 == 0,
                intentFilters = listOf("android.intent.action.VIEW"),
                resolvedIntentFilters = listOf(sharedFilter),
                launchMode = if (index == 0) "singleTask" else "standard",
                processName = if (index % 5 == 0) ":tool$index" else null,
                taskAffinity = "com.tencent.mobileqq.task$index",
                metaData = mapOf("component.index" to index.toString()),
                typedMetaData = mapOf("component.index" to VirtualMetaDataValue.int(index))
            )
        }
        val provider = ResolvedComponent(
            name = "com.tencent.mobileqq.provider.RuntimeProvider",
            exported = false,
            authorities = listOf("com.tencent.mobileqq.runtime"),
            grantUriPermissions = true,
            pathPermissions = listOf(
                VirtualProviderPathPermission(
                    pattern = VirtualProviderPathPattern(
                        path = "/shared",
                        type = VirtualProviderPathPatternType.PREFIX
                    ),
                    readPermission = "com.tencent.mobileqq.permission.READ"
                )
            ),
            uriPermissionPatterns = listOf(
                VirtualProviderPathPattern(
                    path = "/shared",
                    type = VirtualProviderPathPatternType.PREFIX
                )
            )
        )
        val snapshot = VirtualPackageSnapshot(
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            applicationLabel = "QQ",
            versionCode = 15_178L,
            versionName = "9.3.25",
            targetSdk = 34,
            minSdk = 23,
            sourceDir = "/data/user/0/com.multiapp.app/files/artifacts/qq.apk",
            sourceSha256 = DIGEST,
            dataDir = "/data/user/0/com.multiapp.app/files/instance_data/$INSTANCE_ID",
            applicationClassName = "com.tencent.mobileqq.qfix.QFixApplication",
            processName = ORIGIN_PACKAGE,
            taskAffinity = ORIGIN_PACKAGE,
            metaData = mapOf("channel" to "multiapp"),
            typedMetaData = mapOf("debug" to VirtualMetaDataValue.boolean(false)),
            launcherActivityName = activities.first().name,
            activities = activities,
            providers = listOf(provider),
            permissions = listOf("android.permission.INTERNET"),
            debuggable = true,
            sharedUserId = "android.uid.shared",
            sharedUserLabel = 0x7f01_0203,
            originCertSha256 = DIGEST,
            signerSha256Digests = listOf(DIGEST)
        )
        return VirtualInstanceRuntime(
            instanceId = INSTANCE_ID,
            hostPackageName = "com.multiapp.app",
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            dataRoot = snapshot.dataDir,
            packageSnapshot = snapshot,
            profile = EngineProfile.BASELINE,
            processSlot = "com.multiapp.app:v0",
            proxySlot = "proxy-0",
            evidenceSessionId = "evidence-session",
            runtimeEpoch = 123L,
            engineSessionId = "engine-session",
            state = VirtualRuntimeState.CREATED
        )
    }

    private companion object {
        const val INSTANCE_ID = "2ff628f1-8042-4df4-b8e8-d56b0dadd62a"
        const val ORIGIN_PACKAGE = "com.tencent.mobileqq"
        const val VIRTUAL_PACKAGE = "com.multiapp.instance.2ff628f18042"
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
