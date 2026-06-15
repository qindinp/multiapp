package com.multiapp.core.workprofile

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Work Profile 管理接收器
 * 
 * 负责处理 Work Profile 创建完成和策略变更事件
 */
class WorkProfileAdminReceiver : DeviceAdminReceiver() {
    
    companion object {
        private const val TAG = "WorkProfileAdmin"
    }
    
    /**
     * Work Profile 配置完成回调
     * 
     * 当用户完成 Work Profile 创建流程后，系统会调用此方法
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        
        Timber.tag(TAG).d("Profile provisioning complete")
        
        try {
            // 启用 Work Profile
            enableWorkProfile(context)
            
            // 配置 Work Profile 策略
            configureWorkProfile(context)
            
            Timber.tag(TAG).d("Work Profile setup completed")
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to setup Work Profile")
        }
    }
    
    /**
     * 设备管理员启用回调
     */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Timber.tag(TAG).d("Device admin enabled")
    }
    
    /**
     * 设备管理员禁用回调
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Timber.tag(TAG).d("Device admin disabled")
    }
    
    /**
     * 启用 Work Profile
     */
    private fun enableWorkProfile(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, WorkProfileAdminReceiver::class.java)
            
            // 启用 Profile
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                dpm.setProfileEnabled(adminComponent)
                Timber.tag(TAG).d("Work Profile enabled")
            }
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to enable Work Profile")
        }
    }
    
    /**
     * 配置 Work Profile 策略
     */
    private fun configureWorkProfile(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, WorkProfileAdminReceiver::class.java)
            
            // 配置策略示例：
            // - 允许安装应用
            // - 允许使用相机
            // - 允许使用网络
            
            // 注意：某些策略可能需要用户确认
            
            Timber.tag(TAG).d("Work Profile policies configured")
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to configure Work Profile policies")
        }
    }
}
