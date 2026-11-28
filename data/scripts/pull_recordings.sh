#!/usr/bin/env bash
# Copies recordings, analysis exports and live mirror sessions from the connected phone into
# data/ (git-ignored).
# Existing files are kept; only missing ones are pulled (a local copy renamed with a
# label suffix, e.g. training_123-4-5-front.mp4, counts as present).
#   data/scripts/pull_recordings.sh            # everything new
#   data/scripts/pull_recordings.sh --list     # show what is on the phone
set -euo pipefail

PACKAGE=com.tennisshadowcoach
REMOTE=/sdcard/Android/data/$PACKAGE/files/TennisShadowCoach
LOCAL="$(cd "$(dirname "$0")/.." && pwd)"   # data/

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
    training_*.mp4|landmarks_*.csv|landmarks_*.metadata.json|swings_landmarks_*.csv|live_*.csv) ;;
    *) continue ;;
  esac
  # A landmark CSV is complete only once the analysis has written its metadata sidecar.
  if [[ "$name" == landmarks_*.csv ]] && ! grep -qx "${name%.csv}.metadata.json" <<<"$files"; then
    echo "пропущен $name: анализ ещё не закончен"
    continue
  fi
  # A live session is complete once the mirror was closed: its events file has a "finish" line.
  if [[ "$name" == live_* ]]; then
    session="${name%%.*}"
    if ! adb shell grep -q ",finish," "$REMOTE/$session.events.csv" 2>/dev/null; then
      echo "пропущен $name: «Зеркало» ещё открыто"
      continue
    fi
  fi
  # Local copies may carry a label suffix (training_123-4-5-front.mp4): match by stem.
  stem="${name%%.*}"; ext="${name#*.}"
  if compgen -G "$LOCAL/$stem*.$ext" >/dev/null; then continue; fi
  adb pull "$REMOTE/$name" "$LOCAL/$name" >/dev/null 2>&1
  echo "скачан $name"
  pulled=$((pulled + 1))
done
echo "Новых файлов: $pulled → $LOCAL"
