package com.multiapp.core.identity
import com.multiapp.core.model.IdentityConfig

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import com.multiapp.core.hook.HookEngine
import java.security.SecureRandom
import java.util.Base64
import timber.log.Timber

data class ProviderAuthorityHookConfig(
    val instanceId: String,
    val originalPackageName: String,
    val authorityMap: Map<String, String>
)

data class ContentProviderHookInstallStats(
    val attemptedMethodCount: Int,
    val installedMethodCount: Int
)

data class ProviderRouteToken(
    val token: String,
    val callerInstanceId: String,
    val targetInstanceId: String,
    val authority: String,
    val operation: String,
    val expiresAtMillis: Long
)

enum class ProviderRouteTokenValidationStatus {
    VALID,
    MISSING_TOKEN,
    TOKEN_NOT_FOUND,
    EXPIRED,
    CALLER_INSTANCE_MISMATCH,
    TARGET_INSTANCE_MISMATCH,
    AUTHORITY_MISMATCH,
    OPERATION_MISMATCH
}

data class ProviderRouteTokenValidationResult(
    val status: ProviderRouteTokenValidationStatus,
    val route: ProviderRouteToken? = null
) {
    val isValid: Boolean = status == ProviderRouteTokenValidationStatus.VALID
}

object ProviderRouteTokenRegistry {
    const val PROXY_ROUTE_TOKEN = "multiapp_routeToken"
    const val DEFAULT_TTL_MILLIS = 2 * 60 * 1000L

    private const val TOKEN_BYTE_COUNT = 32
    private const val MAX_ROUTE_TOKENS = 2048

    private val random = SecureRandom()
    private val tokenEncoder = Base64.getUrlEncoder().withoutPadding()
    private val lock = Any()
    private val routes = LinkedHashMap<String, ProviderRouteToken>()

    fun issue(
        callerInstanceId: String,
        targetInstanceId: String,
        authority: String,
        operation: String,
        nowMillis: Long = System.currentTimeMillis(),
        ttlMillis: Long = DEFAULT_TTL_MILLIS
    ): ProviderRouteToken {
        require(callerInstanceId.isNotBlank()) { "callerInstanceId must not be blank" }
        require(targetInstanceId.isNotBlank()) { "targetInstanceId must not be blank" }
        require(authority.isNotBlank()) { "authority must not be blank" }
        val normalizedOperation = normalizeOperation(operation)
        require(normalizedOperation.isNotBlank()) { "operation must not be blank" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }

        synchronized(lock) {
            pruneExpiredLocked(nowMillis)
            val token = nextTokenLocked()
            val route = ProviderRouteToken(
                token = token,
                callerInstanceId = callerInstanceId,
                targetInstanceId = targetInstanceId,
                authority = authority,
                operation = normalizedOperation,
                expiresAtMillis = nowMillis + ttlMillis
            )
            routes[token] = route
            trimLocked()
            return route
        }
    }

    fun validate(
        token: String?,
        callerInstanceId: String,
        targetInstanceId: String,
        authority: String,
        operation: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ProviderRouteTokenValidationResult {
        if (token.isNullOrBlank()) {
            return ProviderRouteTokenValidationResult(ProviderRouteTokenValidationStatus.MISSING_TOKEN)
        }
        val normalizedOperation = normalizeOperation(operation)
        synchronized(lock) {
            val route = routes[token]
                ?: return ProviderRouteTokenValidationResult(ProviderRouteTokenValidationStatus.TOKEN_NOT_FOUND)
            if (nowMillis >= route.expiresAtMillis) {
                routes.remove(token)
                return ProviderRouteTokenValidationResult(ProviderRouteTokenValidationStatus.EXPIRED)
            }
            pruneExpiredLocked(nowMillis)
            return when {
                route.callerInstanceId != callerInstanceId -> ProviderRouteTokenValidationResult(
                    ProviderRouteTokenValidationStatus.CALLER_INSTANCE_MISMATCH,
                    route
                )
                route.targetInstanceId != targetInstanceId -> ProviderRouteTokenValidationResult(
                    ProviderRouteTokenValidationStatus.TARGET_INSTANCE_MISMATCH,
                    route
                )
                route.authority != authority -> ProviderRouteTokenValidationResult(
                    ProviderRouteTokenValidationStatus.AUTHORITY_MISMATCH,
                    route
                )
                route.operation != normalizedOperation -> ProviderRouteTokenValidationResult(
                    ProviderRouteTokenValidationStatus.OPERATION_MISMATCH,
                    route
                )
                else -> ProviderRouteTokenValidationResult(ProviderRouteTokenValidationStatus.VALID, route)
            }
        }
    }

    fun normalizeOperation(operation: String): String {
        return when (val base = operation.substringBefore(":")) {
            "openFileDescriptor" -> "openFile"
            "openAssetFileDescriptor" -> "openAssetFile"
            "openTypedAssetFileDescriptor" -> "openTypedAssetFile"
            else -> base
        }
    }

    internal fun clearForTest() {
        synchronized(lock) {
            routes.clear()
        }
    }

    private fun nextTokenLocked(): String {
        val bytes = ByteArray(TOKEN_BYTE_COUNT)
        while (true) {
            random.nextBytes(bytes)
            val token = tokenEncoder.encodeToString(bytes)
            if (!routes.containsKey(token)) return token
        }
    }

    private fun pruneExpiredLocked(nowMillis: Long) {
        val iterator = routes.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMillis >= iterator.next().value.expiresAtMillis) {
                iterator.remove()
            }
        }
    }

    private fun trimLocked() {
        val iterator = routes.entries.iterator()
        while (routes.size > MAX_ROUTE_TOKENS && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}

/**
 * ContentProvider authority hook.
 *
 * Phase 4: Remaps ContentProvider authorities so each cloned instance
 * registers its providers under unique authorities, avoiding conflicts
 * when multiple instances of the same app coexist on a single device.
 *
 * Hook points:
 * 1. ContentResolver.query() URI authority rewrite
 * 2. ContentResolver.insert() URI authority rewrite
 * 3. ContentResolver.update() URI authority rewrite
 * 4. ContentResolver.delete() URI authority rewrite
 * 5. ContentResolver.call() authority rewrite
 * 6. ContentResolver.acquireProvider() authority rewrite
 * 7. ContentResolver file/notify/observer URI authority rewrite
 * 8. Context URI permission authority rewrite
 */
class ContentProviderHook : HookPoint {

    override fun apply(config: IdentityConfig, hookEngine: HookEngine) {
        Timber.d(
            "ContentProviderHook: apply called for instance=%s, authorityMap=%s",
            config.instanceId,
            config.authorityMap.keys.joinToString()
        )
        apply(
            ProviderAuthorityHookConfig(
                instanceId = config.instanceId,
                originalPackageName = config.originalPackageName,
                authorityMap = config.authorityMap
            ),
            hookEngine
        )
    }

    fun apply(config: ProviderAuthorityHookConfig, hookEngine: HookEngine) {
        install(config, hookEngine)
    }

    fun install(config: ProviderAuthorityHookConfig, hookEngine: HookEngine): ContentProviderHookInstallStats {
        Timber.d(
            "ContentProviderHook: apply provider authorities for instance=%s, authorityMap=%s",
            config.instanceId,
            config.authorityMap.keys.joinToString()
        )
        return applyInternal(config.instanceId, config.authorityMap, hookEngine)
    }

    companion object {

        private const val TAG = "ContentProviderHook"
        internal const val PROXY_INSTANCE_ID = "multiapp_instanceId"
        internal const val PROXY_GUEST_AUTHORITY = "multiapp_guestAuthority"
        internal const val PROXY_ROUTE_TOKEN = ProviderRouteTokenRegistry.PROXY_ROUTE_TOKEN

        private val proxyParameterNames = setOf(
            PROXY_INSTANCE_ID,
            PROXY_GUEST_AUTHORITY,
            PROXY_ROUTE_TOKEN
        )

        fun apply(config: IdentityConfig) {
            Timber.d(
                "ContentProviderHook: companion apply called for instance=%s",
                config.instanceId
            )
            ContentProviderHook().apply(config, HookEngine.getInstance())
        }

        fun apply(config: ProviderAuthorityHookConfig) {
            ContentProviderHook().apply(config, HookEngine.getInstance())
        }

        private fun applyInternal(
            instanceId: String,
            authorityMap: Map<String, String>,
            hookEngine: HookEngine
        ): ContentProviderHookInstallStats {
            val hookResults = mutableListOf<Boolean>()

            hookResults += hookContentResolverQuery(hookEngine, instanceId, authorityMap)
            hookResults += hookContentResolverInsert(hookEngine, instanceId, authorityMap)
            hookResults += hookContentResolverUpdate(hookEngine, instanceId, authorityMap)
            hookResults += hookContentResolverDelete(hookEngine, instanceId, authorityMap)
            hookResults += hookContentResolverCall(hookEngine, instanceId, authorityMap)
            hookResults += hookContentResolverAcquireProvider(hookEngine, instanceId, authorityMap)
            hookResults += hookContentResolverModernCrud(hookEngine, instanceId, authorityMap)
            hookResults += hookContentResolverBulkInsert(hookEngine, instanceId, authorityMap)
            hookResults += hookContentResolverFileOpen(hookEngine, instanceId, authorityMap)
            hookResults += hookContentResolverNotifyChange(hookEngine, instanceId, authorityMap)
            hookResults += hookContentResolverContentObserver(hookEngine, instanceId, authorityMap)
            hookResults += hookContentResolverCanonicalize(hookEngine, instanceId, authorityMap)
            hookResults += hookContextUriPermissions(hookEngine, instanceId, authorityMap)

            Timber.tag(TAG).i(
                "ContentProviderHook installed for instance=%s, %d authority mappings, installedMethods=%d/%d",
                instanceId, authorityMap.size, hookResults.count { it }, hookResults.size
            )
            return ContentProviderHookInstallStats(
                attemptedMethodCount = hookResults.size,
                installedMethodCount = hookResults.count { it }
            )
        }

        /**
         * Hook ContentResolver.query() to rewrite URI authorities.
         *
         * The query method signature is:
         *   query(Uri, String[], String, String[], String)
         * We intercept the first parameter (Uri) and rewrite its authority.
         */
        private fun hookContentResolverQuery(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            return try {
                val method = ContentResolver::class.java.getDeclaredMethod(
                    "query",
                    Uri::class.java,
                    Array<String>::class.java,
                    String::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                val installed = hookEngine.hookMethodPassThrough(
                    method = method,
                    beforeCallback = { _, args ->
                        val uri = args.firstOrNull() as? Uri ?: return@hookMethodPassThrough null
                        val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap, "query")
                        if (rewrittenUri != uri) {
                            Timber.tag(TAG).d("query() URI rewrite: %s -> %s", uri, rewrittenUri)
                            val newArgs = args.copyOf()
                            newArgs[0] = rewrittenUri
                            newArgs
                        } else {
                            null
                        }
                    }
                )
                Timber.tag(TAG).d("Hooked ContentResolver.query() passThrough=%s", installed)
                installed
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.query()")
                false
            }
        }

        /**
         * Hook ContentResolver.insert() to rewrite URI authorities.
         */
        private fun hookContentResolverInsert(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            return try {
                val method = ContentResolver::class.java.getDeclaredMethod(
                    "insert",
                    Uri::class.java,
                    android.content.ContentValues::class.java
                )
                val installed = hookEngine.hookMethodPassThrough(
                    method = method,
                    beforeCallback = { _, args ->
                        val uri = args.firstOrNull() as? Uri ?: return@hookMethodPassThrough null
                        val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap, "insert")
                        if (rewrittenUri != uri) {
                            Timber.tag(TAG).d("insert() URI rewrite: %s -> %s", uri, rewrittenUri)
                            val newArgs = args.copyOf()
                            newArgs[0] = rewrittenUri
                            newArgs
                        } else {
                            null
                        }
                    }
                )
                Timber.tag(TAG).d("Hooked ContentResolver.insert() passThrough=%s", installed)
                installed
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.insert()")
                false
            }
        }

        /**
         * Hook ContentResolver.update() to rewrite URI authorities.
         */
        private fun hookContentResolverUpdate(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            return try {
                val method = ContentResolver::class.java.getDeclaredMethod(
                    "update",
                    Uri::class.java,
                    android.content.ContentValues::class.java,
                    String::class.java,
                    Array<String>::class.java
                )
                val installed = hookEngine.hookMethodPassThrough(
                    method = method,
                    beforeCallback = { _, args ->
                        val uri = args.firstOrNull() as? Uri ?: return@hookMethodPassThrough null
                        val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap, "update")
                        if (rewrittenUri != uri) {
                            Timber.tag(TAG).d("update() URI rewrite: %s -> %s", uri, rewrittenUri)
                            val newArgs = args.copyOf()
                            newArgs[0] = rewrittenUri
                            newArgs
                        } else {
                            null
                        }
                    }
                )
                Timber.tag(TAG).d("Hooked ContentResolver.update() passThrough=%s", installed)
                installed
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.update()")
                false
            }
        }

        /**
         * Hook ContentResolver.delete() to rewrite URI authorities.
         */
        private fun hookContentResolverDelete(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            return try {
                val method = ContentResolver::class.java.getDeclaredMethod(
                    "delete",
                    Uri::class.java,
                    String::class.java,
                    Array<String>::class.java
                )
                val installed = hookEngine.hookMethodPassThrough(
                    method = method,
                    beforeCallback = { _, args ->
                        val uri = args.firstOrNull() as? Uri ?: return@hookMethodPassThrough null
                        val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap, "delete")
                        if (rewrittenUri != uri) {
                            Timber.tag(TAG).d("delete() URI rewrite: %s -> %s", uri, rewrittenUri)
                            val newArgs = args.copyOf()
                            newArgs[0] = rewrittenUri
                            newArgs
                        } else {
                            null
                        }
                    }
                )
                Timber.tag(TAG).d("Hooked ContentResolver.delete() passThrough=%s", installed)
                installed
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.delete()")
                false
            }
        }

        /**
         * Hook ContentResolver.call() to rewrite URI authorities.
         *
         * call() has multiple overloads; we hook the 4-arg version:
         *   call(Uri, String, String, Bundle)
         */
        private fun hookContentResolverCall(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            val hookResults = mutableListOf<Boolean>()
            hookResults += try {
                val method = ContentResolver::class.java.getDeclaredMethod(
                    "call",
                    Uri::class.java,
                    String::class.java,
                    String::class.java,
                    android.os.Bundle::class.java
                )
                val installed = hookEngine.hookMethodPassThrough(
                    method = method,
                    beforeCallback = { _, args ->
                        val uri = args.firstOrNull() as? Uri ?: return@hookMethodPassThrough null
                        val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap, "call")
                        if (rewrittenUri != uri) {
                            Timber.tag(TAG).d("call() URI rewrite: %s -> %s", uri, rewrittenUri)
                            val newArgs = args.copyOf()
                            newArgs[0] = rewrittenUri
                            newArgs[3] = routeExtras(
                                original = args.getOrNull(3) as? Bundle,
                                instanceId = instanceId,
                                guestAuthority = uri.authority.orEmpty(),
                                operation = "call",
                                routeToken = rewrittenUri.getQueryParameter(PROXY_ROUTE_TOKEN)
                            )
                            newArgs
                        } else {
                            null
                        }
                    }
                )
                Timber.tag(TAG).d("Hooked ContentResolver.call() passThrough=%s", installed)
                installed
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.call()")
                false
            }

            hookResults += try {
                val method = ContentResolver::class.java.getDeclaredMethod(
                    "call",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Bundle::class.java
                )
                val installed = hookEngine.hookMethodPassThrough(
                    method = method,
                    beforeCallback = { _, args ->
                        val authority = args.firstOrNull() as? String
                            ?: return@hookMethodPassThrough null
                        val rewritten = authorityMap[authority] ?: return@hookMethodPassThrough null
                        Timber.tag(TAG).d(
                            "call(authority) rewrite: %s -> %s",
                            authority, rewritten
                        )
                        val newArgs = args.copyOf()
                        newArgs[0] = rewritten
                        newArgs[3] = routeExtras(
                            original = args.getOrNull(3) as? Bundle,
                            instanceId = instanceId,
                            guestAuthority = authority,
                            operation = "call"
                        )
                        newArgs
                    }
                )
                Timber.tag(TAG).d("Hooked ContentResolver.call(String) passThrough=%s", installed)
                installed
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.call(String)")
                false
            }
            return hookResults.any { it }
        }

        /**
         * Hook ContentResolver.acquireProvider() to rewrite authority strings.
         *
         * acquireProvider(String) is used internally to obtain a provider reference.
         * We rewrite the authority string before it reaches the system.
         */
        private fun hookContentResolverAcquireProvider(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            val hookResults = mutableListOf<Boolean>()
            hookResults += try {
                val method = ContentResolver::class.java.getDeclaredMethod(
                    "acquireProvider",
                    String::class.java
                )
                val installed = hookEngine.hookMethodPassThrough(
                    method = method,
                    beforeCallback = { _, args ->
                        val authority = args.firstOrNull() as? String
                            ?: return@hookMethodPassThrough null
                        val rewritten = authorityMap[authority]
                        if (rewritten != null) {
                            Timber.tag(TAG).d(
                                "acquireProvider() authority rewrite: %s -> %s",
                                authority, rewritten
                            )
                            arrayOf<Any?>(rewritten)
                        } else {
                            null
                        }
                    }
                )
                Timber.tag(TAG).d("Hooked ContentResolver.acquireProvider(String) passThrough=%s", installed)
                installed
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.acquireProvider()")
                false
            }

            // Also hook the Uri overload
            hookResults += try {
                val method = ContentResolver::class.java.getDeclaredMethod(
                    "acquireProvider",
                    Uri::class.java
                )
                val installed = hookEngine.hookMethodPassThrough(
                    method = method,
                    beforeCallback = { _, args ->
                        val uri = args.firstOrNull() as? Uri ?: return@hookMethodPassThrough null
                        val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap, "acquireProvider")
                        if (rewrittenUri != uri) {
                            Timber.tag(TAG).d(
                                "acquireProvider(Uri) rewrite: %s -> %s",
                                uri, rewrittenUri
                            )
                            arrayOf<Any?>(rewrittenUri)
                        } else {
                            null
                        }
                    }
                )
                Timber.tag(TAG).d("Hooked ContentResolver.acquireProvider(Uri) passThrough=%s", installed)
                installed
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.acquireProvider(Uri)")
                false
            }
            return hookResults.any { it }
        }

        private fun hookContentResolverModernCrud(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            val hookResults = listOf(
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "query",
                    arrayOf(Uri::class.java, Array<String>::class.java, Bundle::class.java, CancellationSignal::class.java),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "insert",
                    arrayOf(Uri::class.java, ContentValues::class.java, Bundle::class.java),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "update",
                    arrayOf(Uri::class.java, ContentValues::class.java, Bundle::class.java),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "delete",
                    arrayOf(Uri::class.java, Bundle::class.java),
                    0,
                    instanceId,
                    authorityMap
                )
            )
            return hookResults.any { it }
        }

        private fun hookContentResolverBulkInsert(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean =
            hookUriArgumentMethod(
                hookEngine,
                ContentResolver::class.java,
                "bulkInsert",
                arrayOf(Uri::class.java, Array<ContentValues>::class.java),
                0,
                instanceId,
                authorityMap
            )

        private fun hookContentResolverFileOpen(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            val hookResults = listOf(
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "openFileDescriptor",
                    arrayOf(Uri::class.java, String::class.java),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "openFileDescriptor",
                    arrayOf(Uri::class.java, String::class.java, CancellationSignal::class.java),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "openAssetFileDescriptor",
                    arrayOf(Uri::class.java, String::class.java),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "openAssetFileDescriptor",
                    arrayOf(Uri::class.java, String::class.java, CancellationSignal::class.java),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "openTypedAssetFileDescriptor",
                    arrayOf(Uri::class.java, String::class.java, Bundle::class.java),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "openTypedAssetFileDescriptor",
                    arrayOf(Uri::class.java, String::class.java, Bundle::class.java, CancellationSignal::class.java),
                    0,
                    instanceId,
                    authorityMap
                )
            )
            return hookResults.any { it }
        }

        private fun hookContentResolverNotifyChange(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            val hookResults = listOf(
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "notifyChange",
                    arrayOf(Uri::class.java, ContentObserver::class.java),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "notifyChange",
                    arrayOf(Uri::class.java, ContentObserver::class.java, java.lang.Boolean.TYPE),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "notifyChange",
                    arrayOf(Uri::class.java, ContentObserver::class.java, Integer.TYPE),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriCollectionArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "notifyChange",
                    arrayOf(Collection::class.java, ContentObserver::class.java, Integer.TYPE),
                    0,
                    instanceId,
                    authorityMap
                )
            )
            return hookResults.any { it }
        }

        private fun hookContentResolverContentObserver(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            val hookResults = listOf(
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "registerContentObserver",
                    arrayOf(Uri::class.java, java.lang.Boolean.TYPE, ContentObserver::class.java),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "registerContentObserver",
                    arrayOf(Uri::class.java, java.lang.Boolean.TYPE, ContentObserver::class.java, Integer.TYPE),
                    0,
                    instanceId,
                    authorityMap
                )
            )
            return hookResults.any { it }
        }

        private fun hookContentResolverCanonicalize(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            val hookResults = listOf(
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "canonicalize",
                    arrayOf(Uri::class.java),
                    0,
                    instanceId,
                    authorityMap
                ),
                hookUriArgumentMethod(
                    hookEngine,
                    ContentResolver::class.java,
                    "uncanonicalize",
                    arrayOf(Uri::class.java),
                    0,
                    instanceId,
                    authorityMap
                )
            )
            return hookResults.any { it }
        }

        private fun hookContextUriPermissions(
            hookEngine: HookEngine,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            val hookResults = mutableListOf<Boolean>()
            listOf(Context::class.java, ContextWrapper::class.java).forEach { owner ->
                hookResults += hookUriArgumentMethod(
                    hookEngine,
                    owner,
                    "grantUriPermission",
                    arrayOf(String::class.java, Uri::class.java, Integer.TYPE),
                    1,
                    instanceId,
                    authorityMap
                )
                hookResults += hookUriArgumentMethod(
                    hookEngine,
                    owner,
                    "revokeUriPermission",
                    arrayOf(Uri::class.java, Integer.TYPE),
                    0,
                    instanceId,
                    authorityMap
                )
                hookResults += hookUriArgumentMethod(
                    hookEngine,
                    owner,
                    "revokeUriPermission",
                    arrayOf(String::class.java, Uri::class.java, Integer.TYPE),
                    1,
                    instanceId,
                    authorityMap
                )
            }
            return hookResults.any { it }
        }

        private fun hookUriArgumentMethod(
            hookEngine: HookEngine,
            ownerClass: Class<*>,
            methodName: String,
            parameterTypes: Array<Class<*>>,
            uriArgIndex: Int,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            return try {
                val method = ownerClass.getDeclaredMethod(methodName, *parameterTypes)
                val installed = hookEngine.hookMethodPassThrough(
                    method = method,
                    beforeCallback = { _, args ->
                        rewriteUriArgument(args, uriArgIndex, instanceId, authorityMap, methodName)
                    }
                )
                Timber.tag(TAG).d(
                    "Hooked %s.%s passThrough=%s",
                    ownerClass.simpleName,
                    methodName,
                    installed
                )
                installed
            } catch (e: NoSuchMethodException) {
                Timber.tag(TAG).d(
                    "Skipped missing %s.%s overload",
                    ownerClass.simpleName,
                    methodName
                )
                false
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to hook ${ownerClass.simpleName}.$methodName")
                false
            }
        }

        private fun hookUriCollectionArgumentMethod(
            hookEngine: HookEngine,
            ownerClass: Class<*>,
            methodName: String,
            parameterTypes: Array<Class<*>>,
            uriArgIndex: Int,
            instanceId: String,
            authorityMap: Map<String, String>
        ): Boolean {
            return try {
                val method = ownerClass.getDeclaredMethod(methodName, *parameterTypes)
                val installed = hookEngine.hookMethodPassThrough(
                    method = method,
                    beforeCallback = { _, args ->
                        rewriteUriCollectionArgument(args, uriArgIndex, instanceId, authorityMap, methodName)
                    }
                )
                Timber.tag(TAG).d(
                    "Hooked %s.%s(Collection) passThrough=%s",
                    ownerClass.simpleName,
                    methodName,
                    installed
                )
                installed
            } catch (e: NoSuchMethodException) {
                Timber.tag(TAG).d(
                    "Skipped missing %s.%s(Collection) overload",
                    ownerClass.simpleName,
                    methodName
                )
                false
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Failed to hook ${ownerClass.simpleName}.$methodName(Collection)")
                false
            }
        }

        private fun rewriteUriArgument(
            args: Array<Any?>,
            uriArgIndex: Int,
            instanceId: String,
            authorityMap: Map<String, String>,
            operationName: String
        ): Array<Any?>? {
            return try {
                val uri = args.getOrNull(uriArgIndex) as? Uri ?: return null
                val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap, operationName)
                if (rewrittenUri == uri) return null
                Timber.tag(TAG).d("%s URI rewrite: %s -> %s", operationName, uri, rewrittenUri)
                val newArgs = args.copyOf()
                newArgs[uriArgIndex] = rewrittenUri
                newArgs
            } catch (error: Throwable) {
                Timber.tag(TAG).w(error, "Provider URI rewrite failed for %s", operationName)
                null
            }
        }

        private fun rewriteUriCollectionArgument(
            args: Array<Any?>,
            uriArgIndex: Int,
            instanceId: String,
            authorityMap: Map<String, String>,
            operationName: String
        ): Array<Any?>? {
            return try {
                val uris = args.getOrNull(uriArgIndex) as? Collection<*> ?: return null
                var changed = false
                val rewritten = uris.map { item ->
                    val uri = item as? Uri ?: return@map item
                    val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap, operationName)
                    if (rewrittenUri != uri) changed = true
                    rewrittenUri
                }
                if (!changed) return null
                Timber.tag(TAG).d("%s URI collection rewrite: size=%d", operationName, rewritten.size)
                val newArgs = args.copyOf()
                newArgs[uriArgIndex] = rewritten
                newArgs
            } catch (error: Throwable) {
                Timber.tag(TAG).w(error, "Provider URI collection rewrite failed for %s", operationName)
                null
            }
        }

        private fun routeExtras(
            original: Bundle?,
            instanceId: String,
            guestAuthority: String,
            operation: String,
            routeToken: String? = null
        ): Bundle = Bundle(original ?: Bundle()).apply {
            val token = routeToken ?: ProviderRouteTokenRegistry.issue(
                callerInstanceId = instanceId,
                targetInstanceId = instanceId,
                authority = guestAuthority,
                operation = operation
            ).token
            putString(PROXY_INSTANCE_ID, instanceId)
            putString(PROXY_GUEST_AUTHORITY, guestAuthority)
            putString(PROXY_ROUTE_TOKEN, token)
        }

        /**
         * Rewrite a URI's authority using the provided authority map.
         *
         * If the URI's authority matches a key in the map, replace it with
         * the mapped value. Otherwise return the URI unchanged.
         */
        internal fun rewriteUriForProviderHook(
            uri: Uri,
            instanceId: String,
            authorityMap: Map<String, String>,
            operation: String = "query"
        ): Uri {
            val authority = uri.authority ?: return uri
            val newAuthority = authorityMap[authority] ?: return uri
            val routeToken = ProviderRouteTokenRegistry.issue(
                callerInstanceId = instanceId,
                targetInstanceId = instanceId,
                authority = authority,
                operation = operation
            ).token

            return uri.buildUpon()
                .encodedAuthority(newAuthority)
                .encodedQuery(
                    rewriteEncodedQueryForProviderHook(
                        encodedQuery = uri.encodedQuery,
                        instanceId = instanceId,
                        guestAuthority = authority,
                        routeToken = routeToken
                    )
                )
                .build()
        }

        internal fun rewriteEncodedQueryForProviderHook(
            encodedQuery: String?,
            instanceId: String,
            guestAuthority: String,
            routeToken: String? = null
        ): String {
            val routeParameters = listOfNotNull(
                "$PROXY_INSTANCE_ID=$instanceId",
                "$PROXY_GUEST_AUTHORITY=$guestAuthority",
                routeToken?.let { "$PROXY_ROUTE_TOKEN=$it" }
            )
            val remaining = rewriteEncodedQueryWithoutProxyParameters(encodedQuery)
            return (remaining?.split("&").orEmpty() + routeParameters).joinToString("&")
        }

        internal fun rewriteEncodedQueryWithoutProxyParameters(encodedQuery: String?): String? {
            if (encodedQuery.isNullOrEmpty()) return null
            val remaining = encodedQuery
                .split("&")
                .filterNot { part -> proxyParameterNames.contains(part.substringBefore("=")) }
            return remaining.takeIf { it.isNotEmpty() }?.joinToString("&")
        }
    }
}
