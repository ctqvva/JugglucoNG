#!/usr/bin/env python3
"""Can a person's carb ratio and insulin sensitivity be estimated from CGM and treatment logs?

Backs the numbers in docs/insulin-coefficients-plan.md. Reads the public AZT1D and HUPA-UCM
datasets (CC BY 4.0, downloaded separately, never committed) and writes nothing but a report.
Needs numpy only.

    python3 tools/coefficient_feasibility.py \
        --azt1d '/path/AZT1D 2025/CGM Records' \
        --hupa '/path/HUPA-UCM Diabetes Dataset'

Everything is retrospective. In the held-out studies training days come before the days
scored, and every input comes from the training days alone: fitted values, pump settings read
back from boluses, daily insulin and usual basal. The model is the app's own
arithmetic: glucose moves by -ISF x insulin acted + (ISF / ICR) x carbs absorbed, with carbs
absorbed on the app's smoothstep at 35 g/h. Only the insulin curve differs, because the
datasets do not say which insulin was used; --peak sets it.
"""
import argparse
import bisect
import csv
import datetime as dt
import math
import os
import re
import sys
from collections import defaultdict

import numpy as np

STEP = 5  # minutes per bin
DEFAULT_ICR, DEFAULT_ISF = 10.0, 54.0  # the app's defaults
ICR_RANGE, ISF_RANGE = (3.0, 30.0), (10.0, 180.0)  # the app's limits
CARB_G_PER_H = 35.0
WINDOW_MIN = 240
TRAIN_FRACTION = 0.7
MIN_MEALS = 15  # scored or trained on fewer than this and a person says nothing


def insulin_curve(peak=75.0, duration=360.0):
    """Exponential activity model (the oref0 form), as a fraction of the dose per bin."""
    tau = peak * (1 - peak / duration) / (1 - 2 * peak / duration)
    a = 2 * tau / duration
    s = 1 / (1 - a + (1 + a) * math.exp(-duration / tau))
    t = np.arange(0, duration + STEP, STEP, dtype=float)
    activity = (s / tau**2) * t * (1 - t / duration) * np.exp(-t / tau)
    return activity / activity.sum()


def remaining_fraction(minutes, peak=75.0, duration=300.0):
    """Insulin on board after `minutes`, on the pump's 5 h action."""
    if minutes >= duration:
        return 0.0
    tau = peak * (1 - peak / duration) / (1 - 2 * peak / duration)
    a = 2 * tau / duration
    s = 1 / (1 - a + (1 + a) * math.exp(-duration / tau))
    m = minutes
    return 1 - s * (1 - a) * ((m * m / (tau * duration * (1 - a)) - m / tau - 1) * math.exp(-m / tau) + 1)


def carb_curve(grams):
    """The app's meal model: smoothstep over grams / 35 g/h, as a fraction per bin."""
    minutes = min(max(grams / CARB_G_PER_H * 60.0, 30.0), 360.0)
    x = np.clip(np.arange(0, minutes + 2 * STEP, STEP) / minutes, 0, 1)
    return np.diff(x * x * (3 - 2 * x))


class Person:
    def __init__(self, name, kind):
        self.name, self.kind = name, kind
        self.readings = {}  # bin -> mg/dL
        self.bolus = defaultdict(float)  # bin -> U
        self.carbs = defaultdict(float)  # bin -> g
        self.basal = {}  # bin -> U/h
        self.food_ratio = []  # (bin, carbs / food insulin), as the pump computed it
        self.correction_bins = set()
        self.daily_insulin = defaultdict(float)  # day -> U
        self.daily_basal = {}
        self.pump_corrections = []  # (bin, units, glucose): boluses the pump's calculator sized

    def carb_ratio(self, before=None):
        """The pump's carb ratio as read from boluses before bin `before`, or None."""
        values = [r for b, r in self.food_ratio if before is None or b < before]
        return float(np.median(values)) if values else None

    def pump_correction_factor(self, before=None):
        """The correction factor the pump was set to, read back from what it delivered.

        A Tandem bolus sizes its correction as (glucose - target) / factor - insulin on board,
        so each user-started correction gives (glucose - 110) / (correction + IOB). Automatic
        boluses use a forecast and a 60 % share, and are left out. A median, with its spread.
        """
        doses = sorted(self.bolus.items())
        values = []
        for b, units, glucose in self.pump_corrections:
            if before is not None and b >= before:
                continue
            on_board = sum(u * remaining_fraction((b - d) * STEP) for d, u in doses if 0 < b - d < 60)
            values.append((glucose - 110.0) / (units + on_board))
        if len(values) < 8:
            return None
        q1, median, q3 = np.percentile(values, [25, 50, 75])
        return float(median) if (q3 - q1) / median < 0.6 else None

    def build(self, peak):
        bins = sorted(self.readings)
        self.first = bins[0]
        n = bins[-1] - self.first + 1
        self.g = np.full(n, np.nan)
        for b, v in self.readings.items():
            self.g[b - self.first] = v
        insulin = np.zeros(n)
        for b, u in self.bolus.items():
            if 0 <= b - self.first < n:
                insulin[b - self.first] += u
        # Basal away from the person's usual rate is insulin the loop added or held back.
        # "Usual" comes from the training days, so scored days cannot shape the features.
        cut = self.first + int(n * TRAIN_FRACTION)
        usual = [r for b, r in self.basal.items() if b < cut and r > 0]  # suspensions are not the usual rate
        self.median_basal = float(np.median(usual)) if usual else 0.0
        if usual:
            rate, last = self.median_basal, -10**9
            sorted_basal = dict(sorted(self.basal.items()))
            for i in range(n):
                b = i + self.first
                if b in sorted_basal:
                    rate, last = sorted_basal[b], b
                if b - last <= 12:  # a rate is trusted for an hour
                    insulin[i] += (rate - self.median_basal) * STEP / 60.0
        self.curve = insulin_curve(peak)
        self.acted = np.convolve(insulin, self.curve)[:n]
        absorbed = np.zeros(n)
        for b, grams in self.carbs.items():
            i = b - self.first
            if 0 <= i < n and grams > 0:
                curve = carb_curve(grams) * grams
                end = min(n, i + len(curve))
                absorbed[i:end] += curve[: end - i]
        self.absorbed = absorbed
        self.cum_acted = np.concatenate([[0], np.cumsum(self.acted)])
        self.cum_absorbed = np.concatenate([[0], np.cumsum(self.absorbed)])
        self.meal_bins = sorted(b - self.first for b, grams in self.carbs.items()
                                if grams >= 5 and 0 <= b - self.first < n)

    def value(self, i, tolerance=2):
        """Reading at bin i, or the nearest within the tolerance (15-minute sensors)."""
        for offset in range(tolerance + 1):
            for j in (i - offset, i + offset):
                if 0 <= j < len(self.g) and not math.isnan(self.g[j]):
                    return self.g[j]
        return math.nan

    def window(self, i, bins):
        start, end = self.value(i), self.value(i + bins)
        if math.isnan(start) or math.isnan(end):
            return None
        return (end - start,
                self.cum_acted[i + bins] - self.cum_acted[i],
                self.cum_absorbed[i + bins] - self.cum_absorbed[i],
                bins * STEP / 60.0, start, i, self.slope_before(i))

    def slope_before(self, i):
        """mg/dL per minute over the half hour before bin i; only the past is read."""
        then = self.value(i - 6)
        now = self.value(i)
        return 0.0 if math.isnan(then) or math.isnan(now) else (now - then) / 30.0

    def known_effect(self, bins):
        """What treatments logged by bin i will still do in the next `bins` bins.

        Uses only doses and meals at or before i, each followed along its own curve.
        """
        events_insulin = sorted((b - self.first, u) for b, u in self.bolus.items())
        events_carbs = sorted((b - self.first, g) for b, g in self.carbs.items() if g > 0)
        curve = self.curve
        cum = np.concatenate([[0], np.cumsum(curve)])

        def fraction(age, length):
            lo = min(max(age, 0), len(cum) - 1)
            hi = min(max(age + length, 0), len(cum) - 1)
            return cum[hi] - cum[lo]

        def effect(i):
            acted = absorbed = 0.0
            for b, u in events_insulin[max(0, bisect.bisect_left(events_insulin, (i - 80,))):
                                       bisect.bisect_right(events_insulin, (i, math.inf))]:
                acted += u * fraction(i - b, bins)
            for b, g in events_carbs[max(0, bisect.bisect_left(events_carbs, (i - 80,))):
                                     bisect.bisect_right(events_carbs, (i, math.inf))]:
                c = carb_curve(g)
                cc = np.concatenate([[0], np.cumsum(c)])
                lo, hi = min(i - b, len(cc) - 1), min(i - b + bins, len(cc) - 1)
                absorbed += g * (cc[hi] - cc[lo])
            return acted, absorbed
        return effect

    def meal_windows(self, minutes=WINDOW_MIN, clear_before=120):
        """Windows from a meal start, with no other meal shortly before or inside them."""
        bins, before = minutes // STEP, clear_before // STEP
        out, meals = [], self.meal_bins
        for index, i in enumerate(meals):
            if index and i - meals[index - 1] < before:
                continue
            if index + 1 < len(meals) and meals[index + 1] - i < bins:
                continue
            if i + bins >= len(self.g):
                continue
            w = self.window(i, bins)
            if w:
                out.append(w)
        return out

    def intervals(self, minutes):
        bins = minutes // STEP
        return [w for i in range(0, len(self.g) - bins, bins) if (w := self.window(i, bins))]

    def tdd(self, before=None):
        """Median daily insulin from whole days ending by bin `before`, basal included."""
        boluses = self.daily_values(self.daily_insulin, before)
        if not boluses:
            return math.nan
        return float(np.median(boluses)) + self.basal_per_day(before, boluses)

    @staticmethod
    def daily_values(by_day, before):
        return [v for d, v in by_day.items() if before is None or (d + 1) * 288 <= before]

    def basal_per_day(self, before, boluses):
        rates = [r for b, r in self.basal.items() if (before is None or b < before) and r > 0]
        if rates:
            return float(np.median(rates)) * 24
        long_acting = self.daily_values(self.daily_basal, before)
        if long_acting:
            return float(np.median(long_acting))
        return float(np.median(boluses))  # basal unknown: assume half of the day's insulin


def load_azt1d(root):
    people = []
    for directory, _, files in sorted(os.walk(root)):
        for name in sorted(f for f in files if f.endswith(".csv")):
            person = Person(name[:-4], "pump")
            seen = set()
            last_cgm = 0.0
            with open(os.path.join(directory, name), newline="") as handle:
                for row in csv.DictReader(handle):
                    try:
                        when = dt.datetime.strptime(row["EventDateTime"], "%Y-%m-%d %H:%M:%S")
                    except (KeyError, ValueError):
                        continue
                    b = int(when.replace(tzinfo=dt.timezone.utc).timestamp() // (STEP * 60))

                    def number(column):
                        try:
                            return float(row.get(column) or 0)
                        except ValueError:
                            return 0.0

                    # One subject's file names this column differently, as BenchLoaders knows.
                    cgm, basal = number("CGM") or number("Readings (CGM / BGM)"), number("Basal")
                    has_basal = bool((row.get("Basal") or "").strip())
                    total, food = number("TotalBolusInsulinDelivered"), number("FoodDelivered")
                    grams, correction = number("CarbSize"), number("CorrectionDelivered")
                    if has_basal and 0 <= basal < 10:  # 0 is a suspended pump; 1910 is nonsense
                        person.basal[b] = basal
                    if total > 0 or grams > 0:
                        key = (b, total, grams)
                        if key in seen:  # some subjects repeat a bolus row verbatim
                            continue
                        seen.add(key)
                        if total > 0:
                            person.bolus[b] += total
                            person.daily_insulin[b // 288] += total
                        if grams >= 5:
                            person.carbs[b] += grams
                        if food > 0 and grams >= 5:
                            person.food_ratio.append((b, grams / food))
                        if correction > 0 and grams < 5:
                            person.correction_bins.add(b)
                        bolus_type = row.get("BolusType") or ""
                        # The calculator's glucose is on the bolus row; the sample before it stands in when absent.
                        glucose = cgm if cgm > 0.1 else last_cgm
                        if correction > 0.2 and "Automatic" not in bolus_type and glucose >= 150:
                            person.pump_corrections.append((b, correction, glucose))
                    elif cgm > 0.1:
                        person.readings.setdefault(b, cgm)
                    if cgm > 0.1:
                        last_cgm = cgm
            if len(person.readings) >= 240:
                people.append(person)
    return people


HUPA_DATES = ["%Y/%m/%d %H:%M", "%d/%m/%y %H:%M", "%d-%m-%Y %H:%M", "%d/%m/%Y %H:%M"]


def load_hupa(root):
    raw = os.path.join(root, "Raw_Data")
    raw = raw if os.path.isdir(raw) else root
    people = []
    for name in sorted(os.listdir(raw)):
        path = os.path.join(raw, name)
        if not os.path.isdir(path):
            continue
        # Pump users' boluses live in exports the benchmark does not parse either.
        if any("pump" in d and os.path.isdir(os.path.join(path, d)) for d in os.listdir(path)):
            continue
        person = Person(name, "mdi")
        long_acting = defaultdict(float)
        sensor_dir = os.path.join(path, "free_style_sensor")
        for file in sorted(os.listdir(sensor_dir)) if os.path.isdir(sensor_dir) else []:
            if not file.endswith(".csv"):
                continue
            with open(os.path.join(sensor_dir, file), encoding="utf-8", errors="replace") as handle:
                lines = handle.read().splitlines()
            head = next((i for i, l in enumerate(lines) if "Tipo de registro" in l), None)
            if head is None:
                continue
            delimiter = max(";\t,", key=lines[head].count)
            rows = csv.reader(lines[head:], delimiter=delimiter)
            header = [c.strip() for c in next(rows)]

            def col(test):
                return next((i for i, c in enumerate(header) if test(c)), -1)

            c_time = col(lambda c: c == "Hora" or c.startswith("Sello de tiempo"))
            c_type = col(lambda c: c == "Tipo de registro")
            c_hist = col(lambda c: c.startswith("Hist"))
            c_rapid = col(lambda c: "rápida (unidades)" in c)
            c_long = col(lambda c: "lenta (unidades)" in c)
            c_grams = col(lambda c: "Carbohidratos (gramos)" in c)
            c_portions = col(lambda c: "Carbohidratos (raciones)" in c or "Carbohidratos (porciones)" in c)
            if min(c_time, c_type, c_hist) < 0:
                continue

            def number(row, c):
                if c < 0 or c >= len(row):
                    return None
                try:
                    return float(row[c].strip().replace(",", "."))
                except ValueError:
                    return None

            for row in rows:
                if c_time >= len(row) or not row[c_time].strip():
                    continue
                when = None
                for fmt in HUPA_DATES:
                    try:
                        when = dt.datetime.strptime(row[c_time].strip(), fmt)
                        break
                    except ValueError:
                        pass
                if when is None:
                    continue
                b = int(when.replace(tzinfo=dt.timezone.utc).timestamp() // (STEP * 60))
                if c_type < len(row) and row[c_type].strip() == "0":
                    value = number(row, c_hist)
                    if value and value > 0:
                        person.readings.setdefault(b, value)
                    continue
                rapid, slow = number(row, c_rapid), number(row, c_long)
                grams = number(row, c_grams) or ((number(row, c_portions) or 0) * 10)
                if rapid and rapid > 0:
                    person.bolus[b] += rapid
                    person.daily_insulin[b // 288] += rapid
                    if not (grams and grams >= 5):
                        person.correction_bins.add(b)
                if slow and slow > 0:
                    long_acting[b // 288] += slow
                if grams and grams >= 5:
                    person.carbs[b] += grams
        person.daily_basal = dict(long_acting)
        if len(person.readings) >= 240:
            people.append(person)
    return people


def design(windows):
    w = np.array([x[:4] for x in windows], dtype=float)
    return w[:, 0], w[:, 1:4]  # change; insulin acted, carbs absorbed, hours


def baseline(windows):
    """What the CGM alone says at the start of a window: its level and its slope."""
    w = np.array([(x[4], x[6]) for x in windows], dtype=float)
    return w


def fit_two_stage(windows, isf):
    """ISF held; carbs and a CGM baseline fitted. Sidesteps the insulin/carb collinearity."""
    y, x = design(windows)
    b = baseline(windows)
    target = y + isf * x[:, 0]
    a = np.column_stack([x[:, 1], x[:, 2], b[:, 0] - 120.0, b[:, 1]])
    beta, *_ = np.linalg.lstsq(a, target, rcond=None)
    return beta


def saved_values(isf, csf):
    """What the app would hold: both numbers inside its limits, carb slope as ISF / ICR."""
    isf = min(max(isf, ISF_RANGE[0]), ISF_RANGE[1])
    icr = min(max(isf / csf, ICR_RANGE[0]), ICR_RANGE[1]) if csf > 0 else ICR_RANGE[1]
    return isf, isf / icr


def carb_slope_alone(windows, isf):
    """ISF held, carbs fitted with nothing else: the slope the app's arithmetic can use."""
    y, x = design(windows)
    target = y + isf * x[:, 0]
    return float(x[:, 1] @ target / (x[:, 1] @ x[:, 1]))


def fit_baseline_only(windows):
    y, x = design(windows)
    b = baseline(windows)
    a = np.column_stack([x[:, 2], b[:, 0] - 120.0, b[:, 1]])
    beta, *_ = np.linalg.lstsq(a, y, rcond=None)
    return beta


def predict_baseline_only(windows, beta):
    _, x = design(windows)
    b = baseline(windows)
    return np.column_stack([x[:, 2], b[:, 0] - 120.0, b[:, 1]]) @ beta


def fit_full(windows):
    """Insulin, carbs and the CGM baseline, all free."""
    y, x = design(windows)
    b = baseline(windows)
    a = np.column_stack([-x[:, 0], x[:, 1], x[:, 2], b[:, 0] - 120.0, b[:, 1]])
    beta, *_ = np.linalg.lstsq(a, y, rcond=None)
    return beta


def predict_full(windows, beta):
    _, x = design(windows)
    b = baseline(windows)
    return np.column_stack([-x[:, 0], x[:, 1], x[:, 2], b[:, 0] - 120.0, b[:, 1]]) @ beta


def predict(x, isf, csf, drift=0.0):
    return -isf * x[:, 0] + csf * x[:, 1] + drift * x[:, 2]


def fit_ols(y, x):
    """Free fit: change = -ISF x acted + CSF x absorbed + drift x hours."""
    a = np.column_stack([-x[:, 0], x[:, 1], x[:, 2]])
    beta, *_ = np.linalg.lstsq(a, y, rcond=None)
    return beta


def fit_prior(y, x, isf0, csf0, spread=0.3):
    """The same fit with a prior at the current values: ratio-scale spread, free drift."""
    a = np.column_stack([-x[:, 0], x[:, 1], x[:, 2]])
    prior = np.array([isf0, csf0, 0.0])
    free = fit_ols(y, x)
    noise = max(float(np.var(y - a @ free)), 1.0)
    precision = np.diag([1 / (spread * isf0) ** 2, 1 / (spread * csf0) ** 2, 1 / 30.0**2])
    system = a.T @ a / noise + precision
    return np.linalg.solve(system, a.T @ y / noise + precision @ prior), np.linalg.inv(system)


def split(windows, last_train_bin):
    # A window that starts before the cut but ends after it would train on scored days.
    return ([w for w in windows if w[5] + WINDOW_MIN // STEP < last_train_bin],
            [w for w in windows if w[5] >= last_train_bin])


def mae(y, p):
    return float(np.mean(np.abs(y - p)))


def spearman(a, b):
    ra, rb = np.argsort(np.argsort(a)), np.argsort(np.argsort(b))
    return float(np.corrcoef(ra, rb)[0, 1])


def median_iqr(values):
    q = np.percentile(values, [25, 50, 75])
    return f"{q[1]:.2f} (IQR {q[0]:.2f}-{q[2]:.2f})"


def references(person, end_bin):
    """Starting values a person could have before bin `end_bin`, before any fitting.

    Held-out studies pass their training cut, and then nothing here reads a later day.
    Studies of a whole record pass its length.
    """
    before = end_bin + person.first
    tdd = person.tdd(before)
    ratio = person.carb_ratio(before)
    factor = person.pump_correction_factor(before)
    out = {"app defaults": (DEFAULT_ISF, DEFAULT_ISF / DEFAULT_ICR)}
    if math.isfinite(tdd) and tdd > 0:
        out["500/1800 rule"] = (1800 / tdd, (1800 / tdd) / (500 / tdd))
        if ratio:
            out["pump ratio + 1800 rule"] = (1800 / tdd, (1800 / tdd) / ratio)
    if factor and ratio:
        out["pump's own settings"] = (factor, factor / ratio)
    return out


def composition_study(people, label):
    """Why a fixed-coefficient meal forecast goes wrong: the sum of two large numbers."""
    rows = []
    for person in people:
        windows = person.meal_windows()
        refs = references(person, len(person.g))
        start = refs.get("pump ratio + 1800 rule") or refs.get("500/1800 rule")
        if len(windows) < MIN_MEALS or not start:
            continue
        y, x = design(windows)
        isf, csf = start
        rows.append((np.mean(y), csf * np.mean(x[:, 1]), -isf * np.mean(x[:, 0]), isf, isf / csf,
                     np.mean(x[:, 0]), np.mean(x[:, 1]) / (isf / csf)))
    if rows:
        r = np.median(np.array(rows), axis=0)
        print(f"\n{label}: the typical isolated meal, 4 h window, medians across people")
        print(f"Carbs push +{r[1]:.0f} mg/dL, insulin pushes {r[2]:.0f} mg/dL, sum {r[1] + r[2]:+.0f}; "
              f"glucose actually moved {r[0]:+.0f}. Starting values: ISF {r[3]:.0f}, ICR {r[4]:.1f}. "
              f"Insulin acted {r[5]:.1f} U where the logged carbs need {r[6]:.1f} U.")


def tuning_gain_study(people, label):
    """What saving a tuned ICR buys one person, against the values they started from."""
    print(f"\n### {label}: saving a fitted ICR against keeping the starting values, held-out meals")
    print("| Starting values | People | Error before | Error after | Median change | People improved |")
    print("|---|---:|---:|---:|---:|---:|")
    for start in ("app defaults", "pump ratio + 1800 rule", "pump's own settings"):
        before, after = [], []
        for person in people:
            last_train = int(len(person.g) * TRAIN_FRACTION)
            train, test = split(person.meal_windows(), last_train)
            refs = references(person, last_train)
            if len(train) < MIN_MEALS or len(test) < 8 or start not in refs:
                continue
            yt, xt = design(test)
            isf, csf = refs[start]
            before.append(mae(yt, predict(xt, isf, csf)))
            after.append(mae(yt, predict(xt, *saved_values(isf, carb_slope_alone(train, isf)))))
        if before:
            b, a = np.array(before), np.array(after)
            print(f"| {start} | {len(b)} | {np.median(b):.1f} | {np.median(a):.1f} | "
                  f"{np.median(a / b - 1) * 100:+.0f} % | {int(np.sum(a < b))}/{len(b)} |")


def drawing_layer_study(people, label):
    """A fitted correction used only to draw the what-if: are its pictures repeatable?

    The free fit's coefficients are unstable, but a drawn curve needs stable predictions, not
    stable parts. Two fits from alternate weeks draw the same held-back meals; this is how far
    apart their curves end.
    """
    gaps, spread = [], []
    for person in people:
        windows = person.meal_windows()
        if len(windows) < 3 * MIN_MEALS:
            continue
        third = [w for w in windows if (w[5] // 2016) % 3 == 2]
        halves = [[w for w in windows if (w[5] // 2016) % 3 == h] for h in (0, 1)]
        if min(len(h) for h in halves) < MIN_MEALS or len(third) < 8:
            continue
        _, x = design(third)
        y = np.array([w[0] for w in third])
        a, b = (predict(x, *fit_ols(*design(h))) for h in halves)
        gaps.append(float(np.median(np.abs(a - b))))
        spread.append(float(np.median(np.abs(y - np.median(y)))))
    if gaps:
        print(f"\n{label}: what-if drawn by fits from two separate sets of weeks, on meals neither saw: curves end "
              f"{np.median(gaps):.0f} mg/dL apart (median, {len(gaps)} people), against {np.median(spread):.0f} mg/dL "
              f"of ordinary spread in those meals")


def outcome_study(people, label):
    """Held-out error of the 4 h change after a meal, per candidate."""
    rows = defaultdict(dict)
    used = 0
    prior_name = "pump ratio + 1800 rule" if label == "AZT1D" else "500/1800 rule"
    for person in people:
        last_train = int(len(person.g) * TRAIN_FRACTION)
        train, test = split(person.meal_windows(), last_train)
        if len(train) < MIN_MEALS or len(test) < 8:
            continue
        used += 1
        y, x = design(train)
        yt, xt = design(test)
        rows["no change"][person.name] = mae(yt, np.zeros_like(yt))
        refs = references(person, last_train)
        for name, (isf, csf) in refs.items():
            rows[name][person.name] = mae(yt, predict(xt, isf, csf))
            # The same coefficients plus a drift learned from the training meals, so that
            # any gain below can be told apart from merely learning a constant.
            drift = float(np.mean((y - predict(x, isf, csf)) / x[:, 2]))
            rows[name + " + drift"][person.name] = mae(yt, predict(xt, isf, csf, drift))
        rows["drift alone"][person.name] = mae(yt, np.mean(y / x[:, 2]) * xt[:, 2])
        beta = fit_ols(y, x)
        rows["free fit, with drift (not savable)"][person.name] = mae(yt, predict(xt, *beta))
        # What PR 4 would hand a person: ISF kept, ICR fitted, and only those two numbers
        # reach the what-if, inside the app's limits. The fit's own drift and baseline terms
        # have nowhere to live in a profile, so they are dropped before scoring.
        for name, (isf, _) in refs.items():
            if name not in ("app defaults", "pump ratio + 1800 rule", "pump's own settings"):
                continue
            kept = saved_values(isf, fit_two_stage(train, isf)[0])
            rows[f"saved: ICR fitted with baseline, ISF from {name}"][person.name] = mae(yt, predict(xt, *kept))
            kept = saved_values(isf, carb_slope_alone(train, isf))
            rows[f"saved: ICR fitted alone, ISF from {name}"][person.name] = mae(yt, predict(xt, *kept))
        test_windows = test
        cgm = fit_baseline_only(train)
        rows["CGM baseline alone"][person.name] = mae(yt, predict_baseline_only(test_windows, cgm))
        rows["CGM baseline + free fit"][person.name] = mae(yt, predict_full(test_windows, fit_full(train)))
        if prior_name in refs:
            isf0, csf0 = refs[prior_name]
            shrunk, _ = fit_prior(y, x, isf0, csf0)
            rows["fit with prior"][person.name] = mae(yt, predict(xt, *shrunk))
    print(f"\n### {label}: mean absolute error of the 4 h change after a meal, held-out last 30 % of days")
    print(f"{used} people with at least {MIN_MEALS} isolated training meals\n")
    base = rows["no change"]
    print("| Candidate | Median MAE, mg/dL | vs no change | Better than no change |")
    print("|---|---:|---:|---:|")
    for name, per_person in rows.items():
        common = [p for p in per_person if p in base]
        values = np.array([per_person[p] for p in common])
        change = np.array([per_person[p] / base[p] - 1 for p in common])
        print(f"| {name} | {np.median(values):.1f} | {np.median(change) * 100:+.0f} % | "
              f"{int(np.sum(change < 0))}/{len(common)} |")


def circularity_study(people, label):
    """With ISF held, how much does the answer depend on ISF, and on the outcome at all?"""
    rows = []
    for person in people:
        windows = person.meal_windows()
        refs = references(person, len(person.g))
        start = refs.get("pump ratio + 1800 rule") or refs.get("500/1800 rule")
        if not start or len(windows) < MIN_MEALS:
            continue
        y, x = design(windows)
        isf = start[0]
        icrs = []
        for factor in (0.5, 1.0, 2.0):
            carb_slope = fit_two_stage(windows, isf * factor)[0]
            if carb_slope <= 0:
                icrs = None
                break
            icrs.append(isf * factor / carb_slope)
        if icrs is None:
            continue
        habit = float(np.sum(x[:, 1]) / np.sum(x[:, 0]))  # grams absorbed per unit acted
        alone = carb_slope_alone(windows, isf)
        rows.append((icrs[0], icrs[1], icrs[2], habit,
                     person.carb_ratio() or math.nan, isf / alone if alone > 0 else math.nan))
    if not rows:
        return
    r = np.array(rows)
    print(f"\n{label}: ICR from the ISF-held fit, {len(rows)} people")
    print(f"Holding ISF at half or double moves the answer x{math.exp(np.median(np.abs(np.log(r[:, 0] / r[:, 2])))):.2f} end to end. "
          f"Its distance from grams-per-unit-acted, the person's own habit: x{math.exp(np.median(np.abs(np.log(r[:, 1] / r[:, 3])))):.2f}.")
    known = ~np.isnan(r[:, 4])
    if known.sum() >= 5:
        print(f"Habit against the pump's ratio: x{math.exp(np.median(np.abs(np.log(r[known, 3] / r[known, 4])))):.2f}, "
              f"median habit/pump {np.median(r[known, 3] / r[known, 4]):.2f}; "
              f"fit against pump x{math.exp(np.median(np.abs(np.log(r[known, 1] / r[known, 4])))):.2f}")
        both = known & np.isfinite(r[:, 5])
        ratio = np.log(r[both, 5] / r[both, 4])
        print(f"ICR fitted alone, with no baseline, against pump: median {math.exp(np.median(ratio)):.2f}, "
              f"x{math.exp(np.median(np.abs(ratio))):.2f}")


def recovery_study(people, label):
    """Known answers: real treatments, invented glucose. Separates noise from a wrong model."""
    rng = np.random.default_rng(7)
    print(f"\n### {label}: recovering known coefficients from real treatment logs")
    print("| Meals | People | ISF off by | ICR off by | Wrong sign |")
    print("|---:|---:|---:|---:|---:|")
    for count in (20, 40, 80):
        isf_errors, icr_errors, wrong, people_used = [], [], 0, 0
        for person in people:
            windows = person.meal_windows()
            refs = references(person, len(person.g))
            start = refs.get("pump ratio + 1800 rule") or refs.get("500/1800 rule")
            if not start or len(windows) < count:
                continue
            people_used += 1
            true_isf, true_csf = start
            y, x = design(windows)
            residuals = y - predict_baseline_only(windows, fit_baseline_only(windows))
            for _ in range(40):
                picked = rng.choice(len(windows), count, replace=False)
                xs = x[picked]
                noise = rng.choice(residuals, count)
                ys = predict(xs, true_isf, true_csf) + noise
                beta = fit_ols(ys, xs)
                if beta[0] <= 0 or beta[1] <= 0:
                    wrong += 1
                    continue
                isf_errors.append(abs(math.log(beta[0] / true_isf)))
                icr_errors.append(abs(math.log((beta[0] / beta[1]) / (true_isf / true_csf))))
        if people_used:
            print(f"| {count} | {people_used} | x{math.exp(np.median(isf_errors)):.2f} | "
                  f"x{math.exp(np.median(icr_errors)):.2f} | {wrong / (40 * people_used) * 100:.0f} % |")


BLOCKS = [("night", 0, 6), ("morning", 6, 11), ("midday", 11, 16), ("evening", 16, 24)]


def block_study(people, label):
    """The profile works in time blocks. Does the evidence reach down to a block?"""
    print(f"\n### {label}: where meals leave people, per time block")
    print("| Block | People with 10+ meals | Median meals | Interval excludes zero | Median interval width, mg/dL |")
    print("|---|---:|---:|---:|---:|")
    rng = np.random.default_rng(3)
    for name, start, end in BLOCKS:
        counts, clear, widths = [], 0, []
        for person in people:
            windows = [w for w in person.meal_windows()
                       if start <= ((w[5] + person.first) % 288) * STEP / 60 < end]
            if len(windows) < 10:
                continue
            counts.append(len(windows))
            y, _ = design(windows)
            days = np.array([w[5] // 288 for w in windows])
            unique = np.unique(days)
            means = [np.mean(np.concatenate([y[days == d] for d in rng.choice(unique, len(unique))]))
                     for _ in range(300)]
            low, high = np.percentile(means, [2.5, 97.5])
            widths.append(high - low)
            clear += 1 if (low > 0 or high < 0) else 0
        if counts:
            print(f"| {name} | {len(counts)} | {np.median(counts):.0f} | {clear}/{len(counts)} | {np.median(widths):.0f} |")


def persistence_study(people, label):
    """Does a clear direction in one period still hold in the next? What a nudge relies on."""
    agree, total, change_a, change_b = 0, 0, [], []
    for person in people:
        windows = person.meal_windows()
        if len(windows) < 2 * MIN_MEALS:
            continue
        cut = windows[len(windows) // 2][5]
        first, second = [w[0] for w in windows if w[5] < cut], [w[0] for w in windows if w[5] >= cut]
        a, b = float(np.mean(first)), float(np.mean(second))
        change_a.append(a)
        change_b.append(b)
        if abs(a) >= 15:
            total += 1
            agree += 1 if a * b > 0 else 0
    if len(change_a) >= 5:
        print(f"\n{label}: mean 4 h change after meals, first half of a person's meals against the second: "
              f"rank correlation {spearman(np.array(change_a), np.array(change_b)):.2f} over {len(change_a)} people; "
              f"of {total} with at least 15 mg/dL in the first half, {agree} kept the direction")


def envelope_isf(person, minutes, quantile, min_units=0.5, ignore_carbs=True, week_parity=None, last_bin=None):
    """Food only ever pushes glucose up, so it hides insulin's effect and never fakes it.

    Across every window with insulin acting, the drop per unit is therefore a mix of "insulin
    alone" and "insulin fought by something": the top of that spread is the part food did not
    touch. Takes a high quantile of the drop per unit acted. Carbs are not read at all unless
    ignore_carbs is False, when windows with logged carbs absorbing are simply left out.
    """
    bins = minutes // STEP
    ratios = []
    for i in range(0, len(person.g) - bins, max(1, bins // 2)):
        if week_parity is not None and (i // 2016) % 2 != week_parity:
            continue
        if last_bin is not None and i + bins >= last_bin:
            continue
        w = person.window(i, bins)
        if not w or w[1] < min_units:
            continue
        if not ignore_carbs and w[2] > 1.0:
            continue
        ratios.append(-w[0] / w[1])
    if len(ratios) < 20:
        return None
    return float(np.quantile(ratios, quantile))


def naive_isf(person):
    """The obvious reading: how far glucose fell per unit after corrections, away from meals."""
    meals = np.array(person.meal_bins)
    clean = []
    for b in person.correction_bins:
        i = b - person.first
        if i + 36 >= len(person.g) or (len(meals) and np.min(np.abs(meals - i)) < 36):
            continue
        w = person.window(i, 36)
        if w and w[1] > 0:
            clean.append(-w[0] / w[1])
    return float(np.median(clean)) if len(clean) >= 10 else None


def spread_line(name, estimates, truth):
    e, t = np.array(estimates, dtype=float), np.array(truth, dtype=float)
    keep = np.isfinite(e) & (e > 0)
    if keep.sum() < 5:
        return f"| {name} | {keep.sum()} | - | - | - |"
    log_ratio = np.log(e[keep] / t[keep])
    return (f"| {name} | {keep.sum()} | {math.exp(np.median(log_ratio)):.2f} | "
            f"x{math.exp(np.median(np.abs(log_ratio))):.2f} | {spearman(e[keep], t[keep]):.2f} |")


def isf_study(people, label):
    """Beating the bias: every candidate against the factor the pump was really set to."""
    anchored = [(p, p.pump_correction_factor()) for p in people]
    anchored = [(p, cf) for p, cf in anchored if cf]
    if len(anchored) < 5:
        return
    cfs = [cf for _, cf in anchored]
    print(f"\n### {label}: ISF against the pump's own correction factor, {len(anchored)} people")
    print(f"Pump factors: {median_iqr(cfs)} mg/dL/U, range {min(cfs):.0f}-{max(cfs):.0f}")
    print("\n| Estimator (carbs hidden unless stated) | People | Median estimate / pump | Typical miss | Rank correlation |")
    print("|---|---:|---:|---:|---:|")
    rule = [1800 / p.tdd() for p, _ in anchored]
    print(spread_line("1800 rule", rule, cfs))
    print(spread_line("Naive: drop per unit after corrections, uses carb log", [naive_isf(p) or math.nan for p, _ in anchored], cfs))
    free = []
    for p, _ in anchored:
        w = p.meal_windows()
        free.append(fit_ols(*design(w))[0] if len(w) >= MIN_MEALS else math.nan)
    print(spread_line("Free fit on meals, uses carb log", free, cfs))
    for minutes in (60, 120, 180):
        for quantile in (0.5, 0.75, 0.9):
            values = [envelope_isf(p, minutes, quantile) or math.nan for p, _ in anchored]
            print(spread_line(f"Envelope, {minutes} min, top {100 - int(quantile * 100)} %", values, cfs))
    for minutes, quantile in ((120, 0.75), (120, 0.9)):
        values = [envelope_isf(p, minutes, quantile, ignore_carbs=False) or math.nan for p, _ in anchored]
        print(spread_line(f"Envelope, {minutes} min, top {100 - int(quantile * 100)} %, skipping logged carbs", values, cfs))
    cross_validation(anchored)


def cross_validation(anchored):
    """Choosing among settings on the same people flatters the winner. Choose on one half."""
    settings = [(minutes, quantile) for minutes in (60, 120, 180) for quantile in (0.5, 0.75, 0.9)]

    def estimate(person, setting):
        value = envelope_isf(person, *setting)
        return value if value and value > 0 else math.nan

    table = {setting: np.array([estimate(p, setting) for p, _ in anchored]) for setting in settings}
    truth = np.array([cf for _, cf in anchored])
    rng = np.random.default_rng(11)
    misses, biases = [], []
    for _ in range(200):
        order = rng.permutation(len(truth))
        pick, score = order[: len(order) // 2], order[len(order) // 2:]

        def miss(values, index):
            ratio = np.log(values[index] / truth[index])
            return float(np.nanmedian(np.abs(ratio)))

        best = min(settings, key=lambda s: miss(table[s], pick))
        misses.append(miss(table[best], score))
        biases.append(float(np.nanmedian(np.log(table[best][score] / truth[score]))))
    print(f"\nSetting chosen on half the people, scored on the other half, 200 splits: typical miss "
          f"x{math.exp(np.median(misses)):.2f}, median estimate / pump {math.exp(np.median(biases)):.2f}")


def shuffle_control(people, label, minutes=60, quantile=0.9):
    """Is the envelope measuring insulin, or just glucose swings divided by a typical dose?

    A person's windows are kept, but each drop is paired with the insulin of a random other
    window, which destroys any link between them while keeping both distributions. If the
    estimate does not move, it never read insulin's effect: it is swing / dose, and because
    doses scale with daily insulin it would mimic the 1800 rule for free.
    """
    rng = np.random.default_rng(13)
    real, shuffled, cfs, rule = [], [], [], []
    bins = minutes // STEP
    for person in people:
        rows = [w for i in range(0, len(person.g) - bins, max(1, bins // 2)) if (w := person.window(i, bins))]
        if len(rows) < 100:
            continue
        drops = np.array([-w[0] for w in rows])
        acted = np.array([w[1] for w in rows])
        mask = acted >= 0.5
        if mask.sum() < 30:
            continue
        estimate = float(np.quantile(drops[mask] / acted[mask], quantile))
        control = [float(np.quantile(rng.permutation(drops)[mask] / acted[mask], quantile)) for _ in range(30)]
        real.append(estimate)
        shuffled.append(float(np.median(control)))
        cfs.append(person.pump_correction_factor() or math.nan)
        rule.append(1800 / person.tdd())
    real, shuffled, cfs, rule = map(np.array, (real, shuffled, cfs, rule))
    print(f"\n### {label}: shuffle control, {minutes} min top {100 - int(quantile * 100)} % ratio, {len(real)} people")
    print(f"Real estimate / shuffled estimate: {median_iqr(real / shuffled)}; real above shuffled for "
          f"{int(np.sum(real > shuffled))}/{len(real)}")
    known = np.isfinite(cfs)
    if known.sum() >= 5:
        print(spread_header())
        print(spread_line("Real pairing", real[known], cfs[known]))
        print(spread_line("Shuffled pairing", shuffled[known], cfs[known]))
        print(spread_line("1800 rule", rule[known], cfs[known]))
    print(f"Rank correlation of real and shuffled across people: {spearman(real, shuffled):.2f}")


def counterfactual_isf(person, minutes=120, use_carb_log=False):
    """What would glucose have done with no insulin? Then insulin did the rest.

    A CGM-only baseline is trained on windows with next to no insulin acting, so unlike a
    forecast trained on everything it has not absorbed the average effect of a dose. Its
    answer is the counterfactual for windows with insulin, and the gap is the insulin's work.
    """
    bins = minutes // STEP
    rows = [w for i in range(12, len(person.g) - bins, bins // 4) if (w := person.window(i, bins))]
    if len(rows) < 100:
        return None
    usable = [w for w in rows if not use_carb_log or w[2] < 1.0]
    if len(usable) < 100:
        return None
    # A closed loop leaves almost no insulin-free windows, so "quiet" is the quietest quarter.
    low, high = np.percentile([w[1] for w in usable], [25, 75])
    quiet = [w for w in usable if w[1] <= low]
    treated = [w for w in usable if w[1] >= high]
    if len(quiet) < 30 or len(treated) < 20:
        return None
    a = np.array([(1.0, w[4] - 120.0, w[6]) for w in quiet])
    beta = np.linalg.lstsq(a, np.array([w[0] for w in quiet]), rcond=None)[0]
    t = np.array([(1.0, w[4] - 120.0, w[6]) for w in treated])
    effect = t @ beta - np.array([w[0] for w in treated])
    extra = np.array([w[1] for w in treated]) - float(np.mean([w[1] for w in quiet]))
    return float(effect.sum() / extra.sum())


def quiet_correction_isf(person, hours=3, minimum=6):
    """Doses given away from meals and other doses, with glucose steady at the time.

    The nearest thing the logs hold to an experiment, and what a person could mark with one
    tap ("nothing to eat for three hours") without logging any food.
    """
    bins = hours * 12
    meals = np.array(person.meal_bins)
    doses = sorted(b - person.first for b, u in person.bolus.items() if u >= 0.3)
    out = []
    for index, i in enumerate(doses):
        if i < 36 or i + bins >= len(person.g):
            continue
        if len(meals) and np.min(np.abs(meals - i)) < bins:
            continue
        if any(0 < abs(d - i) < bins for d in doses[max(0, index - 40): index + 40] if d != i):
            continue
        w = person.window(i, bins)
        if not w or w[1] < 0.5 or abs(w[6]) > 0.5:
            continue
        out.append(-w[0] / w[1])
    return (float(np.median(out)), len(out)) if len(out) >= minimum else None


def added_value_study(people, label):
    """The yardstick: does an estimate know anything about a person that daily insulin does not?

    The 1800 rule already gets close to pump factors, which may well have been set from it.
    A measurement earns its place only if it explains where a person's factor differs from
    the rule, so this correlates log(estimate / rule) with log(pump factor / rule).
    """
    if not any(p.pump_correction_factor() for p in people):
        return
    rng = np.random.default_rng(17)
    print(f"\n### {label}: what each estimate adds to the 1800 rule")
    print("| Estimator | People | Median estimate / pump | Typical miss | Rank correlation with pump | Explains the pump's gap from the rule |")
    print("|---|---:|---:|---:|---:|---:|")

    def line(name, values):
        rows = [(v, p.pump_correction_factor(), 1800 / p.tdd()) for p, v in values if v and v > 0 and p.pump_correction_factor()]
        if len(rows) < 6:
            print(f"| {name} | {len(rows)} | - | - | - | - |")
            return
        v, cf, rule = np.array(rows).T
        ratio = np.log(v / cf)
        gap = "-" if np.allclose(v, rule) else f"{spearman(np.log(v / rule), np.log(cf / rule)):+.2f}"
        print(f"| {name} | {len(v)} | {math.exp(np.median(ratio)):.2f} | x{math.exp(np.median(np.abs(ratio))):.2f} | "
              f"{spearman(v, cf):.2f} | {gap} |")

    def shuffled_envelope(p):
        bins = 12
        rows = [w for i in range(0, len(p.g) - bins, 6) if (w := p.window(i, bins))]
        if len(rows) < 100:
            return None
        drops, acted = np.array([-w[0] for w in rows]), np.array([w[1] for w in rows])
        mask = acted >= 0.5
        return float(np.quantile(rng.permutation(drops)[mask] / acted[mask], 0.9)) if mask.sum() >= 30 else None

    line("1800 rule", [(p, 1800 / p.tdd()) for p in people])
    line("1800 rule, first 14 days of insulin", [(p, 1800 / p.tdd(p.first + 14 * 288)) for p in people
                                                 if math.isfinite(p.tdd(p.first + 14 * 288))])
    line("1800 rule, basal not logged (boluses doubled)",
         [(p, 900 / float(np.median(list(p.daily_insulin.values())))) for p in people if p.daily_insulin])
    line("Envelope, 60 min top 10 %", [(p, envelope_isf(p, 60, 0.9)) for p in people])
    line("Same, shuffled (placebo)", [(p, shuffled_envelope(p)) for p in people])
    line("Counterfactual baseline, carbs hidden", [(p, counterfactual_isf(p)) for p in people])
    line("Counterfactual baseline, skipping logged carbs", [(p, counterfactual_isf(p, use_carb_log=True)) for p in people])
    quiet = [(p, quiet_correction_isf(p)) for p in people]
    line("Quiet corrections", [(p, q[0] if q else None) for p, q in quiet])
    counts = [q[1] / (len(p.g) / 288) * 7 for p, q in quiet if q]
    if counts:
        print(f"\nQuiet corrections: {len(counts)} people have at least 6, at {median_iqr(counts)} per week")


def spread_header():
    return "| Estimator | People | Median estimate / pump | Typical miss | Rank correlation |\n|---|---:|---:|---:|---:|"


def partial_logging_study(people, label, repeats=30):
    """ICR when only some meals are logged, with ISF from the rule, as the plan proposes.

    Which meals go unlogged is a random draw, and one draw can show a bias that is not there,
    so the draw is repeated and the spread across repeats is reported with the result.
    """
    results = defaultdict(list)
    rng = np.random.default_rng(9)
    for repeat in range(repeats):
        rows = defaultdict(list)
        for person in people:
            if not person.food_ratio:
                continue
            pump = person.carb_ratio()
            isf = 1800 / person.tdd()
            if not math.isfinite(isf) or isf <= 0:
                continue
            saved = person.absorbed, person.cum_absorbed, person.meal_bins
            for share in (1.0, 0.5, 0.25):
                kept = {b: g for b, g in person.carbs.items() if rng.random() < share}
                absorbed = np.zeros(len(person.g))
                for b, grams in kept.items():
                    i = b - person.first
                    if 0 <= i < len(person.g) and grams > 0:
                        curve = carb_curve(grams) * grams
                        end = min(len(absorbed), i + len(curve))
                        absorbed[i:end] += curve[: end - i]
                person.absorbed = absorbed
                person.cum_absorbed = np.concatenate([[0], np.cumsum(absorbed)])
                person.meal_bins = sorted(b - person.first for b, g in kept.items()
                                          if g >= 5 and 0 <= b - person.first < len(person.g))
                windows = person.meal_windows()
                if len(windows) >= 10:
                    slope = fit_two_stage(windows, isf)[0]
                    if slope > 0:
                        rows[share].append((isf / slope, pump, len(windows)))
            person.absorbed, person.cum_absorbed, person.meal_bins = saved
        for share, values in rows.items():
            r = np.array(values)
            log_ratio = np.log(r[:, 0] / r[:, 1])
            results[share].append((math.exp(np.median(log_ratio)), math.exp(np.median(np.abs(log_ratio))),
                                   float(np.median(r[:, 2])), len(r)))
    if not results:
        return
    print(f"\n### {label}: ICR fitted from logged meals, ISF from the rule, as fewer meals are logged, {repeats} draws")
    print("| Meals logged | People | Usable meals | Median ICR / pump (range of draws) | Typical miss (range of draws) |")
    print("|---:|---:|---:|---:|---:|")
    for share in (1.0, 0.5, 0.25):
        if results[share]:
            r = np.array(results[share])
            print(f"| {int(share * 100)} % | {np.median(r[:, 3]):.0f} | {np.median(r[:, 2]):.0f} | "
                  f"{np.median(r[:, 0]):.2f} ({r[:, 0].min():.2f}-{r[:, 0].max():.2f}) | "
                  f"x{np.median(r[:, 1]):.2f} (x{r[:, 1].min():.2f}-x{r[:, 1].max():.2f}) |")


def ratio_link_study(people, label):
    """Without any grams logged, can the ratio be read off the sensitivity?"""
    pairs = [(p.pump_correction_factor(), p.carb_ratio())
             for p in people if p.food_ratio and p.pump_correction_factor()]
    if len(pairs) >= 5:
        r = np.array(pairs)
        k = r[:, 0] / r[:, 1]
        print(f"\n{label}: pump factor / pump ratio: {median_iqr(k)} over {len(k)} people (the 1800/500 rules assume 3.6); "
              f"reading ICR as factor / median misses by x{math.exp(np.median(np.abs(np.log((r[:, 0] / np.median(k)) / r[:, 1])))):.2f}")
        rule = [(500 / p.tdd(), p.carb_ratio()) for p in people if p.food_ratio]
        q = np.array(rule)
        ratio = np.log(q[:, 0] / q[:, 1])
        print(f"500 rule against the pump's ratio, no meal logged: median {math.exp(np.median(ratio)):.2f}, typical miss "
              f"x{math.exp(np.median(np.abs(ratio))):.2f}, rank correlation {spearman(q[:, 0], q[:, 1]):.2f} over {len(q)} people")


def forecast_study(people, label):
    """The adaptive forecast's question: does knowing the treatments help at all?

    A ridge on the adaptive forecast's CGM history features, the same with treatment terms
    freely fitted, and CGM-only plus the app's fixed-coefficient journal effect. Treatments
    are as logged by the forecast moment, so insulin already given and carbs already eaten
    count, and a later dose does not.
    """
    print(f"\n### {label}: held-out forecast error, last 30 % of days, origins every 15 min")
    print("| Horizon | CGM ridge | + insulin term | + insulin and carb terms | + fixed coefficients | Better with insulin term | Insulin on board: ridge | Insulin on board: + term | Lows caught: ridge | Lows caught: + term |")
    print("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for horizon in (30, 60, 120):
        bins = horizon // STEP
        plain, terms, fixed, insulin_only, iob_plain, iob_terms = {}, {}, {}, {}, {}, {}
        pooled = []  # (forecast by ridge, forecast with the insulin term, what happened)
        for person in people:
            rows, targets, order, near, fixed_effect = [], [], [], [], []
            cut = int(len(person.g) * TRAIN_FRACTION)
            refs = references(person, cut - bins)
            start = refs.get("pump ratio + 1800 rule") or refs.get("500/1800 rule")
            if not start:
                continue
            isf, csf = start
            meals = np.array(person.meal_bins)
            given = person.known_effect(bins)
            for i in range(12, len(person.g) - bins, 3):
                now, later = person.value(i, 0), person.value(i + bins, 0)
                if math.isnan(now) or math.isnan(later):
                    continue
                d15, d30, d60 = (now - person.value(i - k) for k in (3, 6, 12))
                if math.isnan(d15) or math.isnan(d30) or math.isnan(d60):
                    continue
                acted, absorbed = given(i)
                rows.append((1.0, d15, d30, d60, now - 120.0, acted, absorbed))
                targets.append(later - now)
                fixed_effect.append(-isf * acted + csf * absorbed)
                order.append(i)
                near.append(len(meals) > 0 and bool(np.any((i - meals >= 0) & (i - meals <= 36))))
            if len(rows) < 500:
                continue
            a, t = np.array(rows), np.array(targets)
            order, near, fixed_effect = np.array(order), np.array(near), np.array(fixed_effect)
            train, test = order < cut - bins, order >= cut
            if test.sum() < 100:
                continue

            def ridge(columns, target):
                m = a[train][:, columns]
                scale = np.maximum(m.std(axis=0), 1e-6)
                scale[0] = 1.0
                penalty = np.eye(len(columns)) * 1.0
                penalty[0, 0] = 0.0
                beta = np.linalg.solve((m / scale).T @ (m / scale) + penalty, (m / scale).T @ target[train])
                return (a[:, columns] / scale) @ beta

            cgm_columns, insulin_columns, all_columns = [0, 1, 2, 3, 4], [0, 1, 2, 3, 4, 5], [0, 1, 2, 3, 4, 5, 6]
            p_plain, p_terms, p_insulin = ridge(cgm_columns, t), ridge(all_columns, t), ridge(insulin_columns, t)
            # Fixed coefficients get the same chance: the ridge learns only what the app's
            # arithmetic leaves over, so the trend does not count the treatment twice.
            p_fixed = ridge(cgm_columns, t - fixed_effect) + fixed_effect
            plain[person.name] = mae(t[test], p_plain[test])
            terms[person.name] = mae(t[test], p_terms[test])
            fixed[person.name] = mae(t[test], p_fixed[test])
            insulin_only[person.name] = mae(t[test], p_insulin[test])
            active = a[:, 5] >= np.percentile(a[train][:, 5], 75)
            if (test & active).sum() >= 30:
                iob_plain[person.name] = mae(t[test & active], p_plain[test & active])
                iob_terms[person.name] = mae(t[test & active], p_insulin[test & active])
            current = a[:, 4] + 120.0
            for k in np.nonzero(test & (current >= 80))[0]:
                pooled.append((current[k] + p_plain[k], current[k] + p_insulin[k], current[k] + t[k]))
        if not plain:
            continue
        better = sum(1 for p in plain if insulin_only[p] < plain[p])
        r = np.array(pooled)
        lows = r[:, 2] < 70
        alarms = max(int(lows.sum() * 2), 1)  # the same number of alarms for each: twice the lows

        def caught(column):
            flagged = np.argsort(r[:, column])[:alarms]
            return float(lows[flagged].sum() / max(lows.sum(), 1))

        print(f"| {horizon} | {np.median(list(plain.values())):.1f} | {np.median(list(insulin_only.values())):.1f} | "
              f"{np.median(list(terms.values())):.1f} | {np.median(list(fixed.values())):.1f} | {better}/{len(plain)} | "
              f"{np.median(list(iob_plain.values())):.1f} | {np.median(list(iob_terms.values())):.1f} | "
              f"{caught(0) * 100:.0f} % | {caught(1) * 100:.0f} % |")


def reliability_study(people, label):
    """Do two separate stretches of a person's life give the same numbers?"""
    ratio_isf, ratio_icr, bad, cfg, est, kept = [], [], 0, [], [], 0
    for person in people:
        windows = person.meal_windows()
        if len(windows) < 2 * MIN_MEALS:
            continue
        kept += 1
        # Whole weeks alternate between the halves: days within a week are not independent.
        halves = [[w for w in windows if (w[5] // 2016) % 2 == h] for h in (0, 1)]
        if min(len(h) for h in halves) < MIN_MEALS:
            kept -= 1
            continue
        fits = [fit_ols(*design(h)) for h in halves]
        if any(f[0] <= 0 or f[1] <= 0 for f in fits):
            bad += 1
            continue
        ratio_isf.append(abs(math.log(fits[0][0] / fits[1][0])))
        ratio_icr.append(abs(math.log((fits[0][0] / fits[0][1]) / (fits[1][0] / fits[1][1]))))
        whole = fit_ols(*design(windows))
        if person.food_ratio and whole[0] > 0 and whole[1] > 0:
            cfg.append(person.carb_ratio())
            est.append(whole[0] / whole[1])
    print(f"\n### {label}: free fit, alternate weeks against each other")
    print(f"{kept} people with enough meals in both halves; {bad} gave a negative coefficient in a half")
    if ratio_isf:
        print(f"Half-to-half disagreement, as a multiple: ISF x{math.exp(np.median(ratio_isf)):.2f}, "
              f"ICR x{math.exp(np.median(ratio_icr)):.2f} (median)")
    if len(cfg) >= 5:
        cfg, est = np.array(cfg), np.array(est)
        print(f"Fitted ICR against the pump's own ratio: rank correlation {spearman(cfg, est):.2f} over "
              f"{len(cfg)} people; median multiple x{math.exp(np.median(np.abs(np.log(est / cfg)))):.2f}")


def collinearity_study(people, label):
    values = []
    for person in people:
        windows = person.meal_windows()
        if len(windows) < MIN_MEALS:
            continue
        _, x = design(windows)
        if np.std(x[:, 0]) > 0 and np.std(x[:, 1]) > 0:
            values.append(float(np.corrcoef(x[:, 0], x[:, 1])[0, 1]))
    if values:
        print(f"\n{label}: correlation of insulin acted and carbs absorbed across a person's meals: "
              f"{median_iqr(values)}; r squared {np.median(values) ** 2:.2f}")


def net_outcome_study(people, label):
    """What a person could be told without any fitting: where meals leave them."""
    print(f"\n### {label}: mean 4 h change after isolated meals, by person")
    clear, total, widths = 0, 0, []
    for person in people:
        windows = person.meal_windows()
        if len(windows) < MIN_MEALS:
            continue
        total += 1
        y, _ = design(windows)
        days = np.array([w[5] // 288 for w in windows])
        unique = np.unique(days)
        rng = np.random.default_rng(1)
        means = []
        for _ in range(500):  # whole days are resampled: meals on one day are not independent
            picked = rng.choice(unique, len(unique))
            means.append(np.mean(np.concatenate([y[days == d] for d in picked])))
        low, high = np.percentile(means, [2.5, 97.5])
        widths.append(high - low)
        clear += 1 if (low > 0 or high < 0) else 0
    if total:
        print(f"{clear}/{total} people have a day-resampled 95 % interval that excludes zero; "
              f"median interval width {np.median(widths):.0f} mg/dL")


def correction_study(people, label):
    """Insulin without food is the only clean look at ISF. How often does a person have one?"""
    counts, effects = [], []
    for person in people:
        bins = sorted(b - person.first for b in person.correction_bins)
        meals = np.array(person.meal_bins)
        clean = []
        for i in bins:
            if i + 36 >= len(person.g):
                continue
            if len(meals) and np.min(np.abs(meals - i)) < 36:
                continue
            w = person.window(i, 36)
            if w and w[1] > 0:
                clean.append(w)
        days = len(person.g) / 288
        counts.append(len(clean) / days * 7)
        if len(clean) >= 10:
            y, x = design(clean)
            effects.append(-float(np.median(y / x[:, 0])))
    if counts:
        print(f"\n{label}: corrections with no meal within 3 h: {median_iqr(counts)} per week")
    if effects:
        print(f"Median drop per unit acted, people with >= 10: {median_iqr(effects)} mg/dL/U over {len(effects)} people; "
              f"{sum(1 for e in effects if e <= 0)} show no drop at all")


def main():
    np.seterr(all="ignore")  # Accelerate's BLAS reports spurious matmul warnings on macOS
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--azt1d")
    parser.add_argument("--hupa")
    parser.add_argument("--peak", type=float, default=75.0, help="insulin peak, minutes")
    args = parser.parse_args()
    if not args.azt1d and not args.hupa:
        parser.error("point at --azt1d and/or --hupa")
    if not 0 < args.peak < 180:  # the activity curve needs a peak inside the first half of its 360 min
        parser.error("--peak must be above 0 and below 180 minutes")
    print(f"Insulin peak {args.peak:.0f} min. Windows {WINDOW_MIN} min from meals with no other meal "
          "in the 2 h before or inside the window.")
    sources = []
    if args.azt1d:
        sources.append(("AZT1D", load_azt1d(args.azt1d)))
    if args.hupa:
        sources.append(("HUPA-UCM", load_hupa(args.hupa)))
    for label, people in sources:
        for person in people:
            person.build(args.peak)
        meals = [len(p.meal_windows()) for p in people]
        days = [len(p.g) / 288 for p in people]
        print(f"\n## {label}: {len(people)} people, median {np.median(days):.0f} days and "
              f"{np.median(meals):.0f} isolated meals each")
        if label == "AZT1D":
            ratios = [p.carb_ratio() for p in people if p.food_ratio]
            print(f"Pump carb ratios: {median_iqr(ratios)} g/U")
        collinearity_study(people, label)
        composition_study(people, label)
        net_outcome_study(people, label)
        outcome_study(people, label)
        tuning_gain_study(people, label)
        drawing_layer_study(people, label)
        reliability_study(people, label)
        circularity_study(people, label)
        recovery_study(people, label)
        block_study(people, label)
        persistence_study(people, label)
        isf_study(people, label)
        shuffle_control(people, label)
        added_value_study(people, label)
        partial_logging_study(people, label)
        ratio_link_study(people, label)
        correction_study(people, label)
        forecast_study(people, label)


if __name__ == "__main__":
    sys.exit(main())
