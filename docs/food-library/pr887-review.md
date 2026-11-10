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
