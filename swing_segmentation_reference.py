#!/usr/bin/env python3
"""Эталонная реализация сегментации замахов (прототип для переноса в Kotlin).

Вход: CSV с ландмарками из M0 (world-координаты в метрах, начало координат в середине таза).
Запуск: python3 -I swing_segmentation_reference.py landmarks.csv
Зависимости: numpy, pandas, scipy.

Идея: считать замахи не по пикам скорости (их в одном замахе несколько), а по циклам
положения ведущего запястья вдоль главной оси его движения.
"""
import sys
import numpy as np
import pandas as pd
from scipy.signal import savgol_filter
from scipy.stats import skew

CFG = dict(
    wrist=16,            # индекс ведущего запястья (16 правое, 15 левое)
    vis_thr=0.5,         # маска по visibility
    outlier_k=0.35,      # выброс: отклонение от медианы 7 кадров, в длинах торса
    max_gap=6,           # короткие пропуски (кадры) интерполируем, длинные оставляем пустыми
    smooth_win=9,        # окно Савицкого-Голея для координаты (устойчиво в диапазоне 5-17)
    front_frac=0.6,      # замах начался: выше lo + front_frac * размах (устойчиво 0.3-0.9)
    back_frac=0.25,      # замах закончился: рука вернулась ниже lo + back_frac * размах (0.15-0.3)
    min_dur_s=0.25,
    fps=30.0,
)

def torso_length(df):
    W = lambda k: np.c_[df[f"world_{k}_x"], df[f"world_{k}_y"], df[f"world_{k}_z"]]
    return float(np.median(np.linalg.norm((W(11) + W(12)) / 2 - (W(23) + W(24)) / 2, axis=1)))

def clean_track(P, vis, torso, c=CFG):
    """P: Nx3 в метрах -> длины торса; маскируем плохие кадры и выбросы, чиним короткие дыры."""
    P = P / torso
    bad = vis < c["vis_thr"]
    half = 3
    for i in range(len(P)):
        a, b = max(0, i - half), min(len(P), i + half + 1)
        if np.linalg.norm(P[i] - np.median(P[a:b], axis=0)) > c["outlier_k"]:
            bad[i] = True
    P = P.copy(); P[bad] = np.nan
    idx = np.arange(len(P)); out = P.copy()
    for k in range(3):
        ok = ~np.isnan(P[:, k]); out[:, k] = np.interp(idx, idx[ok], P[ok, k])
    isn = np.isnan(P[:, 0]); i = 0
    while i < len(P):                      # длинные дыры возвращаем в NaN
        if isn[i]:
            j = i
            while j < len(P) and isn[j]: j += 1
            if j - i > c["max_gap"]: out[i:j] = np.nan
            i = j
        else:
            i += 1
    return out

def swing_coordinate(P, c=CFG):
    """Одна координата: проекция положения запястья на главную ось его движения."""
    ok = ~np.isnan(P[:, 0])
    X = P[ok] - P[ok].mean(axis=0)
    _, s, vt = np.linalg.svd(X, full_matrices=False)
    z = np.full(len(P), np.nan); z[ok] = X @ vt[0]
    idx = np.arange(len(z)); good = ~np.isnan(z)
    zs = savgol_filter(np.interp(idx, idx[good], z[good]), c["smooth_win"], 2)
    zs[~good] = np.nan
    if skew(zs[good]) < 0: zs = -zs        # исходное положение (где рука проводит больше времени) = низ
    return zs

def detect_swings(z, c=CFG):
    """Гистерезис: покой -> (выше front) -> замах -> (ниже back) -> покой.
    Двойной горб внутри замаха в зону покоя не возвращается, поэтому остаётся одним замахом."""
    good = ~np.isnan(z)
    lo, hi = np.percentile(z[good], [5, 95]); A = hi - lo
    f = z.copy(); last = lo
    for i in range(len(f)):                # через дыры держим последнее значение
        if np.isnan(f[i]): f[i] = last
        else: last = f[i]
    front, back = lo + c["front_frac"] * A, lo + c["back_frac"] * A
    swings, state, start, pk = [], 0, 0, 0
    for i in range(len(f)):
        if state == 0:
            if f[i] > front:
                s = i
                while s > 0 and f[s] > back: s -= 1
                start, state, pk = s, 1, i
        else:
            if f[i] > f[pk]: pk = i
            if f[i] < back:
                if (i - start) / c["fps"] >= c["min_dur_s"]: swings.append((start, pk, i))
                state = 0
    if state == 1 and (len(f) - 1 - start) / c["fps"] >= c["min_dur_s"]:
        swings.append((start, pk, len(f) - 1))
    return swings

def is_complete(sw, n, c=CFG):
    """Неполный: не виден подъём (начало на кадре 0) или пик в последние 0.3 с записи."""
    return sw[0] > 0 and sw[1] < n - 1 - int(0.3 * c["fps"])

def run(path, c=CFG):
    df = pd.read_csv(path)
    t = (df.timestamp_us.values - df.timestamp_us.values[0]) / 1e6
    k = c["wrist"]
    P = np.c_[df[f"world_{k}_x"], df[f"world_{k}_y"], df[f"world_{k}_z"]]
    P = clean_track(P, df[f"image_{k}_visibility"].values, torso_length(df), c)
    z = swing_coordinate(P, c)
    return t, z, detect_swings(z, c)

if __name__ == "__main__":
    t, z, sw = run(sys.argv[1])
    full = [s for s in sw if is_complete(s, len(z))]
    print(f"найдено {len(sw)}, полных {len(full)}")
    for a, p, b in sw:
        tag = "" if is_complete((a, p, b), len(z)) else "  (обрезан записью)"
        print(f"  начало {t[a]:5.2f}  пик {t[p]:5.2f}  конец {t[b]:5.2f} с{tag}")


# ---- признаки замаха (после сегментации) ----

def impact_moment(z, swing, win=7, c=CFG):
    """Момент удара: максимум производной координаты замаха на отрезке [начало, пик координаты].
    Не использовать максимум модуля скорости запястья по всему окну: на сбоях трекинга и на возврате
    руки он уходит на 0.3-0.7 с после пика."""
    a, p, _ = swing
    zz = np.where(np.isnan(z), np.nanmin(z), z)
    dz = savgol_filter(zz, win, 2, deriv=1, delta=1.0 / c["fps"])
    return a + int(np.argmax(dz[a:p + 1]))

def trunk_lean_3d(df):
    """Наклон корпуса: 3D-угол между вертикалью (world y) и линией середина бёдер -> середина плеч, градусы, без знака."""
    W = lambda k: np.c_[df[f"world_{k}_x"], df[f"world_{k}_y"], df[f"world_{k}_z"]]
    v = (W(11) + W(12)) / 2 - (W(23) + W(24)) / 2
    return np.degrees(np.arccos(np.clip(np.abs(v[:, 1]) / np.linalg.norm(v, axis=1), 0, 1)))
