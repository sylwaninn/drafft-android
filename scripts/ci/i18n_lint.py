#!/usr/bin/env python3
"""Translation checks for drafft-android's strings, on every change.
For the tables scripts/sync-strings.py writes.

Fails when a string people see is missing a language, loses or gains a placeholder, capitalises the
brand, uses a '·' or a wording WORDING.md forbids (its `wording-forbidden` block), or when the Android
wording of a platform sentence (i18n/android/) no longer matches a catalog key or misses a language.
Warns when a short English string grows much longer in another language. The code's L("…") keys are
checked by scripts/check-strings.py.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
I18N = ROOT / "core/model/src/main/resources/i18n"
LANGUAGES = ["en", "fr", "es", "de", "it", "pt", "nl"]
PLACEHOLDER = re.compile(r"%(?:\d+\$)?(s|d|f|\.\d+f|%)")
WORD = re.compile(r"[A-Za-zÀ-ÿ]{2,}")
# A plural variation of a key ("%d years ago|one"), written by scripts/sync-strings.py next to the key itself.
PLURAL_FORM = re.compile(r"^(.*)\|(zero|one|two|few|many)$", re.S)
# Copy that lives outside the catalog but still reaches people.
HARD_CODED_COPY = [ROOT / "core/data/src/main/kotlin/so/drafft/core/data/notifications/NotificationText.kt"]


def forbidden_wording() -> list[tuple[re.Pattern, str]]:
    """The `pattern | reason` lines of WORDING.md's wording-forbidden block."""
    block = re.search(r"```wording-forbidden\n(.*?)```", (ROOT / "WORDING.md").read_text(encoding="utf-8"), re.S)
    rules = []
    for line in block.group(1).splitlines() if block else []:
        pattern, _, reason = line.rpartition(" | ")
        if pattern:
            rules.append((re.compile(pattern, re.I), reason))
    return rules


FORBIDDEN = forbidden_wording()


def check_wording(where: str, value: str, errors: list[str]) -> None:
    for pattern, reason in FORBIDDEN:
        if pattern.search(value):
            errors.append(f"{where}: {reason}, see WORDING.md: {value!r}")


def placeholders(text: str) -> list[str]:
    return sorted(m.group(1) for m in PLACEHOLDER.finditer(text) if m.group(1) != "%")


def check_value(where: str, source: str, value: str, lang: str, errors: list[str], warnings: list[str]) -> None:
    if placeholders(value) != placeholders(source):
        errors.append(f"{where}: placeholders {placeholders(value)} != source {placeholders(source)}")
    if re.search(r"\b(Drafft|DRAFFT)\b", value):
        errors.append(f"{where}: the brand is 'drafft', lowercase: {value!r}")
    if "·" in value:
        errors.append(f"{where}: no '·' separators: {value!r}")
    check_wording(where, value, errors)
    if lang != "en" and len(source) <= 24 and len(value) > max(2.2 * len(source), len(source) + 16):
        warnings.append(f"{where}: short English grew to {len(value)} chars: {value!r}")


def load(path: pathlib.Path) -> dict[str, str]:
    return json.loads(path.read_text(encoding="utf-8"))


def check_catalog(errors: list[str], warnings: list[str]) -> set[str]:
    """The tables copied from the shared catalog. English equal to its key is left out of en.json."""
    keys = {line.rstrip("\n").replace("\\n", "\n") for line in (I18N / "keys.txt").open(encoding="utf-8")}
    tables = {lang: load(I18N / f"{lang}.json") for lang in LANGUAGES}
    for lang, table in tables.items():
        for key in sorted(set(table) - keys):
            form = PLURAL_FORM.match(key)
            if form and form.group(1) in keys:
                source = tables["en"].get(form.group(1), form.group(1))
                check_value(f"i18n/{lang}.json: {key!r}", source, table[key], lang, errors, warnings)
                continue
            errors.append(f"i18n/{lang}.json: key not in keys.txt (run scripts/sync-strings.py): {key!r}")
    for key in sorted(keys):
        # Keys without words ("%s %s", "2×") need no translation.
        if not WORD.search(PLACEHOLDER.sub("", key)):
            continue
        # The key is the English source: no forbidden word there either, even when `en` overrides it.
        check_wording(f"keys.txt: {key!r}", key, errors)
        source = tables["en"].get(key, key)
        for lang in LANGUAGES:
            where = f"i18n/{lang}.json: {key!r}"
            if lang != "en" and key not in tables[lang]:
                errors.append(f"{where}: missing {lang} (translate it in drafft's catalog, then sync)")
                continue
            check_value(where, source, tables[lang].get(key, key), lang, errors, warnings)
    return keys


def check_android_variants(keys: set[str], errors: list[str], warnings: list[str]) -> None:
    """The Android wording of the catalog's Apple-platform sentences: same keys in the 7 languages."""
    tables = {lang: load(I18N / "android" / f"{lang}.json") for lang in LANGUAGES}
    every = set().union(*tables.values())
    for key in sorted(every):
        if key not in keys:
            errors.append(f"i18n/android: not a catalog key (the catalog sentence changed?): {key!r}")
        source = tables["en"].get(key, key)
        for lang in LANGUAGES:
            where = f"i18n/android/{lang}.json: {key!r}"
            if key not in tables[lang]:
                errors.append(f"{where}: missing {lang}")
                continue
            check_value(where, source, tables[lang][key], lang, errors, warnings)


def main() -> int:
    errors: list[str] = []
    warnings: list[str] = []
    keys = check_catalog(errors, warnings)
    check_android_variants(keys, errors, warnings)
    for path in HARD_CODED_COPY:
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for literal in re.findall(r'"((?:[^"\\]|\\.)*)"', line):
                check_wording(f"{path.relative_to(ROOT)}:{n}", literal, errors)
    for w in warnings:
        print(f"warning: {w}")
    for e in errors:
        print(f"error: {e}")
    print(f"i18n-lint: {len(errors)} error(s), {len(warnings)} warning(s).")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
