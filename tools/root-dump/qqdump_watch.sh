#!/system/bin/sh
set -u

if [ "$#" -lt 3 ]; then
  echo "usage: $0 <package> <out_dir> <dump_match> [delay] [loops] [wait_match]" >&2
  exit 2
fi

pkg="$1"
out_dir="$2"
dump_match="$3"
delay="${4:-0.05}"
loops="${5:-200}"
wait_match="${6:-$dump_match}"
log="/data/local/tmp/qqdump_watch_${pkg}.log"

rm -rf "$out_dir"
mkdir -p "$out_dir"
echo "watch pkg=$pkg out=$out_dir dump_match=$dump_match wait_match=$wait_match loops=$loops delay=$delay" > "$log"

i=0
while [ "$i" -lt "$loops" ]; do
  pid="$(pidof "$pkg" 2>/dev/null | awk '{print $1}')"
  if [ -n "$pid" ]; then
    echo "attempt=$i pid=$pid" >> "$log"
    if [ "$wait_match" = "$dump_match" ]; then
      /data/local/tmp/qqmemdump "$pid" "$out_dir" "$dump_match" >> "$log" 2>&1
    else
      /data/local/tmp/qqmemdump "$pid" "$out_dir" --require "$wait_match" "$dump_match" >> "$log" 2>&1
    fi
    rc="$?"
    echo "attempt=$i rc=$rc" >> "$log"
    if [ "$rc" = "0" ]; then
      echo "success pid=$pid attempt=$i" >> "$log"
      exit 0
    fi
  fi
  sleep "$delay"
  i=$((i + 1))
done

echo "timeout" >> "$log"
exit 1
