#!/system/bin/sh
# Additional: ensure wifi is connected and get IP
IP=$(ip route get 1.1.1.1 2>/dev/null | grep -oP 'src \K\S+')
echo "$(date) auto_adb_wireless: IP=$IP port=5555" >> /data/local/tmp/auto_adb.log
