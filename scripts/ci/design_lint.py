#!/usr/bin/env python3
"""Design rules drafft's code must keep (DESIGN.md, PRODUCT.md), checked on every change.
The rules and rule names are drafft's (DESIGN.md), checked in their Compose form; the names are the ones
`design-lint: allow <rule>` takes.

A line may opt out of one rule with a reason, on that line or the line above:
    // design-lint: allow <rule> - <why>

Exit 1 on any violation. No dependency beyond Python 3.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCES = [ROOT / "app" / "src", *sorted((ROOT / "core").glob("*/src"))]
# Tokens and primitives are where raw values are allowed to live.
DESIGN_SYSTEM = ROOT / "core" / "ui"

RULES = {
    "middle-dot": (
        re.compile(r"·"),
        "No '·' separators: use layout (spacing, rows) or words.",
    ),
    "gradient": (
        re.compile(r"\bBrush\.(linear|radial|sweep|vertical|horizontal)Gradient\b|\b(Linear|Radial|Sweep)Gradient\("),
        "Solid fills only. Gradients are for photo scrims and blur masks: annotate them.",
    ),
    # Compose only cuts with "…" when it's asked for, so every cut is visible.
    "truncation": (
        re.compile(r"TextOverflow\.(Ellipsis|StartEllipsis|MiddleEllipsis)\b|\+\s*\"(…|\.\.\.)\"|\bellipsize\("),
        "No '…' on UI copy: wrap, reflow (AdaptiveRow/FirstThatFits), then scale. "
        "People's content (names, messages, bios) may: annotate it.",
    ),
    "brand-case": (
        re.compile(r"\"[^\"\n]*\b(Drafft|DRAFFT)\b[^\"\n]*\""),
        "The brand is 'drafft', lowercase, in every string people see.",
    ),
    "raw-color": (
        re.compile(r"\bColor\(\s*(0x|red\s*=|\d)|\.parseColor\(|\bColor\.a?rgb\(|\bColorStateList\.valueOf\("),
        "Colors come from DS.palette, not raw values.",
    ),
    "debug-output": (
        re.compile(r"(?<![\w.])(println|print)\(|\.printStackTrace\(|\bSystem\.(out|err)\b"),
        "No console output in shipped code: use android.util.Log if it must stay.",
    ),
    "secret": (
        re.compile(r"sb_secret_[A-Za-z0-9]|service_role\s*=|-----BEGIN [A-Z ]*PRIVATE KEY"),
        "Never a secret in the app: only public (publishable) keys.",
    ),
    # DrafftSheet puts every sheet on its own surface.
    "sheet-surface": (
        re.compile(r"\b(ModalBottomSheet|BottomSheetScaffold|AlertDialog|BasicAlertDialog)\("),
        "Sheets never share the page colour: present them with DrafftSheet (confirmations: DrafftConfirm).",
    ),
}
# Rules that don't apply inside the design system itself.
DESIGN_SYSTEM_EXEMPT = {"gradient", "raw-color", "sheet-surface"}

ALLOW = re.compile(r"design-lint:\s*allow\s+([\w-]+)")


def code_part(line: str) -> str:
    """The line without its // comment (string contents containing // are rare enough here)."""
    stripped = line.lstrip()
    if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
        return ""
    return re.sub(r"(?<!:)//.*$", "", line)


def allowed(lines: list[str], i: int, rule: str) -> bool:
    for j in (i, i - 1):
        if j >= 0:
            m = ALLOW.search(lines[j])
            if m and m.group(1) == rule:
                return True
    return False


def shipped(path: pathlib.Path) -> bool:
    parts = path.relative_to(ROOT).parts
    return "build" not in parts and not any(p in ("test", "androidTest") for p in parts)


def main() -> int:
    problems: list[str] = []
    for base in SOURCES:
        for path in sorted(base.rglob("*.kt")):
            if not shipped(path):
                continue
            rel = path.relative_to(ROOT)
            in_ds = DESIGN_SYSTEM in path.parents
            lines = path.read_text(encoding="utf-8").splitlines()
            for i, line in enumerate(lines):
                code = code_part(line)
                if not code:
                    continue
                for rule, (pattern, why) in RULES.items():
                    if in_ds and rule in DESIGN_SYSTEM_EXEMPT:
                        continue
                    if pattern.search(code) and not allowed(lines, i, rule):
                        problems.append(f"{rel}:{i + 1}: [{rule}] {why}\n    {line.strip()}")

    # Strings people see that live outside Kotlin: the app's name per flavor and Android resources.
    outside = [ROOT / "app" / "build.gradle.kts"]
    outside += [p for p in ROOT.glob("*/src/*/AndroidManifest.xml")] + list(ROOT.glob("core/*/src/*/AndroidManifest.xml"))
    outside += [p for p in ROOT.glob("**/src/*/res/values*/*.xml") if shipped(p)]
    for path in sorted(set(outside)):
        for i, line in enumerate(path.read_text(encoding="utf-8").splitlines()):
            if re.search(r"\"app_name\"|android:label|<string\b", line) and re.search(r"\b(Drafft|DRAFFT)\b", line):
                problems.append(
                    f"{path.relative_to(ROOT)}:{i + 1}: [brand-case] The brand is 'drafft', lowercase.\n    {line.strip()}"
                )

    for p in problems:
        print(p)
    print(f"design-lint: {len(problems)} problem(s)." if problems else "design-lint: clean.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
