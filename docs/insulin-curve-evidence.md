# Insulin activity curves: evidence and model limits

## Why these are pharmacodynamic curves

JugglucoNG uses an insulin curve as relative glucose-lowering activity over time. The IOB calculation integrates the remaining area under that curve, and prediction integrates delivered activity. Plasma insulin concentration is therefore the wrong input: concentration and glucose-lowering effect do not have the same timing or shape.

Model version 2 uses pharmacodynamic glucose infusion rate (GIR) profiles from euglycaemic clamp studies where a usable source exists. Each curve is normalized to its own maximum because NG needs relative activity; absolute GIR cannot be transferred between people as a personalized effect size. Insulin sensitivity remains a separate prediction parameter.

The 100% point is the maximum of the selected mean GIR profile. It is the modelled peak, not the onset of action and not 100% of an injected dose. Where a label reports first measurable effect, NG stores that timing separately instead of inventing a non-zero activity magnitude at the onset anchor.

These are population-average reference models, not clinical validation for an individual. Injection site, dose, exercise, temperature, illness, renal or hepatic function, and within-person variation can change insulin action substantially.

## Evidence states

- `source_single_dose`: a single-dose pharmacodynamic source includes a usable activity shape and an end-of-action estimate. The preset may participate in IOB and prediction.
- `source_steady_state`: the source measured repeated dosing at steady state. It is displayed as a reference profile but is not treated as the action of one injection.
- `source_reference`: the source is pharmacodynamic, but the observation window ends while material activity remains or otherwise does not support a complete per-dose tail. Calculation is disabled.
- `unverified`: the curve is a legacy approximation or a custom user curve. It is explicitly labelled and never presented as source-backed.

“Source-backed” describes traceability, not personal accuracy. Interpolated or clamped dose models are additionally marked `approximated` on the dose snapshot.

## Catalogue

| Commercial name | Scientific name | Evidence | Source dose/state | Model decision |
| --- | --- | --- | --- | --- |
| Rapid acting (generic) | - | Unverified | None | Legacy polynomial retained for compatibility. |
| Long acting (generic) | - | Unverified | None | Legacy polynomial; calculation off by default. |
| Humulin R / Novolin R | regular human | Single dose | 0.3 U/kg | Mean GIR trace is retained through the plotted 14 h window; label onset and peak are explicit timing anchors. |
| NovoRapid / NovoLog | aspart | Unverified | No complete standalone general GIR curve in the selected label | Existing approximation retained and labelled. |
| Humalog | lispro | Unverified | Meal-response data, not a complete standalone GIR curve | Existing approximation retained and labelled. |
| Apidra | glulisine | Unverified | Comparative meal data and an obese subgroup GIR plot | Existing approximation retained rather than generalising a subgroup curve. |
| Fiasp | aspart | Single dose | 0.1, 0.2 and 0.4 U/kg | Three GIR profiles and exact label timing anchors; interpolate only inside the observed dose range. |
| Lyumjev | lispro-aabc | Single dose | 7, 15 and 30 U | Three GIR profiles and exact label timing anchors; interpolate only inside the observed dose range. |
| Afrezza | human | Single dose | 4, 12 and 48 U | Three baseline-corrected GIR profiles and exact label timing anchors. |
| Humulin N / Novolin N | NPH human | Reference | 0.4 U/kg | Median maximum effect is 6.5 h; the clamp ends near 22 h with GIR remaining, so calculation stays off. |
| Ultra-long basal (generic) | - | Unverified | None | Legacy approximation; calculation off by default. |
| Lantus / Basaglar / Semglee | glargine U-100 | Reference | 0.3 U/kg | Observation ends at 24 h with GIR remaining; no invented tail. |
| Toujeo | glargine U-300 | Steady state | 0.4 U/kg after 8 daily doses | The published GIR is accumulated steady-state activity, not one injection. |
| Levemir | detemir | Reference | 0.2 and 0.4 U/kg | Dose-dependent GIR profiles retained for reference; conservative calculation-off classification. |
| Tresiba | degludec | Steady state | 0.4 U/kg after 8 daily doses | The 12 h median maximum is anchored, but the 42 h profile contains overlapping daily doses. |
| Awiqli | icodec | Steady state | Full-week model-derived steady-state profile | Only 3.5 days were clamped; the full week combines partial clamps and model prediction. |
| Humulin R U-500 | concentrated regular human | Single dose | 50 and 100 U timing; 100 U plotted profile | Label onset is under 15 min and mean duration is 21 h (range 13–24 h); other doses are approximate. |
| Ryzodeg | 70% degludec / 30% aspart | Reference | 0.8 U/kg single dose | Published 24 h plot ends before the stated beyond-24 h duration. |
| NovoLog Mix 70/30 / NovoMix 30 | 70% protamine aspart / 30% aspart | Single dose | 0.3 U/kg | Full 24 h GIR figure with 10–20 min onset and 2.7 h mean peak. |
| Humalog Mix 50/50 | 50% protamine lispro / 50% lispro | Reference | 0.3 U/kg | Median maximum is 2 h; clamp ends at 22 h with activity still present. |
| Humalog Mix 75/25 | 75% protamine lispro / 25% lispro | Reference | 0.3 U/kg | Median maximum is 2 h; clamp ends at 22 h with activity still present. |
| Humulin 70/30 / Novolin 70/30 | 70% NPH human / 30% regular human | Reference | 0.3 U/kg | Label onset is 50 min, peak is 3.5 h and mean duration is 23 h; the plotted trace ends at 12 h, so no tail is invented. |

## Before and after

This table compares the original NG catalogue with model version 2. “Observed end” is the end of the stored source trace, not necessarily a clinically complete duration of action.

| Insulin | Before | Model version 2 |
| --- | --- | --- |
| Rapid acting (generic) | Undocumented polynomial, peak 40 min, 6 h span | Same compatibility curve, explicitly unverified |
| Long acting (generic) | Approximation, onset 90 min, peak 10 h, 24 h span | Same compatibility curve, unverified and excluded from calculation by default |
| Humulin R / Novolin R | Polynomial, peak 140 min, 8.5 h span | 0.3 U/kg mean GIR trace, onset 30 min, peak 180 min, observed to 14 h |
| NovoRapid / NovoLog | Generic rapid polynomial | Retained but explicitly unverified because the label graph is a meal glucose response, not GIR |
| Humalog | Undocumented polynomial, peak 60 min | Retained but explicitly unverified because the label graph is a meal glucose response, not GIR |
| Apidra | Undocumented polynomial, peak 65 min | Retained but explicitly unverified; available GIR is from an obese subgroup |
| Fiasp | Undocumented polynomial, peak 55 min, 6 h span | 0.1/0.2/0.4 U/kg GIR profiles; reference onset 20 min, peak 91 min, observed end 5 h |
| Lyumjev | Approximation, onset 15 min, peak 95 min, 370 min span | 7/15/30 U GIR profiles; reference onset 17 min, peak 120 min, end 276 min |
| Afrezza | Approximation, onset 12 min, peak 45 min, 210 min span | 4/12/48 U GIR profiles; 12 U reference onset 12 min, peak 45 min, end 180 min |
| Humulin N / Novolin N | Approximation, onset 90 min, peak 6 h, 12 h span | 0.4 U/kg mean GIR, median maximum 6.5 h, observed to 22 h with incomplete tail |
| Ultra-long basal (generic) | Approximation, onset 3 h, peak 16 h, 36 h span | Same compatibility curve, explicitly unverified and excluded from calculation |
| Lantus / Basaglar / Semglee | Not available | 0.3 U/kg reference GIR observed to the 24 h clamp limit |
| Toujeo | Not available | 0.4 U/kg steady-state GIR observed to 36 h; never treated as one-dose action |
| Levemir | Not available | Separate 0.2 and 0.4 U/kg reference GIR profiles observed to 24 h |
| Tresiba | Not available | 0.4 U/kg steady-state GIR with the official 12 h median maximum and 42 h observation |
| Awiqli | Not available | Full-week steady-state reference, visibly classified as partly model-derived |
| Humulin R U-500 | Not available | 100 U mean GIR trace, under-15-min onset, 6 h plotted peak and 24 h observation |
| Ryzodeg | Not available | 0.8 U/kg single-dose reference trace to the incomplete 24 h endpoint |
| NovoLog Mix 70/30 / NovoMix 30 | Not available | 0.3 U/kg GIR, 10–20 min onset, 2.7 h mean peak, observed to 24 h |
| Humalog Mix 50/50 | Not available | 0.3 U/kg GIR, 2 h median peak, incomplete 22 h endpoint |
| Humalog Mix 75/25 | Not available | 0.3 U/kg GIR, 2 h median peak, incomplete 22 h endpoint |
| Humulin 70/30 / Novolin 70/30 | Not available | 0.3 U/kg GIR, 50 min onset, 3.5 h peak; only the observed 12 h trace is stored |

## Primary sources

- Major insulin categories: [ADA Standards of Care in Diabetes—2026, section 9](https://diabetesjournals.org/care/article/49/Supplement_1/S183/163934/9-Pharmacologic-Approaches-to-Glycemic-Treatment)
- [NovoLog prescribing information](https://dailymed.nlm.nih.gov/dailymed/getFile.cfm?setid=13891e5a-e57a-46e8-911c-2f680352b52b&type=pdf), section 12.2
- [Humalog prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=c5f75765-86b8-4926-b8c3-b42133ca7ac8), section 12.2
- [Apidra prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=e7af6a7a-8046-4fb4-9979-4ec4230b23aa), section 12.2
- [Fiasp prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=834e7efc-393f-4c55-9125-628562a8a5cf), section 12.2
- [Lyumjev prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=616daea1-0b79-4970-a141-6f99f2072f02), section 12.2
- [Afrezza prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=29f4637b-e204-425b-b89c-7238008d8c10), section 12.2
- [Humulin R U-100 prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=b519bd83-038c-4ec5-a231-a51ec5cc291f), section 12.2
- [Humulin R U-500 prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=b60e8dd0-1d48-4dc9-87fd-e14675255e8c), section 12.2
- [Humulin N prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=f6edd793-440b-40c2-96b5-c16133b7a921), section 12.2
- [Lantus prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=d5e07a0c-7e14-4756-9152-9fea485d654a), section 12.2
- [Toujeo prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=257ca17b-6ff7-4f2e-9037-4c185bac5768), section 12.2
- [Levemir prescribing information](https://dailymed.nlm.nih.gov/dailymed/getFile.cfm?setid=2b8d9730-686b-444b-9941-7b5877255924&type=pdf), section 12.2
- [Tresiba prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=456c5e87-3dfd-46fa-8ac0-c6128d4c97c6), section 12.2
- [Awiqli EMA product information](https://www.ema.europa.eu/en/documents/product-information/awiqli-epar-product-information_en.pdf), section 5.1
- [Ryzodeg EMA product information](https://www.ema.europa.eu/en/documents/product-information/ryzodeg-epar-product-information_en.pdf), section 5.1
- [NovoLog Mix 70/30 prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=19888a44-b330-462a-864d-338c5893dd63), section 12.2
- [Humalog Mix 50/50 prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=b34cd3ff-d0af-4852-b4ef-2a8b4a93aeae), section 12.2
- [Insulin lispro protamine/lispro Mix 75/25 prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=e49f701d-c19f-4cb2-b63f-01a917b33abc), section 12.2
- [Humulin 70/30 prescribing information](https://dailymed.nlm.nih.gov/dailymed/drugInfo.cfm?setid=e245e0c5-b2d6-418b-baa4-1c3324292885), section 12.2

## Digitization and dose resolution

The catalogue stores explicit minute/activity points rather than opaque fitted polynomials. Points were read from the cited GIR figures at documented time anchors, divided by the maximum of the same source curve, and connected linearly. Label tables supply onset and peak timing separately where available. A plotted trace may extend beyond a label's mean duration, or a clamp may stop before activity ends; the evidence state and table above preserve that distinction instead of forcing every final point to zero. This is intentionally auditable and replaceable in a later model version.

For sources with multiple studied doses, NG resolves the curve when the dose is recorded:

1. Absolute-dose sources use entered units. U/kg sources divide entered units by the current optional body weight.
2. An exact studied dose uses that source curve.
3. A dose between studied levels is linearly interpolated and marked approximate.
4. A dose outside the observed range is clamped to the nearest profile and marked approximate; NG does not extrapolate.
5. Missing body weight uses the reference profile and is marked approximate.

Changing or removing body weight affects only subsequently created or dose/preset-edited entries. Every insulin entry stores the resolved points, model version, evidence state, weight used, and approximation state. Existing database entries are migrated by freezing their current preset curve and marking it unverified/approximate. Preset upgrades therefore cannot rewrite historical IOB.

## Calculation boundaries

- IOB, effective IOB, prediction, chart markers, outbound snapshots, exports, and watch prediction consume the same per-dose curve snapshot.
- Source-reference and steady-state profiles cannot contribute to IOB or dose calculation by default.
- Basal and premixed sources remain visible in the catalogue even where calculation is disabled, so the app does not silently substitute an unrelated generic curve.
- Legacy and custom unverified curves remain usable for compatibility, but their status is visible and their dose snapshots stay marked approximate.
- No profile should be described as clinically verified without independent review by a qualified diabetes clinician or clinical pharmacologist.

## Review required before release

The implementation and source transcription need independent clinical/pharmacology review. In particular, reviewers should check every digitized anchor against the cited figure, confirm that the selected observation window supports the evidence state, and decide whether any `source_reference` profile can be promoted only after a defensible complete single-dose tail is found.
