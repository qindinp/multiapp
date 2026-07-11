package com.multiapp.core.engine

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.Context
import android.content.OperationApplicationException
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import com.multiapp.core.identity.ProviderRouteToken
import com.multiapp.core.loader.VirtualContentResolverFactoryRequest
import com.multiapp.core.model.engine.ProviderRouteContract
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngineVirtualContentResolverTest {
    @Test
    fun `authority resolver observes providers added after first lookup`() {
        val context = mockk<Context>(relaxed = true)
        val service = mockk<VirtualProviderService>(relaxed = true)
        val uri = mockk<Uri>(relaxed = true)
        var ownerInstalled = false
        every { uri.authority } returns "com.dynamic.provider"
        every { uri.encodedPath } returns "/items/7"
        every { service.resolveProviderAuthority("inst-001", any()) } answers {
            VirtualProviderAuthorityResolveResult(
                callerInstanceId = "inst-001",
                guestAuthority = "com.dynamic.provider",
                verdict = if (ownerInstalled) {
                    com.multiapp.core.model.engine.EngineResultStatus.PARTIAL
                } else {
                    com.multiapp.core.model.engine.EngineResultStatus.FAIL
                },
                virtualAuthority = ownerInstalled,
                targetInstanceId = "owner-2".takeIf { ownerInstalled },
                message = if (ownerInstalled) {
                    "provider_authority_resolved_unique_owner"
                } else {
                    "provider_authority_not_virtual:com.dynamic.provider"
                }
            )
        }
        val resolver = DefaultEngineProviderAuthorityResolver(
            hostContext = context,
            config = config(),
            providerServiceFactory = { service }
        )

        val beforeInstall = resolver.resolve(uri, EngineProviderOperation.QUERY, null)
        ownerInstalled = true
        val afterInstall = resolver.resolve(uri, EngineProviderOperation.QUERY, null)

        assertTrue(!beforeInstall.virtualAuthority)
        assertTrue(afterInstall.virtualAuthority)
        assertEquals("owner-2", afterInstall.targetInstanceId)
        verify(exactly = 2) { service.resolveProviderAuthority("inst-001", any()) }
    }

    @Test
    fun `resolver factory is baseline on api 29 and falls back on api 28`() {
        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<ContentResolver>(relaxed = true)
        var wrappedProvider: ContentProvider? = null
        val api29Factory = EngineVirtualContentResolverFactory(
            sdkInt = { 29 },
            resolverWrapper = { provider ->
                wrappedProvider = provider
                resolver
            },
            dispatcherFactory = { mockk(relaxed = true) },
            uidProvider = { 1000 },
            pidProvider = { 3001 },
            providerAttacher = { _, _, _ -> }
        )
        val api28Factory = EngineVirtualContentResolverFactory(
            sdkInt = { 28 },
            resolverWrapper = { error("API 28 must not wrap a provider") }
        )
        val request = VirtualContentResolverFactoryRequest(context, config())

        assertSame(resolver, api29Factory.create(request))
        assertIs<EngineRoutingContentProvider>(wrappedProvider)
        assertNull(api28Factory.create(request))
    }

    @Test
    fun `wrapped provider routes query through verified engine dispatch`() {
        val hostContext = mockk<Context>(relaxed = true)
        every { hostContext.packageName } returns "com.multiapp.app"
        val guestUri = mockk<Uri>(relaxed = true)
        val proxyUri = mockk<Uri>(relaxed = true)
        val builder = mockk<Uri.Builder>(relaxed = true)
        every { guestUri.authority } returns "com.example.provider"
        every { guestUri.encodedQuery } returns "bookId=7"
        every { guestUri.buildUpon() } returns builder
        every { builder.encodedAuthority(any()) } returns builder
        every { builder.encodedQuery(any()) } returns builder
        every { builder.appendQueryParameter(any(), any()) } returns builder
        every { builder.build() } returns proxyUri

        val cursor = mockk<Cursor>(relaxed = true)
        val guestProvider = mockk<ContentProvider>(relaxed = true)
        every {
            guestProvider.query(guestUri, any(), any(), any(), any())
        } returns cursor
        val dispatchRequest = slot<EngineProviderDispatchRequest>()
        val dispatcher = mockk<EngineProviderDispatcher>()
        every { dispatcher.dispatch(capture(dispatchRequest)) } returns EngineProviderDispatchResult.ProviderReady(
            resolution = EngineProviderResolution(
                instanceId = "inst-001",
                originPackageName = "com.example",
                virtualPackageName = "com.multiapp.instance.inst001",
                guestAuthority = "com.example.provider",
                proxyAuthority = "com.multiapp.app.multiapp.provider.stub.v1",
                providerClassName = "com.example.DataProvider",
                policy = EngineProviderPolicy(
                    exported = false,
                    permission = null,
                    grantUriPermissions = false,
                    status = "INTERNAL_ONLY",
                    reason = "self",
                    routingScope = "INSTANCE",
                    processWideProviderHook = false,
                    authorityRewriteEntry = "EngineVirtualContentResolver"
                )
            ),
            provider = guestProvider,
            cached = true,
            evidence = EngineProviderEvidence(
                instanceId = "inst-001",
                guestAuthority = "com.example.provider",
                proxyAuthority = "com.multiapp.app.multiapp.provider.stub.v1",
                providerClassName = "com.example.DataProvider",
                operation = EngineProviderOperation.QUERY,
                success = true
            )
        )
        val bridge = EngineRoutingContentProvider(
            hostContext = hostContext,
            config = config(),
            dispatcher = dispatcher,
            hostUid = 1000,
            processId = 3001,
            routeIssuer = { caller, target, authority, operation, processSlot ->
                ProviderRouteToken(
                    token = "route-token",
                    callerInstanceId = caller,
                    targetInstanceId = target,
                    authority = authority,
                    operation = operation,
                    expiresAtMillis = Long.MAX_VALUE,
                    processSlot = processSlot
                )
            }
        )

        val actual = bridge.query(guestUri, null, null, null, null)

        assertSame(cursor, actual)
        assertEquals("query", dispatchRequest.captured.operationName)
        assertEquals("inst-001", dispatchRequest.captured.verifiedRoute?.callerInstanceId)
        assertEquals("inst-001", dispatchRequest.captured.verifiedRoute?.targetInstanceId)
        assertEquals(1000, dispatchRequest.captured.providerCallingUid)
        assertEquals(3001, dispatchRequest.captured.providerCallingPid)
        assertEquals("com.multiapp.app:v1", dispatchRequest.captured.callerProcessSlot)
        verify {
            builder.appendQueryParameter(ProviderRouteContract.PROXY_ROUTE_TOKEN, "route-token")
        }
        verify(exactly = 1) { guestProvider.query(guestUri, any(), any(), any(), any()) }
    }

    @Test
    fun `wrapped provider delegates system authorities to host resolver`() {
        val hostContext = mockk<Context>(relaxed = true)
        val hostResolver = mockk<ContentResolver>()
        val systemUri = mockk<Uri>(relaxed = true)
        val cursor = mockk<Cursor>(relaxed = true)
        val dispatcher = mockk<EngineProviderDispatcher>(relaxed = true)
        every { hostContext.contentResolver } returns hostResolver
        every { systemUri.authority } returns "settings"
        every {
            hostResolver.query(systemUri, null, null, null, null)
        } returns cursor
        val bridge = EngineRoutingContentProvider(
            hostContext = hostContext,
            config = config(),
            dispatcher = dispatcher,
            hostUid = 1000,
            processId = 3001
        )

        val actual = bridge.query(systemUri, null, null, null, null)

        assertSame(cursor, actual)
        verify(exactly = 1) { hostResolver.query(systemUri, null, null, null, null) }
        verify(exactly = 0) { dispatcher.dispatch(any()) }
    }

    @Test
    fun `wrapped provider preserves system refresh delegation`() {
        val hostContext = mockk<Context>(relaxed = true)
        val hostResolver = mockk<ContentResolver>()
        val uri = mockk<Uri>(relaxed = true)
        val extras = mockk<Bundle>()
        val signal = mockk<CancellationSignal>()
        val dispatcher = mockk<EngineProviderDispatcher>(relaxed = true)
        every { hostContext.contentResolver } returns hostResolver
        every { uri.authority } returns "settings"
        every { hostResolver.refresh(uri, extras, signal) } returns true
        val bridge = EngineRoutingContentProvider(
            hostContext = hostContext,
            config = config(),
            dispatcher = dispatcher,
            hostUid = 1000,
            processId = 3001
        )

        assertTrue(bridge.refresh(uri, extras, signal))
        verify(exactly = 1) { hostResolver.refresh(uri, extras, signal) }
        verify(exactly = 0) { dispatcher.dispatch(any()) }
    }

    @Test
    fun `wrapped provider routes external virtual authority to engine resolved owner`() {
        val hostContext = mockk<Context>(relaxed = true)
        every { hostContext.packageName } returns "com.multiapp.app"
        val uri = mockk<Uri>(relaxed = true)
        val proxyUri = mockk<Uri>(relaxed = true)
        val builder = mockk<Uri.Builder>(relaxed = true)
        every { uri.authority } returns "com.other.provider"
        every { uri.encodedPath } returns "/books/7"
        every { uri.encodedQuery } returns null
        every { uri.buildUpon() } returns builder
        every { builder.encodedAuthority(any()) } returns builder
        every { builder.encodedQuery(any()) } returns builder
        every { builder.appendQueryParameter(any(), any()) } returns builder
        every { builder.build() } returns proxyUri
        val cursor = mockk<Cursor>(relaxed = true)
        val guestProvider = mockk<ContentProvider>(relaxed = true)
        every { guestProvider.query(uri, any(), any(), any(), any()) } returns cursor
        val request = slot<EngineProviderDispatchRequest>()
        val dispatcher = mockk<EngineProviderDispatcher>()
        every { dispatcher.dispatch(capture(request)) } returns readyProvider(
            guestProvider,
            instanceId = "owner-2",
            authority = "com.other.provider"
        )
        val bridge = EngineRoutingContentProvider(
            hostContext = hostContext,
            config = config(),
            dispatcher = dispatcher,
            authorityResolver = EngineProviderAuthorityResolver { _, _, _ ->
                VirtualProviderAuthorityResolveResult(
                    callerInstanceId = "inst-001",
                    guestAuthority = "com.other.provider",
                    verdict = com.multiapp.core.model.engine.EngineResultStatus.PARTIAL,
                    virtualAuthority = true,
                    targetInstanceId = "owner-2",
                    message = "provider_authority_resolved_by_uri_grant"
                )
            },
            hostUid = 1000,
            processId = 3001,
            routeIssuer = { caller, target, authority, operation, processSlot ->
                ProviderRouteToken(
                    token = "cross-route-token",
                    callerInstanceId = caller,
                    targetInstanceId = target,
                    authority = authority,
                    operation = operation,
                    expiresAtMillis = Long.MAX_VALUE,
                    processSlot = processSlot
                )
            }
        )

        assertSame(cursor, bridge.query(uri, null, null, null, null))
        assertEquals("inst-001", request.captured.verifiedRoute?.callerInstanceId)
        assertEquals("owner-2", request.captured.verifiedRoute?.targetInstanceId)
        verify(exactly = 0) { hostContext.contentResolver }
    }

    @Test
    fun `blocked virtual authority never falls through to host resolver`() {
        val hostContext = mockk<Context>(relaxed = true)
        val uri = mockk<Uri>(relaxed = true)
        every { uri.authority } returns "com.clone.ambiguous"
        every { uri.encodedPath } returns "/private"
        val dispatcher = mockk<EngineProviderDispatcher>(relaxed = true)
        val bridge = EngineRoutingContentProvider(
            hostContext = hostContext,
            config = config(),
            dispatcher = dispatcher,
            authorityResolver = EngineProviderAuthorityResolver { _, _, _ ->
                VirtualProviderAuthorityResolveResult(
                    callerInstanceId = "inst-001",
                    guestAuthority = "com.clone.ambiguous",
                    verdict = com.multiapp.core.model.engine.EngineResultStatus.UNSUPPORTED,
                    virtualAuthority = true,
                    message = "provider_authority_owner_ambiguous"
                )
            },
            hostUid = 1000,
            processId = 3001
        )

        assertNull(bridge.query(uri, null, null, null, null))
        verify(exactly = 0) { hostContext.contentResolver }
        verify(exactly = 0) { dispatcher.dispatch(any()) }
    }

    @Test
    fun `applyBatch validates every operation then invokes one resolved guest provider`() {
        val hostContext = mockk<Context>(relaxed = true)
        every { hostContext.packageName } returns "com.multiapp.app"
        val uri = mockk<Uri>(relaxed = true)
        val proxyUri = mockk<Uri>(relaxed = true)
        val builder = mockk<Uri.Builder>(relaxed = true)
        every { uri.authority } returns "com.other.provider"
        every { uri.encodedPath } returns "/items"
        every { uri.buildUpon() } returns builder
        every { builder.encodedAuthority(any()) } returns builder
        every { builder.encodedQuery(any()) } returns builder
        every { builder.appendQueryParameter(any(), any()) } returns builder
        every { builder.build() } returns proxyUri
        val read = mockk<ContentProviderOperation>()
        every { read.uri } returns uri
        every { read.isWriteOperation } returns false
        every { read.isReadOperation } returns true
        val write = mockk<ContentProviderOperation>()
        every { write.uri } returns uri
        every { write.isWriteOperation } returns true
        every { write.isReadOperation } returns false
        val operations = arrayListOf(read, write)
        val expected = arrayOf(mockk<ContentProviderResult>(), mockk<ContentProviderResult>())
        val guestProvider = mockk<ContentProvider>()
        every { guestProvider.applyBatch("com.other.provider", operations) } returns expected
        val requests = mutableListOf<EngineProviderDispatchRequest>()
        val dispatcher = mockk<EngineProviderDispatcher>()
        every { dispatcher.dispatch(capture(requests)) } returns readyProvider(
            guestProvider,
            instanceId = "owner-2",
            authority = "com.other.provider"
        )
        val bridge = EngineRoutingContentProvider(
            hostContext = hostContext,
            config = config(),
            dispatcher = dispatcher,
            authorityResolver = EngineProviderAuthorityResolver { _, _, _ ->
                VirtualProviderAuthorityResolveResult(
                    callerInstanceId = "inst-001",
                    guestAuthority = "com.other.provider",
                    verdict = com.multiapp.core.model.engine.EngineResultStatus.PARTIAL,
                    virtualAuthority = true,
                    targetInstanceId = "owner-2",
                    message = "provider_authority_resolved_unique_owner"
                )
            },
            hostUid = 1000,
            processId = 3001,
            routeIssuer = { caller, target, authority, operation, processSlot ->
                ProviderRouteToken(
                    token = "batch-$operation",
                    callerInstanceId = caller,
                    targetInstanceId = target,
                    authority = authority,
                    operation = operation,
                    expiresAtMillis = Long.MAX_VALUE,
                    processSlot = processSlot
                )
            }
        )

        assertSame(expected, bridge.applyBatch("com.other.provider", operations))
        assertEquals(listOf("applyBatch:r", "applyBatch:w"), requests.map { it.operationName })
        assertTrue(requests.all { it.verifiedRoute?.targetInstanceId == "owner-2" })
        verify(exactly = 1) { guestProvider.applyBatch("com.other.provider", operations) }
        verify(exactly = 0) { hostContext.contentResolver }
    }

    @Test
    fun `applyBatch rejects operations resolving to different clone owners before dispatch`() {
        val hostContext = mockk<Context>(relaxed = true)
        val uri = mockk<Uri>(relaxed = true)
        every { uri.authority } returns "com.clone.provider"
        val read = mockk<ContentProviderOperation>()
        every { read.uri } returns uri
        every { read.isWriteOperation } returns false
        every { read.isReadOperation } returns true
        val write = mockk<ContentProviderOperation>()
        every { write.uri } returns uri
        every { write.isWriteOperation } returns true
        every { write.isReadOperation } returns false
        val dispatcher = mockk<EngineProviderDispatcher>(relaxed = true)
        val bridge = EngineRoutingContentProvider(
            hostContext = hostContext,
            config = config(),
            dispatcher = dispatcher,
            authorityResolver = EngineProviderAuthorityResolver { _, _, accessMode ->
                VirtualProviderAuthorityResolveResult(
                    callerInstanceId = "inst-001",
                    guestAuthority = "com.clone.provider",
                    verdict = com.multiapp.core.model.engine.EngineResultStatus.PARTIAL,
                    virtualAuthority = true,
                    targetInstanceId = if (accessMode == "r") "owner-1" else "owner-2",
                    message = "provider_authority_resolved_by_uri_grant"
                )
            },
            hostUid = 1000,
            processId = 3001
        )

        assertFailsWith<OperationApplicationException> {
            bridge.applyBatch("com.clone.provider", arrayListOf(read, write))
        }
        verify(exactly = 0) { dispatcher.dispatch(any()) }
        verify(exactly = 0) { hostContext.contentResolver }
    }

    private fun readyProvider(
        provider: ContentProvider,
        instanceId: String,
        authority: String
    ) = EngineProviderDispatchResult.ProviderReady(
        resolution = EngineProviderResolution(
            instanceId = instanceId,
            originPackageName = "com.other",
            virtualPackageName = "com.multiapp.instance.$instanceId",
            guestAuthority = authority,
            proxyAuthority = "com.multiapp.app.multiapp.provider.stub.v1",
            providerClassName = "com.other.DataProvider",
            policy = EngineProviderPolicy(
                exported = false,
                permission = null,
                grantUriPermissions = true,
                status = "URI_GRANT",
                reason = "cross-instance",
                routingScope = "INSTANCE",
                processWideProviderHook = false,
                authorityRewriteEntry = "EngineVirtualContentResolver"
            )
        ),
        provider = provider,
        cached = true,
        evidence = EngineProviderEvidence(
            instanceId = instanceId,
            guestAuthority = authority,
            proxyAuthority = "com.multiapp.app.multiapp.provider.stub.v1",
            providerClassName = "com.other.DataProvider",
            operation = EngineProviderOperation.QUERY,
            success = true
        )
    )

    private fun config() = VirtualContextConfig(
        instanceId = "inst-001",
        originPackageName = "com.example",
        virtualPackageName = "com.multiapp.instance.inst001",
        dataDir = "/data/inst-001",
        sourceDir = "/data/inst-001/base.apk",
        nativeLibraryDir = null,
        classLoader = ClassLoader.getSystemClassLoader(),
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = "inst-001",
            originPackageName = "com.example",
            virtualPackageName = "com.multiapp.instance.inst001",
            applicationLabel = "Example",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "/data/inst-001/base.apk",
            dataDir = "/data/inst-001",
            providers = listOf(
                ResolvedComponent(
                    name = "com.example.DataProvider",
                    authorities = listOf("com.example.provider")
                )
            )
        ),
        processSlot = "com.multiapp.app:v1"
    )
}
