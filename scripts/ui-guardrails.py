#!/usr/bin/env python3
"""Ratchet for the mechanical rules in docs/ui-style-guide.md.

Counts each pattern across the phone Compose UI and compares it with
scripts/ui-guardrails-baseline.json. A count may fall; it may not rise. After
removing debt, run with --update to lower the baseline in the same change.

    python3 scripts/ui-guardrails.py            # check (exit 1 on a regression)
    python3 scripts/ui-guardrails.py --update   # rewrite the baseline
    python3 scripts/ui-guardrails.py --list RULE  # where a rule's hits are
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI = os.path.join(ROOT, "Common/src/mobile/java/tk/glucodata/ui")
BASELINE = os.path.join(ROOT, "scripts/ui-guardrails-baseline.json")

SPACING_CONTEXT = re.compile(
    r"(\.padding\(|PaddingValues\(|spacedBy\(|Spacer\(\s*(?:modifier\s*=\s*)?Modifier\s*\.\s*(?:width|height|size)\()"
)
DP = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)\.dp\b")
RADIUS_SCALE = {0, 4, 8, 12, 16, 20, 28, 32, 48}


def _closing(text, start):
    depth = 1
    for i in range(start, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return len(text)


def offgrid_spacing(text):
    hits = []
    for m in SPACING_CONTEXT.finditer(text):
        seg = text[m.end():_closing(text, m.end())]
        for v in DP.findall(seg):
            v = float(v)
            if v not in (0, 1, 2) and v % 4 != 0:
                hits.append(m.start())
    return hits


def offscale_radius(text):
    hits = []
    for m in re.finditer(r"RoundedCornerShape\(", text):
        seg = text[m.end():_closing(text, m.end())]
        for v in DP.findall(seg):
            if float(v) not in RADIUS_SCALE:
                hits.append(m.start())
    return hits


def pattern(rx, exclude=()):
    compiled = re.compile(rx)

    def rule(text, path):
        if any(path.endswith(e) for e in exclude):
            return []
        return [m.start() for m in compiled.finditer(text)]

    return rule


RULES = {
    # Spacing must sit on the 4dp grid (2dp is allowed for grouped rows).
    "offgrid_spacing": lambda text, path: offgrid_spacing(text),
    # Corner radii come from the M3 Expressive shape scale.
    "offscale_radius": lambda text, path: offscale_radius(text),
    # Hex colours: theme roles for UI, GlucoseRangeColors for glucose.
    "hex_color": pattern(r"Color\(0x[0-9A-Fa-f]+"),
    # Use isAppInDarkTheme(); the in-app Theme setting can disagree with the system.
    "system_dark_theme": pattern(r"(?<![\w.])isSystemInDarkTheme\(\)", exclude=("JugglucoTheme.kt",)),
    # collectAsStateWithLifecycle() unless the composable lives in a service window.
    "collect_as_state": pattern(r"\.collectAsState\("),
    # Every user-visible string is a translated resource.
    "text_literal": pattern(r"(?<![\w.])Text\(\s*(?:text\s*=\s*)?\"[A-Za-z]"),
    "content_description_literal": pattern(r"contentDescription\s*=\s*\"[A-Za-z]"),
    # Pushed screens use AppTopBar.
    "hand_built_top_bar": pattern(r"(?<![\w.])TopAppBar\(", exclude=("components/AppTopBar.kt",)),
    # Emphasis is a style (titleMediumEmphasized...), not a call-site weight.
    "font_weight_override": pattern(r"fontWeight\s*=\s*FontWeight\.(?:Medium|SemiBold|Bold)\b", exclude=("theme/Type.kt",)),
}


def scan():
    counts = {name: 0 for name in RULES}
    where = {name: [] for name in RULES}
    for dirpath, _, files in os.walk(UI):
        for name in sorted(files):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, UI)
            with open(path, encoding="utf-8") as f:
                text = f.read()
            for rule, fn in RULES.items():
                for offset in fn(text, rel):
                    counts[rule] += 1
                    where[rule].append(f"{rel}:{text.count(chr(10), 0, offset) + 1}")
    return counts, where


def main(argv):
    counts, where = scan()
    if "--list" in argv:
        rule = argv[argv.index("--list") + 1]
        print("\n".join(where[rule]))
        return 0
    if "--update" in argv:
        with open(BASELINE, "w", encoding="utf-8") as f:
            json.dump(counts, f, indent=2, sort_keys=True)
            f.write("\n")
        print("baseline updated")
        return 0
    with open(BASELINE, encoding="utf-8") as f:
        baseline = json.load(f)
    failed = False
    for rule, count in sorted(counts.items()):
        allowed = baseline.get(rule, 0)
        mark = "ok"
        if count > allowed:
            mark = "REGRESSED"
            failed = True
        elif count < allowed:
            mark = "improved (run --update)"
        print(f"{rule:30} {count:5} / {allowed:5}  {mark}")
    if failed:
        print("\nA UI guardrail count went up. See docs/ui-style-guide.md; "
              "`--list <rule>` shows where the hits are.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
