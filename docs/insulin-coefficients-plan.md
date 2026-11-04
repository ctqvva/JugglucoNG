# Personal insulin coefficients: plan for #722

Status: plan. Nothing here changes the app yet.

## What #722 asks for

The issue asks to keep the forecast curve visible beside the real readings, so that a person can tune their coefficients by comparing the two. Those coefficients are the three numbers in Model profile, set per time block:

- **ICR**, the carb ratio: grams covered by one unit.
- **ISF**, insulin sensitivity: how far one unit moves glucose.
- **Carb absorption**, in grams per hour.

Prediction analysis (#774) records forecasts and scores them, but it does not answer the issue. It stores a `configHash`, which cannot be turned back into the coefficients behind a forecast, and the adaptive forecast it introduced reads no coefficients at all.

The plan below gives the issue's author what they asked for, adds estimates of both numbers, and adds a path for people who do not log food.

## Where the coefficients act

Everything reads the one profile in `PredictionModelProfileStore`, so changing it changes all of these:

| Reader | What it does with them |
|---|---|
| Standard forecast (`GlucosePrediction.kt`) | Draws the effect of logged meals and doses |
| Dose calculator (`JournalDoseCalculator`) | Sizes food and correction doses |
| State dose hints (`StateDoseHint`) | Carbs or insulin suggestion on the dashboard |
| PRE_HIGH gate (`ForecastIobCoverage`) | Holds the alert back when insulin on board covers the rise |
| PRE_LOW gate (`ForecastLowSuppression`) | Holds the alert back when carbs on board cover the fall |
| IOB/COB risk (`IobCobRisk`) | Warns when uncovered insulin would go low |

The adaptive forecast is the exception: it reads none of them. Measured LOW and VERY_LOW alerts do not depend on them either.

## What the data says

`tools/coefficient_feasibility.py` produces every number here. It uses the two public datasets from the prediction benchmark:

- **AZT1D**: 25 people on Control-IQ pumps, about 44 days each. It carries the pump's real settings, which gives something to measure against. The carb ratio comes from `CarbSize / FoodDelivered`. The correction factor comes from `(glucose − 110) / (correction + IOB)` on user-started boluses, and it comes back in tight clusters for 19 people, ranging from 3 to 117 mg/dL per unit.
- **HUPA-UCM**: 12 people on injections, a median of 14 days each. Only 3 of them have enough meals for the held-out test, so it cannot confirm anything by itself.

Training days always come before the days scored. "x1.25" means typically off by a factor of 1.25 in either direction.

### Tuned coefficients fix the what-if, not the forecast

A meal's what-if is a small difference between two large numbers. On a typical meal, carbs push +196 mg/dL and insulin pushes −252, which sums to −56, while glucose actually moved +2. A 10 % error in either side is a 20–25 mg/dL error in the answer.

So the starting coefficients matter a lot. Mean error of the change 4 hours after a meal on held-out days, as the median across 22 people:

| Coefficients | Error, mg/dL | Against "no change" |
|---|---:|---:|
| App defaults | 123 | +177 % |
| The pump's own settings (18 people) | 73 | +37 % |
| Pump ratio and 1800 rule | 63 | +43 % |
| Fitted to the person | 40 | −8 % |
| No change at all | 43 | |
| CGM level and trend only, no coefficients | 31 | −23 % |

Fitting cuts the what-if's error by about 40 % against the numbers people really use, and by two thirds against the app's defaults. This is why the issue is worth doing: today's what-if is worse than drawing a flat line, and tuned coefficients take that away.

Two limits come with it. Fitted coefficients still lose to a model that knows nothing about the meal. And they do not improve the forecast itself:

| Horizon | CGM only | + fitted insulin and carbs | + fixed coefficients |
|---:|---:|---:|---:|
| 30 min | 14.3 | 14.3 | 22.0 |
| 60 min | 23.2 | 23.1 | 35.8 |
| 120 min | 31.2 | 30.5 | 50.3 |

This fits #774's finding that logged treatments hurt the forecast. Wrong coefficients look like the reason, but even well-fitted ones only reach parity with CGM alone.

**So this work improves what-ifs, dosing arithmetic and the person's understanding of their insulin. It should not be sold as better forecasts.**

### ICR can be estimated when meals are logged

Fitting ICR with ISF held at its current value lands x1.20 from the pump's ratio, with a median ratio of 1.00. The result depends little on the ISF chosen: moving ISF across a fourfold range moves ICR by only x1.16. It is also not just the person's dosing habit, because raw grams per unit sits further from the pump (x1.36) than the fit does.

Logging only some meals costs precision, but less than expected. Hiding meals at random, over 30 draws:

| Meals logged | Median ICR / pump | Typical miss |
|---:|---:|---:|
| All | 1.00 | x1.20 |
| Half | 1.14 | x1.28 |
| A quarter | 1.16 | x1.34 |

Even a quarter of meals beats logging none (x1.60, below), so partial logging is worth supporting. The estimate does lean about 15 % high, which means too little insulin per gram, and single draws range from 0.88 to 1.49. Real logging is not random either: people skip certain meals, not arbitrary ones.

Fitting ICR and ISF together does not work. Alternate weeks disagree by x1.65 on ICR and x1.40 on ISF, and 6 of 21 people get a negative coefficient.

### Where meals leave a person is dependable

Of the 10 people whose meals ended at least 15 mg/dL off in the first half of their data, 9 kept that direction in the second half (rank correlation 0.65 across 21 people). This needs no coefficients at all.

It reaches down to time blocks, though not for everyone. With 19–31 meals per block, a clear direction shows for 8 of 19 people in the morning, 5 of 18 at midday and 10 of 23 in the evening. Intervals are about 40 mg/dL wide.

### ISF cannot yet be measured from these logs

Every attempt to measure ISF was compared against the pump's factor, with carbs hidden unless noted:

| Estimator | Median estimate / pump | Typical miss |
|---|---:|---:|
| Naive: drop per unit after corrections, away from logged meals | 0.41 | x2.44 |
| Fitted jointly with ICR from meals | 0.34 | x2.91 |
| Envelope: top 10 % of drop per unit, 60 min | 0.92 | x1.25 |
| The same, with drops paired to random insulin (placebo) | 0.81 | x1.38 |
| **1800 rule on total daily insulin** | **1.04** | **x1.23** |

- **The bias is in the dangerous direction.** A naive reading of 0.41 of the real factor would make corrections about 2.5 times too large. An ISF estimate from outcomes must not reach dosing until it clears this.
- **More data will not fix it.** Rebuilding glucose from real treatment logs with a known ISF recovers it to x1.10 from 20 meals and x1.04 from 80. So the limit is not sample size. The failure looks structural: insulin is given because of what glucose is doing, and food, the loop and changing sensitivity all move with it.
- **The envelope looks like the answer and mostly is not.** It removes the bias (0.41 to 0.92) and survives cross-validation at x1.25. But pairing each drop with insulin from a random other time still gives x1.38, and real and shuffled estimates correlate 0.94 across people. Most of its accuracy is ordinary glucose swing divided by a typical dose, which scales with daily insulin, which is the 1800 rule in disguise. A faint real signal remains: real beats shuffled for 18 of 25 people, and it tracks the pump's distance from the rule at +0.62 against about +0.3 for the placebo. With 19 people that gap is suggestive, not established.
- **Better-designed tests could not run on this data.** A counterfactual trained on insulin-free windows and quiet corrections reached 3–4 people, because a closed loop almost never leaves insulin alone. Seven people have enough quiet corrections, at about 1.7 a week.

The 1800 rule's agreement has a caveat of its own: pump factors may have been set from that rule, so agreement shows it matches what clinicians set, not that it is true for the person.

### Insulin totals give ISF without any food logging

The rule needs only logged insulin, and it holds up when the logging is thin:

| Input | Median estimate / pump | Typical miss |
|---|---:|---:|
| All insulin, whole history | 1.04 | x1.23 |
| First 14 days only | 1.08 | x1.28 |
| Boluses only, doubled because basal is not logged | 1.06 | x1.24 |

It is personal, it follows the person as their insulin use changes, and it needs no meals. It cannot see differences between time blocks.

ICR has no such route, because a ratio of grams cannot be found without grams. The rough options miss badly: the 500 rule reads x1.60 too high, and pump factor ÷ 4.9 misses by x1.44, with that 4.9 fitted on the same people. The usual 1800/500 pairing assumes 3.6, where these people sit at 4.9 (IQR 3.7–7.3).

### Insulin as an input to the adaptive forecast

Adding insulin as a fitted term makes almost no difference here (14.3 to 14.4 at 30 minutes, 23.2 to 23.2 at 60, 31.2 to 30.4 at 120). It is slightly worse with a lot of insulin on board (27.7 to 28.8 at 60 minutes). Lows caught at equal alarm counts do not clearly move (39 % to 38 % at 30 minutes, 28 % to 28 % at 60, 10 % to 12 % at 120). These figures come from a small ridge standing in for the real model, on people whose loop already acts on insulin, so injection users may differ.

### Limits of this evidence

- **Injection users are nearly absent.** AZT1D is all closed loop and HUPA has 3 usable people. This is the largest gap, since injections are the case the app serves most directly.
- **Pump settings are a reference, not the truth.** They were set by people and may be wrong.
- **The study is a Python model of the app's arithmetic**, retrospective, on one assumed insulin curve. The conclusions hold for peaks from 55 to 95 minutes. The numbers need repeating in the Kotlin benchmark before anything ships.

## Decisions

Taken by the project owner on this evidence:

| Question | Decision | How the plan honours it |
|---|---|---|
| What to build | Replay, meal check and frozen recording, plus a route with no food logging | PRs 1–4 |
| Stating an ICR | Full estimate with its range, no cap | PR 4 |
| Stating an ISF | Estimate it like ICR, and research a way around the bias | PR 4 states the estimate from insulin totals, which is the best figure available today. PR 5 continues the research and promotes a measured ISF when it passes |
| Saving | One profile, a confirm sheet, a dated history, undo | PR 4 |
| Insulin in adaptive prediction | A switch under the Adaptive prediction toggle, shown when that is on | PR 6 |
| Alerts on the new curve | Wanted, as separate work | PR 7 |

## Rules for all of it

- **Nothing changes a coefficient without the person confirming it.** Estimates are shown and refreshed on their own, and only the confirm sheet writes to the profile. This keeps the project's standing rule of no automatic treatment adjustment.
- **Trial values stay inside the screen.** They reach no reader in the table above.
- **Each estimate says how it was reached.** A figure from logged meals and a figure from insulin totals are labelled differently, and each carries a range.
- **An outcome-based ISF never reaches dosing until PR 5's gate passes.** The bias runs toward bigger doses.
- **Nothing leaves the phone.** All of this runs locally. Personal data is not committed or uploaded, and any run on real exports stays on the machine of the person who owns the data.

## Pull requests

Each is separate and reviewable by itself. PRs 1 and 2 can run in parallel, and so can 6 and 7.

### 1. Meal replay with trial coefficients

Answers the issue with no recording and nothing to wait for, because a what-if can be recomputed from history and the journal with any coefficients.

- Extract the what-if arithmetic from `GlucosePrediction.kt` as a pure function. Arithmetic unchanged, and a test holds the dashboard forecast to bit-identical output.
- New **Past meals** screen, reached from Model profile: each meal with its readings and its what-if over 6 hours, which is the existing limit of `PREDICTION_HORIZON`.
- Trial ICR and ISF controls, with one figure that updates as they move: the average miss across those meals.
- Isolated meals only, using the study's rule: at least 5 g, no other meal in the 2 hours before or inside the window.

**Done when** replay matches a recorded standard forecast on the same inputs, and trial values are shown to reach no reader in the table above.

### 2. Frozen what-ifs and coefficient history

Keeps what the app said at the time, which replay cannot do, and puts the line on the main chart as the issue asks.

- Migrate `prediction_analysis.db` to version 2, with a real migration test.
- `coefficient_history`: when the profile changed, its full values and what changed it. It closes the `configHash` gap and is what undo reads.
- `treatment_forecasts`: the what-if at the moment a meal or dose is logged, with the coefficients behind it, kept for the same 90 days.
- A chart option to keep that curve visible beside the readings, off by default. Recording follows the existing Prediction analysis opt-in.

**Done when** a frozen curve survives a later profile change unchanged, and turning analysis off stops new records.

### 3. Meal check

- Per time block: where meals leave the person after 4 hours, with the count and interval, using day-level resampling as the benchmark does.
- A block appears at 10 isolated meals. Below that it says how many more it needs.
- Sits in Model profile beside the block it describes. Plain wording, such as "Evening meals end 30 mg/dL high, 24 meals".

**Done when** the Kotlin results reproduce the study's block table on AZT1D.

### 4. Estimates and saving

- **ICR per block**: fitted from logged meals with the block's current ISF and a CGM level and trend baseline, shown with its range. Needs 15 isolated meals in the block.
- **Partial logging**: fewer logged meals widen the range rather than block the estimate, since even a quarter of meals beats none. The share of boluses with carbs logged nearby feeds that range, so a person who logs little sees it.
- **ISF**: from the last 14 days of insulin, doubling boluses if basal is not logged. One value for the whole day, labelled as coming from insulin totals.
- **No food logging**: the same ISF, with ICR shown as a rough figure from ISF and a wide range, and not offered for saving. Logging 15 meals in a block replaces it with a real estimate.
- **Use these values**: a confirm sheet showing the effect in concrete terms, such as "a 60 g meal: 6.0 U → 7.1 U", and naming what follows: doses, hints and alert gates. Saving writes to the one profile and to `coefficient_history`. Undo restores the previous entry.

**Done when** a test proves no path writes the profile without the sheet, and the Kotlin benchmark reproduces the ICR table within its intervals.

### 5. Measuring ISF

Continues the research, since insulin totals cannot see time blocks or a real change in sensitivity.

- A one-tap mark on an insulin entry: "Nothing to eat for 3 hours". It costs far less than logging food, and it supplies what the logs lack, which is a dose with no food confounding it.
- Run the marked-correction estimate and the envelope in shadow, displayed as experimental and read by nothing.
- Port the estimators to `predictionbench` and run them on app exports from injection users, including the #722 author's if they are willing. Every run stays local.

**The gate**: a measured ISF replaces the rule only if, on injection data, it beats its shuffled placebo for most people, tracks real differences from the rule on a sample large enough to tell, and does not read under 0.7 of the reference for more people than it reads over 1.43. The last condition is what keeps the bias from reaching doses. If it does not pass, the rule stays, and the plan says so.

### 6. Insulin in the adaptive forecast

- `PREDICTION_ADAPTIVE_INSULIN`, off by default, phone scope, shown under the Adaptive prediction toggle only when that is on. Localized like the rest of the settings.
- Insulin acted over each horizon as an extra ridge feature, with a `MODEL_VERSION` bump and a retrain. The feature reads only doses logged by the forecast moment.
- With the switch on, record the plain adaptive forecast as a shadow alongside it, so Prediction accuracy shows whether it helps this person. Fall back to the plain model when insulin logging stops.
- Repeat the study's comparison on the real model in `predictionbench` first.

Expect little on pump data. Injection users are the open question, and shadow recording answers it from real use.

### 7. Alerts on the new curve

The largest safety change here, so it comes last and stands alone. PRE_LOW and PRE_HIGH project from the current rate of change today (`AlertGlucoseMath.projectedDisplayValue`). Swapping in the adaptive curve is not a drop-in change: in #774's benchmark it ranked lows better at equal alarm counts (49–60 % against 42–46 %) but rarely crossed 70 by itself, so the same threshold would mostly stop alerting.

- An opt-in setting, off by default, per alert type, falling back to today's projection whenever no adaptive model is ready.
- Its own threshold, chosen in the benchmark for an alarm budget and not reused from today's.
- Measured LOW and VERY_LOW stay independent, and PRE_HIGH and PRE_LOW keep their asymmetric gates.

**Done when** the benchmark shows more lows caught at no more alarms on both datasets, and the fallback has tests. This needs its own sign-off.

## Questions still open

None of these blocks PR 1:

1. **Closed-loop users.** A loop muddies the outcomes: should estimates from AAPS users carry a note, or be hidden?
2. **Rough ICR with no food logged.** PR 4 shows it but does not offer it for saving. Is showing it useful, or does it invite use?
3. **The rule's constant.** 1800 is standard for mg/dL, and the data agrees with it (1.04). Should it stay fixed, or be settable for people who know theirs differs?

## Reproducing the numbers

Download AZT1D (doi:10.17632/gk9m674wcx.1) and HUPA-UCM (doi:10.17632/3hbcscwz44.1), both CC BY 4.0 on Mendeley Data. Neither is committed. Then, with numpy installed:

```
python3 tools/coefficient_feasibility.py \
    --azt1d '/path/AZT1D 2025/CGM Records' \
    --hupa '/path/HUPA-UCM Diabetes Dataset' \
    --peak 75
```

It takes under a minute and prints every table above. The random seeds are fixed. `--peak` sets the assumed insulin peak.
