# WorkProfile module ProGuard rules

# Keep DeviceAdminReceiver
-keep class com.multiapp.core.workprofile.WorkProfileAdminReceiver { *; }

# Keep public API
-keep class com.multiapp.core.workprofile.WorkProfileManager { *; }
-keep class com.multiapp.core.workprofile.** { *; }
