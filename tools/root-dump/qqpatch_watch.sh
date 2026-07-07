#!/system/bin/sh
set -u

pkg="${1:-com.qq.reader}"
log="${2:-/data/local/tmp/qqpatch_watch.log}"
loops="${3:-500}"
delay="${4:-0.005}"
if [ "$#" -gt 4 ]; then
  shift 4
else
  set --
fi

echo "watch pkg=$pkg loops=$loops delay=$delay options=$*" > "$log"
i=0
while [ "$i" -lt "$loops" ]; do
  pid="$(pidof "$pkg" 2>/dev/null | awk '{print $1}')"
  if [ -n "$pid" ]; then
    echo "pid=$pid attempt=$i" >> "$log"
    /data/local/tmp/qqmempatch "$pid" 300 2 "$@" >> "$log" 2>&1
    rc="$?"
    echo "patch rc=$rc" >> "$log"
    exit "$rc"
  fi
  sleep "$delay"
  i=$((i + 1))
done

echo "timeout" >> "$log"
exit 1
