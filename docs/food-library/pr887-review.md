# PR 887 correctness review

Reviewed the original head `300081ddb5c240ca00183d7888c43e5fab105b55`, including
all nine Greptile findings and all 21 Cubic findings. The requested Claude Fable
5.2 audit was attempted at ultrathink effort, but the provider rejected the run
with “Claude API rate limit reached. Try again later.” The repairs and source
review were performed by Codex.

## Dispositions

| Findings | Resolution |
| --- | --- |
| Unit switching changes carbs; tiny package fractions show zero | Retain the converted amount, format a localized decimal that round-trips to the same Float, and accept localized digits and decimal marks. Property-style tests cover weights, portions and English/German/Arabic locales. |
| Editor confuses nutrient reference and serving weight | Open weighed foods with macros converted to per 100, independently retain their serving portion, and scale fibre/energy with the reference. Regression covers 250 g reference with 125 g serving. |
| Restoring an old history backup retains local details | Restore rows with exactly the incoming details (including absence), preserving unrelated rows. Settings restore uses the same path. |
| Barcode response restores stale meal state; leaving scan mode retains lookup | Add to the caller's current draft; close/cancel scanning when activating search. Delayed-response tests cover edited/removed items and closing the scanner. |
| Alert profile and Talker locale regressions | Restore Notify, Talker, AlertVibrationPattern and its deleted tests exactly from the PR base. Restore the unrelated reduction in Cubic dataset review coverage too. |
| Custom-food dedup ignores protein/fat | Compare all nutrients, reference, liquid flag and explicit duration. A corrected custom label matched by barcode updates its nutrient definition. |
| Inferred absorption uses reference macros and becomes explicit | Estimate from consumed nutrients and persist explicit-duration provenance independently of `kept`, including editor, backups and transfer. |
| English aliases disappear after remembering translated foods | Suppress a shelf match only when the library copy is also among the returned matches. |
| Liquid flag missing from transfer/editor | Transfer `is_liquid`; offer existing Weight/Volume controls in the editor and save the selection. |
| Concurrent imports duplicate rows | One shared coroutine mutex covers import, edit, remember, delete, startup seeding, backup snapshot and restore across library instances. Concurrent-instance regressions cover dedup and usage counts. |
| Two databases cannot commit atomically | Keep both databases. Stage a durable pending food write in the library database before writing history; replay idempotently before library reads/writes and clear intent with the details transaction. Tests cover interruption before and after the history commit, schema 1-to-2 migration, and preserving SQLite's ID high-water mark. This is recovery per food, not atomic restoration of the entire history backup. |
| Existing entry loses selected food | Restore the persisted single-food association after foods load, inferring its amount from the entry's carbs. Do not record an unchanged reopened entry as another food use. Full meal composition remains outside this PR. |
| Manual aggregate override remembers stale quantities | Omit food uses after manually changing aggregate carbs; changing ingredients derives totals and enables usage recording again. |
| Quoted decimal commas/multiline CSV/portion punctuation | Parse complete quote-aware records, normalize decimal marks after splitting cells, and store portions reversibly as JSON while accepting old shorthand. |
| Decrement below one step increases amount | A decrement cannot exceed the current positive amount. |
| UPC-E advertised but interpreted as EAN-8 | Remove UPC-E from scanner formats; keep EAN-8, UPC-A and EAN-13. UPC-E decoding needs a format-aware callback and can be added separately. |
| Reference search limit counts unusable rows | Apply the limit after materializing usable foods. |
| Search ignores use frequency; punctuation defeats exact rank | Explicit frequency tie-breaker after match rank; compare separator-normalized names for exact/prefix ranking. |
| MainNavigation removed custom-food save callbacks | No fix required: JournalRepository.upsertEntry calls FoodLibrary.noteUses before saving the entry. Adding another save path would duplicate work. Abandoning an unsaved meal continues to abandon its new custom foods. |

The correctness pass also fixes scientific notation in exported small nutrient
values and canonicalizes valid UPC-A product codes for offline barcode reuse.

## Validation boundary

Automated tests and build results are recorded with the repair commit/PR update.
No physical camera, live in-app Open Food Facts request, Android document-picker
round-trip or on-device meal editing was exercised in this review. Recommended
manual checks remain: switching units, reopening a single-food entry, editing an
imported food, restoring an old backup, and changing a meal during barcode lookup.

An emulator smoke test was attempted through the Device panel. The agent-device
`apps` command failed twice with `adb timed out after 10000ms`, including after
its recommended adb-server restart. No app UI was driven, so emulator behavior
remains unverified.

## Fresh reviews after dcd906df6

All seven new comments (six distinct concerns) were checked against source:

- Refocusing search now cancels scanner work without resetting the query or results.
- English alias matches return the owned translated food, including edited nutrients
  and remembered quantities. The regression now requires that exact library ID.
- Clone journal recovery acquires the library write lock before its history
  transaction, and recovers pending writes before importing. This preserves lock
  order and prevents Clone auto-inserts from taking an allocated library ID. A
  real package-import test pauses ID allocation, verifies that import waits, then
  checks both food definitions and the imported entry's association.
- Switching weight/volume invalidates remembered quantities and portion indices.
- Reopening zero-carb entries can infer quantity from saved protein or fat. If no
  ratio is known, or the saved nutrients disagree with today's food definition,
  the picker retains a weightless snapshot of the saved meal totals. It never
  records that unknown quantity as a new library use, and adding a food preserves
  the previous totals. Matched library definitions remain eligible for learning
  an edited amount; saving an untouched reopened draft records no new use.
- Snapshot queries use the same saved-food ordering as the observer and suggestions.

Validation: 5,710 Mobile tests and 4,143 Wear tests; zero failures or errors, with
one Mobile and nine Wear skips. Updated arm64 Mobile debug APK built and installed
in place on the connected Pixel 8 Pro. UI guardrails and whitespace checks pass.
On the Pixel, 1 g of apple converted to 0.0054945056 pieces and back to 1 g with
unchanged carbs; decrementing 5 g retained 5 g. No test meal was saved to history.

The Pixel also retained the `rice` query and its result list after hiding and
reopening the keyboard. Search activation/cancellation itself is covered by the
focused state regression; live barcode completion during edits remains covered
by the deterministic delayed-response tests rather than a physical barcode scan.

## Follow-up after 66caff53e

Confirmed Cubic's edited-restoration finding. A matched restored food is now
eligible for usage recording when a meal edit enables the draft's existing
`recordFoodUses` gate. Untouched reopened drafts and weightless fallback snapshots
remain excluded. A focused regression covers all three paths and the updated
zero-carb reconstruction test checks the remembered amount too.

Validation for this follow-up: all 80 focused food/journal tests passed, along
with UI guardrails and whitespace checks.

## Settings library and adaptive sheet follow-up

The settings library now offers the same barcode scanner as the meal picker.
Found products open in the library for inspection/saving; missing products can be
entered manually with their scanned barcode retained. Offline/library switches
cancel scanner work and control whether scanning is offered.

The reported French product had been saved, but its name did not match the
English `apple` query. The online row continued offering Save, so repeated taps
could create duplicates. Saved online identities now appear under My foods for
the active search, the online Save row goes away, and the result is acknowledged.
Explicit shelf saves are serialized and idempotent by library ID/source/barcode;
keeping a remembered food preserves its usage, amounts and edited definition.
Existing duplicate rows are preserved rather than deleting user data.

The meal sheet's viewport-height lock now resets when its search/camera/ingredient
layout changes. A rendered regression fills the sheet with search results and
checks that closing the search restores its original short height. On the Pixel,
the camera opened from Settings → Food library, and closing the meal camera
returned the meal sheet to its compact height without the large bottom gap.

Validation: 5,716 Mobile and 4,143 Wear tests, zero failures/errors (1/9 skips),
arm64 Mobile debug APK assembly, UI guardrails, and whitespace checks. Rendered
regressions cover a French online result saved while searching in English on a
Russian screen, scanner availability, and the sheet's measured shrink behavior.
The final scanner cancel-control adjustment also passed the rendered suite and
APK assembly. No test journal entry was saved on the Pixel.

## Mixed fallback-meal association

A subsequent Greptile finding was confirmed: usage filtering could leave one
rememberable food in a two-item meal, causing the repository to link the entire
meal to that one new food. The entry input now carries the full meal item count
separately from its eligible usage records. Single-food association is based on
that full count; eligible foods are still remembered independently. Regressions
cover the fallback-plus-new-food case and a newly entered single food receiving
its saved library ID. The history schema is unchanged.

All 373 targeted journal/food tests and the final arm64 Mobile debug APK build passed,
with UI guardrails and whitespace checks also passing.

## Library controls and file actions

The library now uses the existing master switch card. Its shorter Open Food Facts
row and Add food action sit above search, so a large saved-food list cannot bury
them. Import and export moved into a compact bottom sheet opened from the pinned
top-right toolbar action. Import remains available with an empty or disabled
library; export requires a saved, active food. Source copy was shortened in all
16 supported translations.

All nine focused library, picker and sheet-height UI tests passed. The new tests
exercise a 100-food library, control ordering, master-switch behavior, the toolbar
action after scrolling, both file callbacks and empty-library export gating. The
arm64 Mobile debug APK built and was installed on the Pixel. UI guardrails, string
XML parsing and whitespace checks passed. Final device interaction stopped when
the Pixel entered the screenshot editor; the new transfer sheet was verified by
the rendered UI tests rather than claiming a completed physical-device check.

## Keyboard dismissal and sheet motion

The Pixel reproduction exposed two separate size problems. A temporary keyboard
padding measurement could permanently lock the short journal form at full height;
then, even after removing that lock, its content-height spring lagged behind the
disappearing keyboard padding and pulled the sheet down and back up.

The journal now reserves the top safe inset outside the moving sheet surface,
keeping a stable viewport without a permanent minimum height. Content changes use
a spring without overshoot when the keyboard is absent. While the keyboard is
present its own inset animation controls sizing directly, and the content-size
animation starts fresh when the keyboard finishes closing. Other sheets retain
their existing default sizing policy.

Rendered regressions reproduce both failures: the old lock retained 3,000 px for
a 1,404 px form, and the competing keyboard/content animations briefly shrank a
3,000 px sheet to 1,700 px. Additional checks cover tall-to-short content while
insets remain, returning from food search to Glucose, and frame-by-frame type
transitions without a height rebound.

Validation: all 78 related journal/component tests passed, with zero skips,
failures or errors. The arm64 Mobile debug APK built and was installed on the
Pixel. UI guardrails and whitespace checks passed. The gap fix was checked on
the Pixel across search dismissal, food selection, quantity-keyboard dismissal,
Glucose and Insulin. The final motion adjustment passes the rendered frame test;
its last physical-device check is pending because the Pixel is locked.
