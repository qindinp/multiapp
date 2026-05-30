package com.multiapp.core.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.io.FileInputStream

/**
 * Stub APK 安装器
 * 优先使用 PackageInstaller.Session API，
 * 无安装权限时自动降级到系统安装器 Intent
 */
class StubInstaller(private val context: Context) {

    companion object {
        private const val TAG = "StubInstaller"
        private const val INSTALLER_ACTION = "com.multiapp.core.installer.INSTALL_RESULT"
    }

    /**
     * 检查是否有安装未知来源应用的权限
     */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true // API < 26 不需要此权限
        }
    }

    /**
     * 请求安装未知来源应用的权限（打开系统设置页）
     */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    sealed class InstallResult {
        data object Success : InstallResult()
        data class Error(val message: String) : InstallResult()
        data object PendingUserConfirmation : InstallResult()
    }

    /**
     * 安装 Stub APK（智能降级）
     *
     * 流程:
     * 1. 检查 REQUEST_INSTALL_PACKAGES 权限
     * 2. 有权限 → PackageInstaller.Session API（静默安装）
     * 3. 无权限 → 降级到系统安装器 Intent（用户手动确认）
     *
     * @param stubApk Stub APK 文件
     * @return 安装结果
     */
    fun install(stubApk: File): InstallResult {
        Timber.d("$TAG: installing ${stubApk.name}")

        // 检查安装权限
        if (!canInstallPackages()) {
            Timber.w("$TAG: no install permission, falling back to system installer")
            return installWithFileProvider(stubApk)
        }

        // 有权限，尝试 PackageInstaller
        val result = installWithSessionApi(stubApk)

        // 如果 Session API 被系统拦截（MIUI 等），降级到系统安装器
        if (result is InstallResult.Error) {
            Timber.w("$TAG: Session API failed (${result.message}), falling back to system installer")
            return installWithFileProvider(stubApk)
        }

        return result
    }

    /**
     * 使用 PackageInstaller.Session API 安装
     */
    private fun installWithSessionApi(stubApk: File): InstallResult {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )

            val sessionId = packageInstaller.createSession(params)
            Timber.d("$TAG: created session $sessionId")

            val session = packageInstaller.openSession(sessionId)
            try {
                writeApkToSession(session, stubApk)
                commitSession(session, sessionId)
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Session API install failed")
            InstallResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 写入 APK 数据到安装 session
     *
     * @param session PackageInstaller.Session
     * @param stubApk APK 文件
     */
    private fun writeApkToSession(session: PackageInstaller.Session, stubApk: File) {
        FileInputStream(stubApk).use { inputStream ->
            session.openWrite("stub.apk", 0, stubApk.length()).use { outputStream ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                var totalBytes: Long = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                }

                // 确保数据写入
                session.fsync(outputStream)
                Timber.d("$TAG: wrote $totalBytes bytes to session")
            }
        }
    }

    /**
     * 提交安装 session 并等待结果
     *
     * @param session PackageInstaller.Session
     * @param sessionId 会话 ID
     * @return 安装结果
     */
    private fun commitSession(session: PackageInstaller.Session, sessionId: Int): InstallResult {
        // 使用 CountDownLatch 实现同步等待广播结果
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: InstallResult = InstallResult.Error("Install timeout")

        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: Intent) {
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                )
                result = when (status) {
                    PackageInstaller.STATUS_SUCCESS -> InstallResult.Success
                    PackageInstaller.STATUS_FAILURE_ABORTED -> {
                        InstallResult.Error("Installation aborted")
                    }
                    else -> {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        InstallResult.Error(message ?: "Installation failed with status $status")
                    }
                }
                latch.countDown()
            }
        }

        // 先注册 Receiver，再提交 session，避免广播丢失
        val intentFilter = android.content.IntentFilter(INSTALLER_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, intentFilter)
        }

        try {
            // 创建结果 PendingIntent
            val intent = Intent(INSTALLER_ACTION).apply {
                setPackage(context.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                flags
            )

            // 提交 session
            session.commit(pendingIntent.intentSender)

            // 等待最多 30 秒
            val completed = latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                Timber.e("$TAG: install timeout after 30s")
                return InstallResult.Error("Installation timeout")
            }
            return result
        } catch (e: InterruptedException) {
            Timber.e(e, "$TAG: install wait interrupted")
            return InstallResult.Error("Install interrupted")
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Receiver already unregistered
            }
        }
    }

    /**
     * 使用 FileProvider URI 安装 (备用方案)
     *
     * 适用于无法使用 Session API 的场景
     *
     * @param stubApk APK 文件
     * @return 安装结果
     */
    fun installWithFileProvider(stubApk: File): InstallResult {
        Timber.d("$TAG: installing with FileProvider: ${stubApk.name}")

        return try {
            // Copy APK to the shared_apks/ subdirectory (must match file_provider_paths.xml)
            val sharedDir = File(context.cacheDir, "shared_apks")
            sharedDir.mkdirs()
            val sharedApk = File(sharedDir, stubApk.name)
            if (stubApk.absolutePath != sharedApk.absolutePath) {
                stubApk.copyTo(sharedApk, overwrite = true)
            }

            val uri = getApkUri(sharedApk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            InstallResult.PendingUserConfirmation
        } catch (e: Exception) {
            Timber.e(e, "$TAG: FileProvider install failed")
            InstallResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 获取 APK 文件的 content:// URI
     *
     * @param apkFile APK 文件
     * @return content:// URI
     */
    private fun getApkUri(apkFile: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, apkFile)
    }
}
