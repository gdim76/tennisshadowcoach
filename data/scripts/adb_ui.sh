#!/usr/bin/env bash
# Drives the app on the connected phone for diagnostics (no taps outside the app are intended).
#   data/scripts/adb_ui.sh dump                 # visible texts with their bounds
#   data/scripts/adb_ui.sh tap "Анализировать"  # tap the first element whose text contains this
#   data/scripts/adb_ui.sh screenshot out.png   # screenshot of the phone
#   data/scripts/adb_ui.sh start                # (re)start the app
# Note: uiautomator dump does not work while a camera preview is animating (mirror screen).
set -euo pipefail

TMP="${TMPDIR:-/tmp}/tennis_adb_ui.xml"

dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$TMP" >/dev/null 2>&1
  python3 - "$TMP" <<'EOF'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="([^"]+)"', s):
    if m.group(1):
        print(m.group(1)[:80], m.group(2))
EOF
}

case "${1:-}" in
  dump) dump ;;
  tap)
    bounds=$(dump | grep -F -- "$2" | head -1 | grep -o '\[[0-9,]*\]\[[0-9,]*\]$' || true)
    [[ -z "$bounds" ]] && { echo "не найдено: $2" >&2; exit 1; }
    read -r x y < <(python3 -c "import re,sys;a=list(map(int,re.findall(r'\d+',sys.argv[1])));print((a[0]+a[2])//2,(a[1]+a[3])//2)" "$bounds")
    adb shell input tap "$x" "$y" ;;
  screenshot) adb exec-out screencap -p > "${2:-screen.png}" ;;
  start) adb shell am start -S -n com.tennisshadowcoach/.MainActivity >/dev/null ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac
