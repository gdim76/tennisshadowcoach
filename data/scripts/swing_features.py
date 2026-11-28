#!/usr/bin/env python3
"""Per-swing features that may tell a full swing (with a backswing) from an incomplete one.

    python3 -I data/scripts/swing_features.py file.csv [more.csv ...]

For every window found by the reference segmentation:
  rise      coordinate peak above the rest level, torso lengths (absolute, not relative to the clip)
  back      how far the wrist went below the rest level before the rise (backswing), torso lengths
  sh_turn   shoulder-line yaw range (world x-z plane, 5th-95th percentile) from 1 s before the
            start to the end of the window, °
  sep       the same range for the shoulder-to-hip separation, °
"""
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, ROOT)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import swing_segmentation_reference as R  # noqa: E402


def yaw(df, left, right):
    """Line yaw in the world x-z plane, unwrapped; frames without pose are interpolated."""
    dx = (df[f"world_{left}_x"] - df[f"world_{right}_x"]).values
    dz = (df[f"world_{left}_z"] - df[f"world_{right}_z"]).values
    a = np.arctan2(dz, dx)
    ok = ~np.isnan(a)
    idx = np.arange(len(a))
    a = np.interp(idx, idx[ok], np.unwrap(a[ok]))
    return np.degrees(a)


def spread(values):
    """Robust range: 95th minus 5th percentile (single-frame tracking jumps do not count)."""
    return np.nanpercentile(values, 95) - np.nanpercentile(values, 5)


def features(path):
    df = pd.read_csv(path)
    t, z, windows, counted = R.run(path)
    k = R.CFG["wrist"]
    P = np.c_[df[f"world_{k}_x"], df[f"world_{k}_y"], df[f"world_{k}_z"]]
    torso = R.torso_length(df)
    # absolute coordinate in torso lengths: same projection, without percentile normalisation
    good = ~np.isnan(z)
    rest = np.nanmedian(z[good][z[good] < np.nanpercentile(z[good], 40)])
    sh = yaw(df, 11, 12)
    hp = yaw(df, 23, 24)
    fps = R.CFG["fps"]
    rows = []
    for s in windows:
        a, p, b = s
        pre = max(0, a - int(fps))
        rise = z[p] - rest
        back = rest - np.nanmin(z[pre:p + 1])
        span = slice(pre, b + 1)
        sh_turn = spread(sh[span])
        sep = spread((sh - hp)[span])
        rows.append((t[p], s in counted, rise, back, sh_turn, sep))
    return rows


if __name__ == "__main__":
    for path in sys.argv[1:]:
        print(os.path.basename(path))
        print("   пик,с  засч  rise   back  sh_turn   sep")
        for tp, c, rise, back, sh_turn, sep in features(path):
            print(f"  {tp:6.2f}  {'да ' if c else 'нет'}  {rise:5.2f}  {back:5.2f}  {sh_turn:6.1f}  {sep:6.1f}")
