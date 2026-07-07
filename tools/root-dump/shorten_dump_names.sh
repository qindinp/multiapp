#!/system/bin/sh
set -u

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <dump_dir>" >&2
  exit 2
fi

dir="$1"
cd "$dir" || exit 1
i=0
for f in range-*.bin; do
  [ -e "$f" ] || continue
  mv "$f" "r$i.bin" || exit 1
  i=$((i + 1))
done
ls -lh
