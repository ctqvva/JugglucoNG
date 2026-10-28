# Historical root files

These files were moved here unchanged during the repository-root cleanup. They are retained for reference and recovery, outside the active build and CI paths.

| Files | Original purpose |
| --- | --- |
| `Makefile`, `addgit.sh`, `mkgit.sh`, `dirs.0`, `dirs.lines` | Original Git staging generator, generated staging commands, and directory inventories. `dirs.0` uses NUL separators; `dirs.lines` is the readable form. |
| `build.gradle.werktniet` | Old alternate Gradle configuration with Android plugin 7.x and Kotlin 1.6.10; the active build uses the root `build.gradle`. |
| `upstream_strings.xml` | Historical upstream string-resource snapshot; it is not an Android source-set resource. |
| `diagnose_strings.py`, `inspect_strings.py` | One-off diagnostics for specific French resource keys. |
| `fix_html_xml.py`, `remove_bad_strings.py` | One-off resource rewrites and removal of specific French string definitions. |

Do not run these as current maintenance tools. The staging commands include obsolete paths and local configuration files. The Python scripts hard-code `/Users/ctqwa/Dev/Android/JugglucoNG`, and the two rewrite scripts can change that separate checkout. The archived Makefile expects the original root layout and GNU `find`. Review and adapt any archived tool before reusing it.

Current build helpers are in [scripts/](../../../scripts/README.md). Both root screenshots were preserved under [docs/assets/](../../../docs/assets/); the main README uses `juggluco_screenshot.png`.
