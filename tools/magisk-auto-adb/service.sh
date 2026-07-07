#!/system/bin/sh
# Wait for boot to complete
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done
sleep 3

# Enable wireless debugging (Android 11+)
settings put global adb_wifi_enabled 1 2>/dev/null
settings put global development_settings_enabled 1 2>/dev/null

# Set persistent ADB TCP port
resetprop persist.adb.tcp.port 5555
resetprop service.adb.tcp.port 5555

# Restart adbd to apply
stop adbd
sleep 1
start adbd

# Log result
echo "$(date) auto_adb_wireless: ADB wireless enabled, port=5555" >> /data/local/tmp/auto_adb.log
