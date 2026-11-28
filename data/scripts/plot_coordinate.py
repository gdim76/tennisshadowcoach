#!/usr/bin/env python3
"""Plots the swing coordinate of landmark CSVs with thresholds and found windows.

    python3 -I data/scripts/plot_coordinate.py out.png data/landmarks_123.csv [more.csv ...]

Orange: counted swings; grey: windows not counted (cut by recording/setup or too slow).
The second panel shows the image torso size relative to the median: setup frames (walking to
and from the phone) are far from 1 and are excluded from the analysis.
"""
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, ROOT)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import matplotlib  # noqa: E402
matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import swing_segmentation_reference as R  # noqa: E402


def main(out, paths):
    fig, axes = plt.subplots(len(paths), 1, figsize=(16, 3.4 * len(paths)), squeeze=False)
    for ax, path in zip(axes[:, 0], paths):
        df = pd.read_csv(path)
        t, z, windows, counted = R.run(path)
        good = ~np.isnan(z)
        lo, hi = np.percentile(z[good], [5, 95])
        a = hi - lo
        ax.plot(t, (z - lo) / a, lw=.8, label="координата замаха")
        ax.axhline(R.CFG["front_frac"], color="r", lw=.5, ls="--")
        ax.axhline(R.CFG["back_frac"], color="g", lw=.5, ls="--")
        for s in windows:
            ax.axvspan(t[s[0]], t[s[2]], color="orange" if s in counted else "grey", alpha=.2)
        I = lambda k, c: df[f"image_{k}_{c}"].values
        torso = np.hypot((I(11, "x") + I(12, "x") - I(23, "x") - I(24, "x")) / 2,
                         (I(11, "y") + I(12, "y") - I(23, "y") - I(24, "y")) / 2)
        ax.plot(t, torso / np.nanmedian(torso) - 1.5, lw=.6, color="purple", label="торс/медиана − 1.5")
        ax.set_ylim(-1.2, 1.6)
        ax.set_title(f"{os.path.basename(path)}: засчитано {len(counted)}")
        ax.legend(loc="upper right", fontsize=7)
    plt.tight_layout()
    plt.savefig(out, dpi=70)
    print(out)


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2:])
