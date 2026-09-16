#!/usr/bin/env python3
"""Static test-coverage map: source types/functions vs. references in test sources.

Not line coverage. A source type counts as "referenced" if a test source mentions
its simple name as a whole word (imports or usage). Functions are listed per type
and inherit the type's referenced flag.
"""
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SRC_ROOTS = [
    "Common/src/main/java",
    "Common/src/mobile/java",
    "Common/src/mobileSi/java",
    "Common/src/mobileNosi/java",
    "Common/src/mobileSiGoogle/java",
    "Common/src/mobileSiNogoogle/java",
    "Common/src/nosi/java",
    "Common/src/small/java",
    "Common/src/dex/java",
    "Common/src/libre3/java",
    "Common/src/libreOld/java",
    "Common/src/wear/java",
    "Common/src/wearSi/java",
]

TEST_ROOTS = ["Common/src/test/java", "Common/src/testMobile/java"]

KW_MOD = r"(?:public\s+|internal\s+|private\s+|protected\s+|open\s+|abstract\s+|sealed\s+|data\s+|enum\s+|annotation\s+|value\s+|inline\s+|suspend\s+|override\s+|operator\s+|external\s+|expect\s+|actual\s+|tailrec\s+|const\s+|lateinit\s+|companion\s+)*"

KT_TYPE = re.compile(KW_MOD + r"(class|interface|object|enum\s+class|data\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)")
KT_FUN = re.compile(KW_MOD + r"fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][A-Za-z0-9_.<>?]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(")
JAVA_TYPE = re.compile(r"(?:public\s+|final\s+|abstract\s+|static\s+|sealed\s+)*(class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)")
JAVA_METHOD = re.compile(r"^\s*(?:public|protected)\s+(?:static\s+|final\s+|synchronized\s+|abstract\s+|native\s+|default\s+)*[\w<>\[\],.\s?]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")


def rel(path):
    return os.path.relpath(path, ROOT)


VENDORED = [
    "com/microtechmd/",
    "com/eveningoutpost/",
    "ist/com/",
    "yuku/ambilwarna/",
    "nodomain/freeyourgadget/",
]


def iter_sources():
    for r in SRC_ROOTS:
        base = os.path.join(ROOT, r)
        for dirpath, _, files in os.walk(base):
            rel_dir = rel(dirpath) + "/"
            if any(v in rel_dir for v in VENDORED):
                continue
            for f in files:
                if f.endswith(".kt") or f.endswith(".java"):
                    yield os.path.join(dirpath, f)


def extract_source(path):
    """Return (classes, functions) where functions is a list of (owner, name)."""
    text = open(path, encoding="utf-8", errors="ignore").read()
    lines = text.splitlines()
    classes = []
    funcs = []
    current = None
    is_kt = path.endswith(".kt")
    depth = 0
    for line in lines:
        stripped = line.strip()
        if is_kt:
            m = KT_TYPE.search(line)
            if m:
                name = m.group(2)
                classes.append(name)
                if depth <= 1:
                    current = name
            if KT_FUN.search(line):
                fn = KT_FUN.search(line).group(1)
                if fn not in ("if", "for", "while", "when", "catch", "return", "constructor"):
                    funcs.append((current, fn))
        else:
            m = JAVA_TYPE.search(line)
            if m:
                classes.append(m.group(2))
                if depth <= 1:
                    current = m.group(2)
            mm = JAVA_METHOD.match(line)
            if mm and mm.group(1) not in ("if", "for", "while", "switch", "catch", "return"):
                funcs.append((current, mm.group(1)))
        depth += line.count("{") - line.count("}")
    return classes, funcs


def load_tests():
    texts = []
    files = []
    for r in TEST_ROOTS:
        base = os.path.join(ROOT, r)
        for dirpath, _, fs in os.walk(base):
            for f in fs:
                if f.endswith(".kt") or f.endswith(".java"):
                    p = os.path.join(dirpath, f)
                    files.append(rel(p))
                    texts.append(open(p, encoding="utf-8", errors="ignore").read())
    return "\n".join(texts), files


def package_of(path):
    d = os.path.dirname(rel(path))
    for r in SRC_ROOTS:
        if d == r or d.startswith(r + "/"):
            rem = d[len(r):].strip("/")
            return rem.replace("/", ".") if rem else "(default)"
    return d


def main():
    test_text, test_files = load_tests()
    words = set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", test_text))
    names_blob = " ".join(os.path.basename(f) for f in test_files)
    name_words = set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", names_blob))
    imports = set()
    for m in re.finditer(r"import\s+([\w.]+)", test_text):
        imports.add(m.group(1).rsplit(".", 1)[-1])

    def is_referenced(t):
        if t in words or t in imports:
            return True
        # Test classes are named <SourceClass>Tests / <SourceClass>Test.
        if t + "s" in name_words or t + "Tests" in name_words or t + "Test" in name_words:
            return True
        return any(w.startswith(t) and len(w) > len(t) for w in name_words if w.endswith("Tests") or w.endswith("Test"))

    file_data = {}
    for p in iter_sources():
        try:
            classes, funcs = extract_source(p)
        except Exception as e:
            print("ERR", p, e, file=sys.stderr)
            continue
        if not classes and not funcs:
            continue
        file_data[rel(p)] = {
            "pkg": package_of(p),
            "types": sorted(set(classes)),
            "funcs": sorted(set(fn for _, fn in funcs)),
        }

    covered, uncovered = [], []
    for rp, data in file_data.items():
        for t in data["types"]:
            (covered if is_referenced(t) else uncovered).append((data["pkg"], t, rp))

    lines = []
    lines.append("# Test coverage map (static, main branch)\n")
    lines.append("Generated by `scripts/test_coverage_map.py`. A type counts as referenced when its")
    lines.append("simple name appears in a test source (import or usage). This is a rough proxy for")
    lines.append("class-level test presence, not line coverage.\n")
    lines.append(f"- Source types: {len(covered) + len(uncovered)}")
    lines.append(f"- Referenced by tests: {len(covered)}")
    lines.append(f"- Not referenced: {len(uncovered)}")
    lines.append(f"- Test files: {len(test_files)}")
    lines.append("")
    lines.append("## Coverage by area (top package)\n")
    lines.append("| area | referenced | unreferenced | total |")
    lines.append("|------|-----------:|-------------:|------:|")
    area = defaultdict(lambda: [0, 0])
    for pkg, t, p in covered:
        area[pkg][0] += 1
    for pkg, t, p in uncovered:
        area[pkg][1] += 1
    for k in sorted(area):
        r, u = area[k]
        lines.append(f"| `{k}` | {r} | {u} | {r + u} |")
    lines.append("")
    lines.append("## Unreferenced logic types (non-UI candidates)\n")
    ui_suffix = ("Activity", "Adapter", "Fragment", "Composable", "Canvas", "Dialog",
                 "Service", "Receiver", "Widget", "View", "Screen", "Sheet", "Card",
                 "Tile", "Icon", "Drawable", "Layout", "Holder", "Binding")
    for pkg, t, rp in sorted(uncovered, key=lambda x: (x[0], x[1])):
        if "/ui/" in rp or "/res/" in rp or t.endswith(ui_suffix):
            continue
        lines.append(f"- `{pkg}.{t}`  ({rp})")
    lines.append("")
    lines.append("## Unreferenced source types\n")
    un_java = defaultdict(list)
    for pkg, t, rp in sorted(uncovered, key=lambda x: (x[2], x[1])):
        un_java[rp].append((pkg, t))
    for rp, items in sorted(un_java.items()):
        lines.append(f"### {rp}")
        for pkg, t in items:
            lines.append(f"- `{pkg}.{t}`")
        lines.append("")
    lines.append("## Type / function map\n")
    lines.append("Functions are listed per file (declaration site), not per nested type.\n")
    for rp in sorted(file_data):
        data = file_data[rp]
        marks = "".join("x" if is_referenced(t) else " " for t in data["types"])
        lines.append(f"### {rp}")
        lines.append(f"package `{data['pkg']}` — types `{marks}`: {', '.join(data['types']) or '—'}")
        if data["funcs"]:
            lines.append("functions: " + ", ".join(f"`{fn}()`" for fn in data["funcs"]))
        lines.append("")
    out = os.path.join(ROOT, "docs", "test-coverage-map.md")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, "w").write("\n".join(lines))
    print(f"wrote {out}: {len(covered)} referenced / {len(uncovered)} unreferenced")


if __name__ == "__main__":
    main()
