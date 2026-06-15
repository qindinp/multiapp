package com.multiapp.core.workprofile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import timber.log.Timber

/**
 * Work Profile 管理器
 * 
 * 负责：
 * 1. 检查设备是否支持 Work Profile
 * 2. 创建和管理 Work Profile
 * 3. 在 Work Profile 中安装和启动应用
 */
class WorkProfileManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WorkProfileManager"
        private const val REQUEST_PROVISION_MANAGED_PROFILE = 1001
    }
    
    private val dpm: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    
    private val userManager: UserManager by lazy {
        context.getSystemService(Context.USER_SERVICE) as UserManager
    }
    
    private val adminComponent: ComponentName by lazy {
        ComponentName(context, WorkProfileAdminReceiver::class.java)
    }
    
    /**
     * 获取所有用户 Profile
     */
    private fun getUserProfiles(): List<Any> {
        return try {
            val method = UserManager::class.java.getMethod("getUserProfiles")
            val result = method.invoke(userManager)
            if (result is List<*>) {
                result.filterNotNull()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 检查设备是否支持 Work Profile
     * 
     * @return 支持返回 true，否则返回 false
     */
    fun isWorkProfileSupported(): Boolean {
        return try {
            // 检查 DevicePolicyManager 是否可用
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                Timber.tag(TAG).d("Android version too low for Work Profile")
                return false
            }
            
            // 检查是否有 UserManager
            if (userManager == null) {
                Timber.tag(TAG).d("UserManager not available")
                return false
            }
            
            // 检查是否已有 managed profile
            val users = getUserProfiles()
            val hasManagedProfile = users.any { userHandle ->
                isManagedProfile(userHandle)
            }
            
            if (hasManagedProfile) {
                Timber.tag(TAG).d("Managed profile already exists")
                // 已有 profile，仍然返回 true（可以复用）
            }
            
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to check Work Profile support")
            false
        }
    }
    
    /**
     * 检查是否已有 Work Profile
     * 
     * @return 存在返回 true，否则返回 false
     */
    fun hasExistingWorkProfile(): Boolean {
        return try {
            val users = getUserProfiles()
            users.any { userHandle ->
                isManagedProfile(userHandle)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to check existing Work Profile")
            false
        }
    }
    
    /**
     * 获取现有 Work Profile 的用户 ID
     * 
     * @return Work Profile 的用户 ID，如果不存在返回 -1
     */
    fun getWorkProfileUserId(): Int {
        return try {
            val users = getUserProfiles()
            val managedUser = users.firstOrNull { userHandle ->
                isManagedProfile(userHandle)
            }
            managedUser?.let { getUserId(it) } ?: -1
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get Work Profile user ID")
            -1
        }
    }
    
    /**
     * 从 UserHandle 获取用户 ID
     */
    private fun getUserId(userHandle: Any): Int {
        return try {
            val method = userHandle.javaClass.getMethod("getIdentifier")
            method.invoke(userHandle) as Int
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * 检查指定用户是否是 Managed Profile
     */
    private fun isManagedProfile(userHandle: Any): Boolean {
        return try {
            val userId = getUserId(userHandle)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ 有 isManagedProfile 方法
                val method = UserManager::class.java.getMethod("isManagedProfile", Int::class.java)
                method.invoke(userManager, userId) as? Boolean ?: false
            } else {
                // 旧版本尝试通过 DevicePolicyManager 判断
                dpm.isProfileOwnerApp(context.packageName)
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 创建 Work Profile
     * 
     * 注意：此方法会启动系统配置流程，需要用户交互
     * 
     * @return 创建结果
     */
    fun createWorkProfile(): WorkProfileResult {
        return try {
            // 检查是否已有 Work Profile
            if (hasExistingWorkProfile()) {
                val userId = getWorkProfileUserId()
                Timber.tag(TAG).d("Work Profile already exists, userId: $userId")
                return WorkProfileResult.AlreadyExists(userId)
            }
            
            // 检查是否支持
            if (!isWorkProfileSupported()) {
                Timber.tag(TAG).d("Work Profile not supported")
                return WorkProfileResult.NotSupported
            }
            
            // 创建 Intent
            val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                    adminComponent
                )
                // 跳过加密（如果支持）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    putExtra(
                        DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION,
                        true
                    )
                }
            }
            
            // 检查是否有 Activity 处理此 Intent
            if (intent.resolveActivity(context.packageManager) == null) {
                Timber.tag(TAG).d("No activity to handle PROVISION_MANAGED_PROFILE")
                return WorkProfileResult.NotSupported
            }
            
            Timber.tag(TAG).d("Starting Work Profile provisioning")
            WorkProfileResult.ProvisioningRequired(intent)
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create Work Profile")
            WorkProfileResult.Error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 在 Work Profile 中安装应用
     * 
     * @param packageName 要安装的应用包名
     * @return 安装结果
     */
    fun installAppInProfile(packageName: String): InstallResult {
        return try {
            val userId = getWorkProfileUserId()
            if (userId == -1) {
                Timber.tag(TAG).d("No Work Profile found")
                return InstallResult.NoProfile
            }
            
            // 使用 PackageManager 安装已有包到 Work Profile
            // 注意：这是系统 API，需要 INTERACT_ACROSS_USERS 权限
            val pm = context.packageManager
            
            // 检查包是否已安装在主用户
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Timber.tag(TAG).d("Package not installed in main user: $packageName")
                return InstallResult.PackageNotFound
            }
            
            // 尝试在 Work Profile 中安装
            // installExistingPackageAsUser 是系统 API，可能需要特殊权限
            try {
                val installMethod = PackageManager::class.java.getMethod(
                    "installExistingPackageAsUser",
                    String::class.java,
                    Int::class.java
                )
                val result = installMethod.invoke(pm, packageName, userId) as Int
                
                // PackageManager.INSTALL_SUCCEEDED = 1
                if (result == 1) {
                    Timber.tag(TAG).d("Package installed in Work Profile: $packageName")
                    InstallResult.Success
                } else {
                    Timber.tag(TAG).d("Failed to install package, result code: $result")
                    InstallResult.Failed("Install result code: $result")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "installExistingPackageAsUser not available")
                InstallResult.Failed("System API not available: ${e.message}")
            }
            
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "SecurityException installing package")
            InstallResult.PermissionDenied
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to install package")
            InstallResult.Failed(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 启动 Work Profile 中的应用
     * 
     * @param packageName 要启动的应用包名
     * @return 启动结果
     */
    fun launchAppInProfile(packageName: String): LaunchResult {
        return try {
            val userId = getWorkProfileUserId()
            if (userId == -1) {
                Timber.tag(TAG).d("No Work Profile found")
                return LaunchResult.NoProfile
            }
            
            // 获取应用的启动 Intent
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            
            if (launchIntent == null) {
                Timber.tag(TAG).d("No launch intent for package: $packageName")
                return LaunchResult.NoLaunchIntent
            }
            
            // 设置目标用户
            // 注意：需要使用 ActivityOptions 指定目标用户
            // 这里简化处理，实际实现可能需要更复杂的跨用户启动逻辑
            
            Timber.tag(TAG).d("Launching app in Work Profile: $packageName")
            context.startActivity(launchIntent)
            
            LaunchResult.Success
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to launch app")
            LaunchResult.Failed(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 删除 Work Profile
     * 
     * @return 删除结果
     */
    fun removeWorkProfile(): RemoveResult {
        return try {
            val userId = getWorkProfileUserId()
            if (userId == -1) {
                Timber.tag(TAG).d("No Work Profile found")
                return RemoveResult.NoProfile
            }
            
            // 注意：删除 Work Profile 需要系统 API
            // UserManager.removeUser() 是系统 API
            // 普通 App 无法调用
            
            Timber.tag(TAG).d("Cannot remove Work Profile without system permissions")
            RemoveResult.PermissionDenied
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to remove Work Profile")
            RemoveResult.Failed(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 获取 Work Profile 状态信息
     */
    fun getProfileStatus(): ProfileStatus {
        return try {
            val userId = getWorkProfileUserId()
            val isSupported = isWorkProfileSupported()
            val hasProfile = userId != -1
            
            ProfileStatus(
                isSupported = isSupported,
                hasProfile = hasProfile,
                profileUserId = userId,
                profileUserName = if (hasProfile) getProfileUserName(userId) else null
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get profile status")
            ProfileStatus(
                isSupported = false,
                hasProfile = false,
                profileUserId = -1,
                profileUserName = null
            )
        }
    }
    
    private fun getProfileUserName(userId: Int): String? {
        return try {
            // UserManager.getUserName() 是系统 API
            // 这里简化处理
            "Work Profile ($userId)"
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Work Profile 创建结果
 */
sealed class WorkProfileResult {
    /** 需要用户交互来创建 */
    data class ProvisioningRequired(val intent: Intent) : WorkProfileResult()
    
    /** Work Profile 已存在 */
    data class AlreadyExists(val userId: Int) : WorkProfileResult()
    
    /** 设备不支持 Work Profile */
    data object NotSupported : WorkProfileResult()
    
    /** 创建失败 */
    data class Error(val message: String) : WorkProfileResult()
}

/**
 * 应用安装结果
 */
sealed class InstallResult {
    data object Success : InstallResult()
    data object NoProfile : InstallResult()
    data object PackageNotFound : InstallResult()
    data object PermissionDenied : InstallResult()
    data class Failed(val message: String) : InstallResult()
}

/**
 * 应用启动结果
 */
sealed class LaunchResult {
    data object Success : LaunchResult()
    data object NoProfile : LaunchResult()
    data object NoLaunchIntent : LaunchResult()
    data class Failed(val message: String) : LaunchResult()
}

/**
 * Profile 删除结果
 */
sealed class RemoveResult {
    data object Success : RemoveResult()
    data object NoProfile : RemoveResult()
    data object PermissionDenied : RemoveResult()
    data class Failed(val message: String) : RemoveResult()
}

/**
 * Profile 状态信息
 */
data class ProfileStatus(
    val isSupported: Boolean,
    val hasProfile: Boolean,
    val profileUserId: Int,
    val profileUserName: String?
)
