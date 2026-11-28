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
    torso_tol=0.25,      # подготовка: размер торса в кадре вне [1/1.25, 1.25] от медианы записи (устойчиво 0.2-0.4)
    min_in_pos_s=1.0,    # отрезки «на месте» короче этого — тоже подготовка (устойчиво 0.5-2 с)
    min_speed_frac=0.3,  # замах: скорость подъёма >= доли медианы по кандидатам (устойчиво 0.2-0.4)
    min_shoulder_turn=40.0,  # замах с разворотом плеч не меньше этого (иначе замечание); устойчиво 37-42
    shoulder_pre_s=1.2,      # разворот плеч ищется от этого времени до пика координаты ...
    shoulder_post_s=0.1,     # ... до этого времени после пика
    leading="right",
)

def torso_length(df):
    W = lambda k: np.c_[df[f"world_{k}_x"], df[f"world_{k}_y"], df[f"world_{k}_z"]]
    return float(np.nanmedian(np.linalg.norm((W(11) + W(12)) / 2 - (W(23) + W(24)) / 2, axis=1)))  # кадры без позы = NaN

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

def in_position(df, c=CFG):
    """Человек на рабочем расстоянии: длина торса в кадре (x-y) в пределах допуска от медианы записи,
    непрерывно не меньше min_in_pos_s. Убирает подход к телефону и уход от него в начале и в конце записи."""
    I = lambda k, a: df[f"image_{k}_{a}"].values
    torso = np.hypot((I(11, "x") + I(12, "x") - I(23, "x") - I(24, "x")) / 2,
                     (I(11, "y") + I(12, "y") - I(23, "y") - I(24, "y")) / 2)
    r = torso / np.nanmedian(torso); up = 1 + c["torso_tol"]
    ok = (~np.isnan(r)) & (r >= 1 / up) & (r <= up)
    out = ok.copy(); run = int(c["min_in_pos_s"] * c["fps"]); i = 0
    while i < len(ok):
        if ok[i]:
            j = i
            while j < len(ok) and ok[j]: j += 1
            if j - i < run: out[i:j] = False
            i = j
        else:
            i += 1
    return out

def trailing_side(df, mask, c=CFG):
    """Единичный вектор от бедра ведущей стороны к бедру ведомой (world)."""
    W = lambda k: np.c_[df[f"world_{k}_x"], df[f"world_{k}_y"], df[f"world_{k}_z"]][mask]
    v = W(23) - W(24) if c["leading"] == "right" else W(24) - W(23)
    v = v / np.linalg.norm(v, axis=1)[:, None]
    m = np.nanmean(v, axis=0)
    return m / np.linalg.norm(m)

def swing_coordinate(P, c=CFG, side=None):
    """Одна координата: проекция положения запястья на главную ось его движения."""
    ok = ~np.isnan(P[:, 0])
    X = P[ok] - P[ok].mean(axis=0)
    _, s, vt = np.linalg.svd(X, full_matrices=False)
    z = np.full(len(P), np.nan); z[ok] = X @ vt[0]
    idx = np.arange(len(z)); good = ~np.isnan(z)
    zs = savgol_filter(np.interp(idx, idx[good], z[good]), c["smooth_win"], 2)
    zs[~good] = np.nan
    # Пик координаты — проводка; при ударе справа она уходит к ведомому боку тела. Это правило верно на
    # всех размеченных роликах (косинус 0.61-0.79) и на калибровке из 2-3 замахов, где асимметрия
    # распределения (skew) ненадёжна. Skew — запасной вариант, если ось почти перпендикулярна боку.
    a = None if side is None else float(vt[0] @ side)
    if (a is not None and abs(a) >= 0.3 and a < 0) or ((a is None or abs(a) < 0.3) and skew(zs[good]) < 0):
        zs = -zs
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

def is_complete(sw, z, c=CFG):
    """Неполный: не виден подъём (начало на первом годном кадре) или пик в последние 0.3 с годных данных.
    Кадры подготовки до и после рабочего отрезка считаются вне записи."""
    good = np.flatnonzero(~np.isnan(z))
    return sw[0] > good[0] and sw[1] < good[-1] - int(0.3 * c["fps"])

def rise_speed(z, swing, win=5, c=CFG):
    """Максимальная скорость подъёма координаты от начала до пика (та же производная, что у impact_moment)."""
    a, p, _ = swing
    zz = np.where(np.isnan(z), np.nanmin(z), z)
    return float(savgol_filter(zz, win, 2, deriv=1, delta=1.0 / c["fps"])[a:p + 1].max())

def shoulder_yaw(df):
    """Поворот линии плеч в горизонтальной плоскости world x-z, градусы, без скачков через ±180°;
    кадры без позы интерполируются."""
    dx = (df["world_11_x"] - df["world_12_x"]).values
    dz = (df["world_11_z"] - df["world_12_z"]).values
    a = np.arctan2(dz, dx)
    ok = ~np.isnan(a)
    idx = np.arange(len(a))
    return np.degrees(np.interp(idx, idx[ok], np.unwrap(a[ok])))

def shoulder_turn(yaw, swing, c=CFG):
    """Размах поворота плеч вокруг замаха: от shoulder_pre_s до пика координаты до shoulder_post_s после
    него; 95-й минус 5-й перцентиль, чтобы одиночные сбои трекинга не считались. Отрезок привязан к пику,
    а не к началу окна: иначе склеенное окно из двух коротких движений (рука не вернулась в покой)
    захватывает оба и набирает лишний поворот.
    Полный удар справа начинается с разворота плеч назад; у «удара без замаха» и лёгкого маха рукой
    плечи почти не поворачиваются (30-36° против 45-130° у полных замахов на размеченных роликах)."""
    _, p, _ = swing
    seg = yaw[max(0, p - int(c["shoulder_pre_s"] * c["fps"])): min(len(yaw), p + int(c["shoulder_post_s"] * c["fps"]) + 1)]
    return float(np.percentile(seg, 95) - np.percentile(seg, 5))

def corrections(yaw, swing, c=CFG):
    """Замечания к засчитанному замаху (замах всё равно считается: это тренажёр, а не фильтр)."""
    out = []
    if shoulder_turn(yaw, swing, c) < c["min_shoulder_turn"]:
        out.append("нет разворота плеч: начни замах с разворота плеч и корпуса боком к сетке")
    return out

def classify(path, c=CFG):
    """Окна замахов и причина, по которой окно не засчитано (None — засчитано)."""
    df = pd.read_csv(path)
    t = (df.timestamp_us.values - df.timestamp_us.values[0]) / 1e6
    k = c["wrist"]
    mask = in_position(df, c)
    P = np.c_[df[f"world_{k}_x"], df[f"world_{k}_y"], df[f"world_{k}_z"]]
    P = clean_track(P, np.where(mask, df[f"image_{k}_visibility"].values, 0.0), torso_length(df), c)
    z = swing_coordinate(P, c, trailing_side(df, mask, c))
    sw = detect_swings(z, c)
    reason = {s: None if is_complete(s, z, c) else "обрезан записью/подготовкой" for s in sw}
    full = [s for s in sw if reason[s] is None]
    if full:   # медленный подъём ракетки в готовую позицию — цикл положения, но не замах
        speeds = np.array([rise_speed(z, s, c=c) for s in full])
        for s, v in zip(full, speeds):
            if v < c["min_speed_frac"] * np.median(speeds):
                reason[s] = "медленное движение"
    return t, z, sw, reason

def run(path, c=CFG):
    """Возвращает t, z, все окна и засчитанные замахи (полные, с разворотом плеч и не медленные)."""
    t, z, sw, reason = classify(path, c)
    return t, z, sw, [s for s in sw if reason[s] is None]

if __name__ == "__main__":
    t, z, sw, reason = classify(sys.argv[1])
    print(f"найдено окон {len(sw)}, засчитано {sum(r is None for r in reason.values())}")
    for s in sw:
        a, p, b = s
        tag = f"  ({reason[s]})" if reason[s] else ""
        print(f"  начало {t[a]:5.2f}  пик {t[p]:5.2f}  конец {t[b]:5.2f} с{tag}")


# ---- признаки замаха (после сегментации) ----

def impact_moment(z, swing, win=5, c=CFG):
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
