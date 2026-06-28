package com.multiapp.core.identity
import com.multiapp.core.model.IdentityConfig

import android.content.ContentResolver
import android.net.Uri
import com.multiapp.core.hook.HookEngine
import timber.log.Timber

data class ProviderAuthorityHookConfig(
    val instanceId: String,
    val originalPackageName: String,
    val authorityMap: Map<String, String>
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
        Timber.d(
            "ContentProviderHook: apply provider authorities for instance=%s, authorityMap=%s",
            config.instanceId,
            config.authorityMap.keys.joinToString()
        )
        applyInternal(config.instanceId, config.authorityMap, hookEngine)
    }

    companion object {

        private const val TAG = "ContentProviderHook"

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
        ) {

            hookContentResolverQuery(hookEngine, authorityMap)
            hookContentResolverInsert(hookEngine, authorityMap)
            hookContentResolverUpdate(hookEngine, authorityMap)
            hookContentResolverDelete(hookEngine, authorityMap)
            hookContentResolverCall(hookEngine, authorityMap)
            hookContentResolverAcquireProvider(hookEngine, authorityMap)

            Timber.tag(TAG).i(
                "ContentProviderHook installed for instance=%s, %d authority mappings",
                instanceId, authorityMap.size
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
            authorityMap: Map<String, String>
        ) {
            try {
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
                        val rewrittenUri = rewriteUriAuthority(uri, authorityMap)
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
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.query()")
            }
        }

        /**
         * Hook ContentResolver.insert() to rewrite URI authorities.
         */
        private fun hookContentResolverInsert(
            hookEngine: HookEngine,
            authorityMap: Map<String, String>
        ) {
            try {
                val method = ContentResolver::class.java.getDeclaredMethod(
                    "insert",
                    Uri::class.java,
                    android.content.ContentValues::class.java
                )
                val installed = hookEngine.hookMethodPassThrough(
                    method = method,
                    beforeCallback = { _, args ->
                        val uri = args.firstOrNull() as? Uri ?: return@hookMethodPassThrough null
                        val rewrittenUri = rewriteUriAuthority(uri, authorityMap)
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
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.insert()")
            }
        }

        /**
         * Hook ContentResolver.update() to rewrite URI authorities.
         */
        private fun hookContentResolverUpdate(
            hookEngine: HookEngine,
            authorityMap: Map<String, String>
        ) {
            try {
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
                        val rewrittenUri = rewriteUriAuthority(uri, authorityMap)
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
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.update()")
            }
        }

        /**
         * Hook ContentResolver.delete() to rewrite URI authorities.
         */
        private fun hookContentResolverDelete(
            hookEngine: HookEngine,
            authorityMap: Map<String, String>
        ) {
            try {
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
                        val rewrittenUri = rewriteUriAuthority(uri, authorityMap)
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
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.delete()")
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
            authorityMap: Map<String, String>
        ) {
            try {
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
                        val rewrittenUri = rewriteUriAuthority(uri, authorityMap)
                        if (rewrittenUri != uri) {
                            Timber.tag(TAG).d("call() URI rewrite: %s -> %s", uri, rewrittenUri)
                            val newArgs = args.copyOf()
                            newArgs[0] = rewrittenUri
                            newArgs
                        } else {
                            null
                        }
                    }
                )
                Timber.tag(TAG).d("Hooked ContentResolver.call() passThrough=%s", installed)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.call()")
            }
        }

        /**
         * Hook ContentResolver.acquireProvider() to rewrite authority strings.
         *
         * acquireProvider(String) is used internally to obtain a provider reference.
         * We rewrite the authority string before it reaches the system.
         */
        private fun hookContentResolverAcquireProvider(
            hookEngine: HookEngine,
            authorityMap: Map<String, String>
        ) {
            try {
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
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.acquireProvider()")
            }

            // Also hook the Uri overload
            try {
                val method = ContentResolver::class.java.getDeclaredMethod(
                    "acquireProvider",
                    Uri::class.java
                )
                val installed = hookEngine.hookMethodPassThrough(
                    method = method,
                    beforeCallback = { _, args ->
                        val uri = args.firstOrNull() as? Uri ?: return@hookMethodPassThrough null
                        val rewrittenUri = rewriteUriAuthority(uri, authorityMap)
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
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook ContentResolver.acquireProvider(Uri)")
            }
        }

        /**
         * Rewrite a URI's authority using the provided authority map.
         *
         * If the URI's authority matches a key in the map, replace it with
         * the mapped value. Otherwise return the URI unchanged.
         */
        private fun rewriteUriAuthority(
            uri: Uri,
            authorityMap: Map<String, String>
        ): Uri {
            val authority = uri.authority ?: return uri
            val newAuthority = authorityMap[authority] ?: return uri

            return uri.buildUpon()
                .encodedAuthority(newAuthority)
                .build()
        }
    }
}
