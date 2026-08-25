# Insulin activity curves: evidence and model limits

## Why these are pharmacodynamic curves

JugglucoNG uses an insulin curve as relative glucose-lowering activity over time. The IOB calculation integrates the remaining area under that curve, and prediction integrates delivered activity. Plasma insulin concentration is therefore the wrong input: concentration and glucose-lowering effect do not have the same timing or shape.

Model version 1 uses pharmacodynamic glucose infusion rate (GIR) profiles from euglycaemic clamp studies where a usable source exists. Each curve is normalized to its own maximum because NG needs relative activity; absolute GIR cannot be transferred between people as a personalized effect size. Insulin sensitivity remains a separate prediction parameter.

These are population-average reference models, not clinical validation for an individual. Injection site, dose, exercise, temperature, illness, renal or hepatic function, and within-person variation can change insulin action substantially.

## Evidence states

- `source_single_dose`: a single-dose pharmacodynamic source includes a usable activity shape and an end-of-action estimate. The preset may participate in IOB and prediction.
- `source_steady_state`: the source measured repeated dosing at steady state. It is displayed as a reference profile but is not treated as the action of one injection.
- `source_reference`: the source is pharmacodynamic, but the observation window ends while material activity remains or otherwise does not support a complete per-dose tail. Calculation is disabled.
- `unverified`: the curve is a legacy approximation or a custom user curve. It is explicitly labelled and never presented as source-backed.

“Source-backed” describes traceability, not personal accuracy. Interpolated or clamped dose models are additionally marked `approximated` on the dose snapshot.

## Catalogue

| Preset | Evidence | Source dose/state | Model decision |
| --- | --- | --- | --- |
| Rapid acting (generic) | Unverified | None | Legacy polynomial retained for compatibility. |
| Long acting (generic) | Unverified | None | Legacy polynomial; calculation off by default. |
| Humulin R / Novolin R (regular human) | Single dose | 0.3 U/kg | GIR figure digitized; label onset, peak and termination used as anchors. |
| NovoRapid / NovoLog (aspart) | Unverified | None suitable for a complete GIR curve | Existing approximation retained and labelled. |
| Humalog (lispro) | Unverified | No complete standalone GIR curve in the selected label | Existing approximation retained and labelled. |
| Apidra (glulisine) | Unverified | Comparative/meal and subgroup data do not provide a complete general curve | Existing approximation retained and labelled. |
| Fiasp (aspart) | Single dose | 0.1, 0.2 and 0.4 U/kg | Three GIR profiles and label timing anchors; interpolate only inside the observed dose range. |
| Lyumjev (lispro-aabc) | Single dose | 7, 15 and 30 U | Three GIR profiles and label timing anchors; interpolate only inside the observed dose range. |
| Afrezza (human) | Single dose | 4, 12 and 48 U | Three baseline-corrected GIR profiles and label timing anchors. |
| Humulin N / Novolin N (NPH human) | Reference | 0.4 U/kg | Clamp ends at about 22 h with substantial GIR remaining; no per-dose calculation. |
| Ultra-long basal (generic) | Unverified | None | Legacy polynomial; calculation off by default. |
| Lantus / Basaglar / Semglee (glargine U-100) | Reference | 0.3 U/kg | Observation ends at 24 h with GIR remaining; no invented tail. |
| Toujeo (glargine U-300) | Steady state | 0.4 U/kg after 8 daily doses | The published GIR is accumulated steady-state activity, not one injection. |
| Levemir (detemir) | Reference | 0.2 and 0.4 U/kg | Dose-dependent GIR profiles retained for reference; conservative calculation-off classification. |
| Tresiba (degludec) | Steady state | 0.4 U/kg after 8 daily doses | The published 42 h profile contains overlapping daily doses. |
| Awiqli (icodec) | Steady state | Full-week model-derived steady-state profile | Partial clamp observations plus PK/PD interpolation; not a single weekly dose curve. |
| Humulin R U-500 (concentrated regular human) | Single dose | 50 and 100 U timing; 100 U plotted profile | Label reports mean duration 21 h (range 13–24 h); non-reference doses are marked approximate. |
| Ryzodeg (70% degludec / 30% aspart) | Reference | 0.8 U/kg single dose | Published 24 h plot ends before the stated beyond-24 h duration. |
| NovoLog Mix 70/30 / NovoMix 30 (70% protamine aspart / 30% aspart) | Single dose | 0.3 U/kg | Full 24 h GIR figure digitized. |
| Humalog Mix 50/50 (50% protamine lispro / 50% lispro) | Reference | 0.3 U/kg | Clamp ends with activity still present. |
| Humalog Mix 75/25 (75% protamine lispro / 25% lispro) | Reference | 0.3 U/kg | Clamp ends with activity still present. |
| Humulin 70/30 / Novolin 70/30 (70% NPH human / 30% regular human) | Reference | 0.3 U/kg | Comparative plot ends with activity still present; label duration reaches about 23 h. |

## Primary sources

- Major insulin categories: [ADA Standards of Care in Diabetes—2026, section 9](https://diabetesjournals.org/care/article/49/Supplement_1/S183/163934/9-Pharmacologic-Approaches-to-Glycemic-Treatment)
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

The catalogue stores explicit minute/activity points rather than opaque fitted polynomials. Points were read from the cited GIR figures at documented time anchors, divided by the maximum of the same source curve, and connected linearly. Label tables supply onset, peak, and return-to-baseline anchors where available. This is intentionally auditable and replaceable in a later model version.

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
