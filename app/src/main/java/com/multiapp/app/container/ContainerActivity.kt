package com.multiapp.app.container

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.multiapp.core.loader.HostedRuntimeBootstrap
import com.multiapp.core.loader.VirtualContextWrapper
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.io.File

/**
 * Fixed host entry point for v2 hosted containers.
 *
 * Launched internally by MultiApp with an [EXTRA_INSTANCE_ID] to identify
 * which app-instance to host. Does NOT generate stub APKs, does NOT run a
 * full Virtual AMS, and does NOT touch QQ-Reader hooks or LSPlant/Xposed.
 *
 * On launch, bootstraps the hosted runtime via [HostedRuntimeBootstrap],
 * creates a [VirtualContextWrapper] for the guest app, and attempts to
 * instantiate the guest [android.app.Application] class.
 */
class ContainerActivity : Activity() {

    companion object {
        private const val TAG = "ContainerActivity"

        /** Key for the hosted-instance identifier in intent extras. */
        const val EXTRA_INSTANCE_ID = "multiapp.instanceId"

        /** Optional key for tracking the install origin. */
        const val EXTRA_INSTALL_ORIGIN = "multiapp.installOrigin"

        /**
         * Build a launch [Intent] for [ContainerActivity].
         *
         * @param context  used to create the explicit intent
         * @param instanceId  required identifier for the hosted instance
         * @param installOrigin  optional origin tag for analytics / tracking
         */
        fun createIntent(
            context: Context,
            instanceId: String,
            installOrigin: String? = null
        ): Intent {
            return Intent(context, ContainerActivity::class.java)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
                .putExtra(EXTRA_INSTALL_ORIGIN, installOrigin)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
        if (instanceId.isNullOrBlank()) {
            Log.e(TAG, "No instanceId in intent extras")
            finish()
            return
        }

        val installOrigin = intent.getStringExtra(EXTRA_INSTALL_ORIGIN)
        Log.i(TAG, "Container launch started: instanceId=$instanceId, installOrigin=$installOrigin")

        // 1. Create persistence dependencies
        val instanceStore = JsonInstanceRecordStore(getInstanceStoreDir())
        val installStore = JsonInstallRecordStore(getInstallStoreDir())
        val instanceManager: InstanceManager = DefaultInstanceManager(instanceStore, getDataRootDir())

        // 2. Run bootstrap
        val bootstrap = HostedRuntimeBootstrap(instanceManager, installStore)
        val result = bootstrap.run(instanceId)

        if (!result.success) {
            Log.e(TAG, "Bootstrap failed for instanceId=$instanceId: ${result.summary.failureReason}")
            return
        }

        val guestClassLoader = result.guestClassLoader
        if (guestClassLoader == null) {
            Log.e(TAG, "Bootstrap succeeded but guestClassLoader is null for instanceId=$instanceId")
            return
        }

        // 3. Create VirtualContextWrapper for the guest app
        val instance = instanceManager.getInstance(instanceId)
        val guestContext = VirtualContextWrapper(
            base = this,
            config = VirtualContextConfig(
                instanceId = instanceId,
                originPackageName = result.originPackageName ?: "",
                virtualPackageName = instance?.virtualPackageName ?: "",
                dataDir = instance?.dataRoot ?: "",
                sourceDir = result.originApkPath ?: "",
                nativeLibraryDir = null,
                classLoader = guestClassLoader
            ),
            guestClassLoader = guestClassLoader
        )

        // 4. Attempt to load guest Application class
        val appClassName = resolveApplicationClassName(guestClassLoader, result.originApkPath)
        if (appClassName != null) {
            try {
                val appClass = guestClassLoader.loadClass(appClassName)
                val app = appClass.getDeclaredConstructor().newInstance() as android.app.Application
                val attachMethod = android.app.Application::class.java
                    .getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
                attachMethod.isAccessible = true
                attachMethod.invoke(app, guestContext)
                Log.i(TAG, "Guest Application created: $appClassName")
            } catch (e: Throwable) {
                Log.w(TAG, "Guest Application creation failed: ${e.message}")
            }
        }

        Log.i(TAG, "Container launch complete: instanceId=$instanceId, success=${result.success}")
    }

    /**
     * Resolve the guest Application class name from the APK manifest.
     *
     * Returns null if no custom Application class is declared, which means
     * the default [android.app.Application] will be used.
     */
    private fun resolveApplicationClassName(classLoader: ClassLoader, apkPath: String?): String? {
        // Phase 1: return null -- the guest Application lifecycle is deferred.
        // Phase 2 will parse the manifest to extract the application class name.
        return null
    }

    /** Directory for [JsonInstanceRecordStore] persistence. */
    private fun getInstanceStoreDir(): File =
        File(filesDir, "instances").apply { mkdirs() }

    /** Directory for [JsonInstallRecordStore] persistence. */
    private fun getInstallStoreDir(): File =
        File(filesDir, "installs").apply { mkdirs() }

    /** Base directory for instance data roots. */
    private fun getDataRootDir(): File =
        File(filesDir, "data").apply { mkdirs() }
}
