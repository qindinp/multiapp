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

        private val proxyParameterNames = setOf(
            PROXY_INSTANCE_ID,
            PROXY_GUEST_AUTHORITY
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
                        val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap)
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
                        val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap)
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
                        val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap)
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
                        val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap)
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
                        val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap)
                        if (rewrittenUri != uri) {
                            Timber.tag(TAG).d("call() URI rewrite: %s -> %s", uri, rewrittenUri)
                            val newArgs = args.copyOf()
                            newArgs[0] = rewrittenUri
                            newArgs[3] = routeExtras(args.getOrNull(3) as? Bundle, instanceId, uri.authority.orEmpty())
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
                        newArgs[3] = routeExtras(args.getOrNull(3) as? Bundle, instanceId, authority)
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
                        val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap)
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
                val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap)
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
                    val rewrittenUri = rewriteUriForProviderHook(uri, instanceId, authorityMap)
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
            guestAuthority: String
        ): Bundle = Bundle(original ?: Bundle()).apply {
            putString(PROXY_INSTANCE_ID, instanceId)
            putString(PROXY_GUEST_AUTHORITY, guestAuthority)
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
            authorityMap: Map<String, String>
        ): Uri {
            val authority = uri.authority ?: return uri
            val newAuthority = authorityMap[authority] ?: return uri

            return uri.buildUpon()
                .encodedAuthority(newAuthority)
                .encodedQuery(rewriteEncodedQueryForProviderHook(uri.encodedQuery, instanceId, authority))
                .build()
        }

        internal fun rewriteEncodedQueryForProviderHook(
            encodedQuery: String?,
            instanceId: String,
            guestAuthority: String
        ): String {
            val routeParameters = listOf(
                "$PROXY_INSTANCE_ID=$instanceId",
                "$PROXY_GUEST_AUTHORITY=$guestAuthority"
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
