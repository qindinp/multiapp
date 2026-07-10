package com.multiapp.core.loader

import android.content.ContentResolver
import android.net.Uri
import com.multiapp.core.common.findField
import com.multiapp.core.common.findMethod
import com.multiapp.core.model.engine.ProviderRouteContract
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

data class VirtualContentServiceRoute(
    val instanceId: String,
    val authorityMap: Map<String, String>,
    val processSlot: String?
)

data class VirtualContentServiceOperationRecord(
    val instanceId: String,
    val operation: String,
    val guestAuthority: String?,
    val proxyAuthority: String?,
    val processSlot: String?,
    val routedUriCount: Int,
    val success: Boolean,
    val reason: String
)

fun interface VirtualContentServiceOperationRecorder {
    fun record(record: VirtualContentServiceOperationRecord)
}

object VirtualContentServiceOperationRecorders {
    @Volatile
    private var recorder: VirtualContentServiceOperationRecorder? = null

    fun install(recorder: VirtualContentServiceOperationRecorder?) {
        this.recorder = recorder
    }

    fun record(record: VirtualContentServiceOperationRecord) {
        recorder?.record(record)
    }
}

object VirtualContentServiceRoutes {
    private val proxyParameterNames = setOf(
        ProviderRouteContract.PROXY_INSTANCE_ID,
        ProviderRouteContract.PROXY_GUEST_AUTHORITY,
        ProviderRouteContract.PROXY_PROCESS_SLOT,
        ProviderRouteContract.PROXY_ROUTE_TOKEN
    )

    @Volatile
    private var activeRoute: VirtualContentServiceRoute? = null

    fun install(plan: VirtualProviderRoutingPlan) {
        install(
            VirtualContentServiceRoute(
                instanceId = plan.instanceId,
                authorityMap = plan.authorityMap.toMap(),
                processSlot = plan.processSlot
            )
        )
    }

    internal fun install(route: VirtualContentServiceRoute) {
        activeRoute = route.copy(authorityMap = route.authorityMap.toMap())
    }

    fun reset() {
        activeRoute = null
    }

    internal fun current(): VirtualContentServiceRoute? = activeRoute

    internal fun rewrite(uri: Uri, route: VirtualContentServiceRoute? = activeRoute): Uri {
        val active = route ?: return uri
        val currentAuthority = uri.authority ?: return uri
        val guestAuthority = when {
            currentAuthority in active.authorityMap -> currentAuthority
            currentAuthority in active.authorityMap.values ->
                uri.getQueryParameter(ProviderRouteContract.PROXY_GUEST_AUTHORITY)
                    ?.takeIf { it in active.authorityMap }
                    ?: active.authorityMap.entries.singleOrNull { it.value == currentAuthority }?.key
            else -> null
        } ?: return uri
        val proxyAuthority = active.authorityMap[guestAuthority] ?: return uri
        return uri.buildUpon()
            .encodedAuthority(proxyAuthority)
            .encodedQuery(rewriteEncodedQuery(uri.encodedQuery, active, guestAuthority))
            .build()
    }

    internal fun rewriteEncodedQuery(
        encodedQuery: String?,
        route: VirtualContentServiceRoute,
        guestAuthority: String
    ): String {
        val guestQuery = encodedQuery
            ?.split("&")
            .orEmpty()
            .filterNot { part -> proxyParameterNames.contains(part.substringBefore("=")) }
        val routeQuery = listOfNotNull(
            "${ProviderRouteContract.PROXY_INSTANCE_ID}=${route.instanceId}",
            "${ProviderRouteContract.PROXY_GUEST_AUTHORITY}=$guestAuthority",
            route.processSlot?.takeIf { it.isNotBlank() }?.let {
                "${ProviderRouteContract.PROXY_PROCESS_SLOT}=$it"
            }
        )
        return (guestQuery + routeQuery).joinToString("&")
    }
}

data class VirtualContentServiceProxyInstallResult(
    val installed: Boolean,
    val status: String,
    val reason: String
)

class VirtualContentServiceProxyInstaller(
    private val contentResolverClass: Class<*> = ContentResolver::class.java,
    private val interfaceClassProvider: () -> Class<*> = {
        Class.forName("android.content.IContentService")
    }
) {
    @Synchronized
    fun install(): VirtualContentServiceProxyInstallResult = runCatching {
        val field = findField(contentResolverClass, "sContentService")
            ?: return VirtualContentServiceProxyInstallResult(
                installed = false,
                status = "UNSUPPORTED",
                reason = "content_service_cache_field_missing"
            )
        field.isAccessible = true
        val base = field.get(null) ?: run {
            val getter = findMethod(contentResolverClass, "getContentService", emptyArray())
                ?: return VirtualContentServiceProxyInstallResult(
                    installed = false,
                    status = "UNSUPPORTED",
                    reason = "content_service_getter_missing"
                )
            getter.isAccessible = true
            getter.invoke(null)
        } ?: return VirtualContentServiceProxyInstallResult(
            installed = false,
            status = "FAIL",
            reason = "content_service_unavailable"
        )
        if (Proxy.isProxyClass(base.javaClass) && Proxy.getInvocationHandler(base) is VirtualContentServiceHandler) {
            return VirtualContentServiceProxyInstallResult(
                installed = true,
                status = "ALREADY_INSTALLED",
                reason = "engine_content_service_proxy_active"
            )
        }
        val interfaceClass = interfaceClassProvider()
        val proxy = Proxy.newProxyInstance(
            interfaceClass.classLoader,
            arrayOf(interfaceClass),
            VirtualContentServiceHandler(base)
        )
        field.set(null, proxy)
        VirtualContentServiceProxyInstallResult(
            installed = true,
            status = "PASS",
            reason = "engine_content_service_proxy_installed"
        )
    }.getOrElse { error ->
        VirtualContentServiceProxyInstallResult(
            installed = false,
            status = "FAIL",
            reason = "${error.javaClass.name}:${error.message.orEmpty()}"
        )
    }
}

internal class VirtualContentServiceHandler(
    private val base: Any
) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        when (method.name) {
            "toString" -> return "MultiAppContentServiceProxy(${System.identityHashCode(proxy)})"
            "hashCode" -> return System.identityHashCode(proxy)
            "equals" -> return proxy === args?.firstOrNull()
        }
        val originalArgs: Array<Any?> = args?.map { it }?.toTypedArray() ?: emptyArray()
        val routed = routeArguments(method.name, originalArgs)
        return try {
            method.invoke(base, *routed.args).also {
                routed.record(success = true, reason = "framework_content_service_dispatched")
            }
        } catch (error: InvocationTargetException) {
            routed.record(
                success = false,
                reason = error.targetException?.javaClass?.name ?: error.javaClass.name
            )
            throw (error.targetException ?: error)
        } catch (error: Throwable) {
            routed.record(success = false, reason = error.javaClass.name)
            throw error
        }
    }

    private fun routeArguments(methodName: String, args: Array<Any?>): RoutedArguments {
        val route = VirtualContentServiceRoutes.current()
            ?: return RoutedArguments(args, methodName, null, emptyList())
        if (methodName == "unregisterContentObserver") {
            return RoutedArguments(args, methodName, route, emptyList())
        }
        if (methodName != "registerContentObserver" && methodName != "notifyChange") {
            return RoutedArguments(args, methodName, null, emptyList())
        }
        val routedUris = mutableListOf<RoutedUri>()
        val rewrittenArgs = args.copyOf()
        rewrittenArgs.indices.forEach { index ->
            when (val value = rewrittenArgs[index]) {
                is Uri -> {
                    val rewritten = VirtualContentServiceRoutes.rewrite(value, route)
                    if (rewritten != value) {
                        routedUris += RoutedUri(value.authority, rewritten.authority)
                        rewrittenArgs[index] = rewritten
                    }
                }
                is Collection<*> -> {
                    var changed = false
                    val rewritten = value.map { item ->
                        val uri = item as? Uri ?: return@map item
                        val routedUri = VirtualContentServiceRoutes.rewrite(uri, route)
                        if (routedUri != uri) {
                            changed = true
                            routedUris += RoutedUri(uri.authority, routedUri.authority)
                        }
                        routedUri
                    }
                    if (changed) rewrittenArgs[index] = rewritten
                }
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val rewritten = value.copyOf() as Array<Any?>
                    var changed = false
                    rewritten.indices.forEach { uriIndex ->
                        val uri = rewritten[uriIndex] as? Uri ?: return@forEach
                        val routedUri = VirtualContentServiceRoutes.rewrite(uri, route)
                        if (routedUri != uri) {
                            changed = true
                            routedUris += RoutedUri(uri.authority, routedUri.authority)
                            rewritten[uriIndex] = routedUri
                        }
                    }
                    if (changed) rewrittenArgs[index] = rewritten
                }
            }
        }
        return RoutedArguments(rewrittenArgs, methodName, route, routedUris)
    }

    private data class RoutedUri(
        val guestAuthority: String?,
        val proxyAuthority: String?
    )

    private data class RoutedArguments(
        val args: Array<Any?>,
        val operation: String,
        val route: VirtualContentServiceRoute?,
        val routedUris: List<RoutedUri>
    ) {
        fun record(success: Boolean, reason: String) {
            val currentRoute = route ?: return
            val first = routedUris.firstOrNull()
            VirtualContentServiceOperationRecorders.record(
                VirtualContentServiceOperationRecord(
                    instanceId = currentRoute.instanceId,
                    operation = operation,
                    guestAuthority = first?.guestAuthority,
                    proxyAuthority = first?.proxyAuthority,
                    processSlot = currentRoute.processSlot,
                    routedUriCount = routedUris.size,
                    success = success,
                    reason = reason
                )
            )
        }
    }
}
