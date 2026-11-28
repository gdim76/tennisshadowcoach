#!/usr/bin/env bash
# Frames of a recording as one image, to check a manual count or what happened at a moment.
#   data/scripts/contact_sheet.sh data/training_123.mp4 out.png [start_s] [duration_s] [fps]
# Defaults: from 0 s, 10 s long, 2 frames per second, 10 frames per row.
set -euo pipefail
video="$1"; out="$2"; start="${3:-0}"; duration="${4:-10}"; fps="${5:-2}"
ffmpeg -v error -y -ss "$start" -i "$video" -t "$duration" \
  -vf "fps=$fps,scale=-1:220,tile=10x$(( (${duration%.*} * fps + 9) / 10 ))" -frames:v 1 "$out"
echo "$out"
