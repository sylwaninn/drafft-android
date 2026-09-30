#!/usr/bin/env python3
"""Copies the iPhone app's string catalog (drafft/Drafft/Resources/Localizable.xcstrings) into the
Android app: one JSON table per language in core/model/src/main/resources/i18n/, read by `L(...)`.

Both apps share the same keys (the English source text) and the same wording (WORDING.md), so the
catalog stays the single source. Placeholders are converted to Java's: %@ -> %s, %lld -> %d, with
positions kept (%1$@ -> %1$s).

Usage: scripts/sync-strings.py [path/to/Localizable.xcstrings]
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT = os.path.join(os.path.dirname(ROOT), "drafft", "Drafft", "Resources", "Localizable.xcstrings")
OUT = os.path.join(ROOT, "core", "model", "src", "main", "resources", "i18n")
LANGS = ["en", "fr", "es", "de", "it", "pt", "nl"]

SPEC = re.compile(r"%(\d+\$)?(@|lld|ld|d|lf|f|\.\d+f|%)")


def java(text: str) -> str:
    def repl(m):
        pos, kind = m.group(1) or "", m.group(2)
        if kind == "%":
            return "%%"
        if kind == "@":
            return f"%{pos}s"
        if kind in ("lld", "ld", "d"):
            return f"%{pos}d"
        return f"%{pos}{kind.replace('l', '')}"
    return SPEC.sub(repl, text)


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    catalog = json.load(open(src, encoding="utf-8"))["strings"]
    os.makedirs(OUT, exist_ok=True)
    tables = {lang: {} for lang in LANGS}
    for key, entry in catalog.items():
        if not key.strip():
            continue
        jkey = java(key)
        for lang in LANGS:
            unit = entry.get("localizations", {}).get(lang, {}).get("stringUnit")
            if unit and unit.get("value") is not None:
                value = java(unit["value"])
            elif lang == "en":
                value = jkey
            else:
                continue
            # English equal to its key is implied: `L` falls back to the key.
            if lang == "en" and value == jkey:
                continue
            tables[lang][jkey] = value
    for lang, table in tables.items():
        path = os.path.join(OUT, f"{lang}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(dict(sorted(table.items())), f, ensure_ascii=False, indent=0, separators=(",", ":"))
            f.write("\n")
        print(f"{lang}: {len(table)} strings -> {os.path.relpath(path, ROOT)}")
    # Every key, for reference and for the key check (scripts/check-strings.py).
    with open(os.path.join(OUT, "keys.txt"), "w", encoding="utf-8") as f:
        for key in sorted(java(k) for k in catalog if k.strip()):
            f.write(key.replace("\n", "\\n") + "\n")


if __name__ == "__main__":
    main()
