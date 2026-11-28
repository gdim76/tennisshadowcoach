#!/usr/bin/env python3
"""Runs the reference segmentation (swing_segmentation_reference.py) on landmark CSVs.

Without arguments it checks every labelled recording in app/src/test/resources (metadata with
expected_swings): count at defaults and across the M1 matrix front_frac × back_frac × window.
With CSV paths it prints the swing windows of those files (manual count taken from their
metadata when present).

    python3 -I data/scripts/check_segmentation.py
    python3 -I data/scripts/check_segmentation.py data/landmarks_123.csv

Needs numpy, pandas, scipy (data/scripts/requirements.txt).
"""
import glob
import itertools
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, ROOT)
import swing_segmentation_reference as R  # noqa: E402

# front_frac 0.4–0.7 (owner's decision 2026-11-28, see SPEC section 5)
MATRIX = list(itertools.product((.4, .5, .6, .7), (.15, .2, .25, .3), (5, 9, 17)))


def expected(csv_path):
    meta = csv_path[:-4] + ".metadata.json"
    if not os.path.exists(meta):
        return None
    return json.load(open(meta, encoding="utf-8")).get("expected_swings")


def show(path):
    t, z, windows, reason = R.classify(path)
    yaw = R.shoulder_yaw(__import__("pandas").read_csv(path))
    exp = expected(path)
    counted = sum(r is None for r in reason.values())
    print(f"{os.path.basename(path)}: засчитано {counted}" + (f", ручной счёт {exp}" if exp else ""))
    for s in windows:
        a, p, b = s
        tag = f"  (не засчитан: {reason[s]})" if reason[s] else "".join(f"  [{n}]" for n in R.corrections(yaw, s))
        print(f"  начало {t[a]:5.2f}  пик {t[p]:5.2f}  конец {t[b]:5.2f} с  плечи {R.shoulder_turn(yaw, s):5.1f}°{tag}")


def matrix():
    total = 0
    for path in sorted(glob.glob(os.path.join(ROOT, "app/src/test/resources/*/landmarks_*.csv"))):
        exp = expected(path)
        if exp is None:
            continue
        meta = json.load(open(path[:-4] + ".metadata.json", encoding="utf-8"))
        default = len(R.run(path)[3])
        if meta.get("matrix_check") is False:
            print(f"{os.path.basename(path)}: ожидается {exp}, по умолчанию {default}, матрица не проверяется "
                  f"({meta.get('matrix_check_note', 'matrix_check=false')[:60]}…)")
            total += default != exp
            continue
        bad = []
        for front, back, win in MATRIX:
            c = dict(R.CFG, front_frac=front, back_frac=back, smooth_win=win)
            n = len(R.run(path, c)[3])
            if n != exp:
                bad.append((front, back, win, n))
        total += len(bad)
        print(f"{os.path.basename(path)}: ожидается {exp}, по умолчанию {default}, "
              f"расхождений в матрице {len(bad)}/{len(MATRIX)}")
        for front, back, win, n in bad[:5]:
            print(f"    front={front} back={back} окно={win} → {n}")
    print(f"Всего расхождений: {total}")
    return total


if __name__ == "__main__":
    if len(sys.argv) > 1:
        for p in sys.argv[1:]:
            show(p)
    else:
        sys.exit(1 if matrix() else 0)
