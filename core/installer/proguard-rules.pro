# ============================================================================
# core:installer — PackageInstaller session API, BroadcastReceiver callback
# ============================================================================

# StubInstaller — uses PackageInstaller.Session API + BroadcastReceiver
-keep class com.multiapp.core.installer.StubInstaller { *; }
-keep class com.multiapp.core.installer.StubInstaller$* { *; }

# ShizukuInstaller — optional installer
-keep class com.multiapp.core.installer.ShizukuInstaller { *; }
