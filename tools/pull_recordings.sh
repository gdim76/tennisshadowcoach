#!/usr/bin/env bash
# Copies recordings and analysis exports from the connected phone into data/ (git-ignored).
# Existing files are kept; only missing ones are pulled (a local copy renamed with a
# label suffix, e.g. training_123-4-5-front.mp4, counts as present).
#   tools/pull_recordings.sh            # everything new
#   tools/pull_recordings.sh --list     # show what is on the phone
set -euo pipefail

PACKAGE=com.tennisshadowcoach
REMOTE=/sdcard/Android/data/$PACKAGE/files/TennisShadowCoach
LOCAL="$(cd "$(dirname "$0")/.." && pwd)/data"

if ! adb get-state >/dev/null 2>&1; then
  echo "Телефон не подключён по adb (проверь кабель и разрешение отладки по USB)." >&2
  exit 1
fi

files=$(adb shell ls "$REMOTE" | tr -d '\r')
if [[ "${1:-}" == "--list" ]]; then
  adb shell ls -l "$REMOTE"
  exit 0
fi

mkdir -p "$LOCAL"
pulled=0
for name in $files; do
  case "$name" in
    training_*.mp4|landmarks_*.csv|landmarks_*.metadata.json|swings_landmarks_*.csv) ;;
    *) continue ;;
  esac
  # Local copies may carry a label suffix (training_123-4-5-front.mp4): match by stem.
  stem="${name%%.*}"; ext="${name#*.}"
  if compgen -G "$LOCAL/$stem*.$ext" >/dev/null; then continue; fi
  adb pull "$REMOTE/$name" "$LOCAL/$name" >/dev/null 2>&1
  echo "скачан $name"
  pulled=$((pulled + 1))
done
echo "Новых файлов: $pulled → $LOCAL"
