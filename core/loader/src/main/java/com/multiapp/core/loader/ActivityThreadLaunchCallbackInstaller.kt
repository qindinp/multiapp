package com.multiapp.core.loader

import android.os.Handler
import android.os.Message
import android.util.Log

/** Installs the pre-attach ActivityThread launch-record patch callback. */
object ActivityThreadLaunchCallbackInstaller {
    private const val TAG = "ActivityLaunchCallback"

    @Volatile
    private var installedCallback: LaunchCallback? = null

    @Volatile
    private var originalCallback: Handler.Callback? = null

    fun install(): Result<ActivityThreadLaunchCallbackInstallResult> = runCatching {
        val handler = ActivityThreadCompat.mainHandler()
        val current = ActivityThreadCompat.getHandlerCallback(handler)
        if (current is LaunchCallback) {
            return@runCatching ActivityThreadLaunchCallbackInstallResult(
                installed = false,
                alreadyInstalled = true,
                previousCallbackClassName = current.delegateClassName
            )
        }

        val callback = LaunchCallback(current)
        originalCallback = current
        installedCallback = callback
        ActivityThreadCompat.setHandlerCallback(handler, callback)
        Log.i(TAG, "ActivityThread launch callback installed: previous=${current?.javaClass?.name.orEmpty()}")
        ActivityThreadLaunchCallbackInstallResult(
            installed = true,
            alreadyInstalled = false,
            previousCallbackClassName = current?.javaClass?.name
        )
    }

    fun restore(): Result<Unit> = runCatching {
        val handler = ActivityThreadCompat.mainHandler()
        val current = ActivityThreadCompat.getHandlerCallback(handler)
        if (current === installedCallback) {
            ActivityThreadCompat.setHandlerCallback(handler, originalCallback)
        }
        installedCallback = null
        originalCallback = null
        Log.i(TAG, "ActivityThread launch callback restored")
    }

    fun isInstalled(): Boolean =
        runCatching { ActivityThreadCompat.getHandlerCallback(ActivityThreadCompat.mainHandler()) is LaunchCallback }
            .getOrDefault(false)

    private class LaunchCallback(
        private val delegate: Handler.Callback?
    ) : Handler.Callback {
        val delegateClassName: String?
            get() = delegate?.javaClass?.name

        override fun handleMessage(msg: Message): Boolean {
            runCatching { ActivityThreadLaunchRecordPatcher.patchMessage(msg) }
                .onFailure { error -> Log.w(TAG, "Unable to patch ActivityThread launch record", error) }
            return delegate?.handleMessage(msg) ?: false
        }
    }
}

data class ActivityThreadLaunchCallbackInstallResult(
    val installed: Boolean,
    val alreadyInstalled: Boolean,
    val previousCallbackClassName: String? = null
)
