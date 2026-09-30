#!/usr/bin/env python3
"""Checks that every literal key passed to L("...") in the Kotlin sources is in the string catalog
(core/model/src/main/resources/i18n/keys.txt, from scripts/sync-strings.py). A key that isn't there
shows in English in every language: fix the key (copy it from the iPhone app) or add the string to
the catalog on the iPhone side first.

Usage: scripts/check-strings.py [paths...]   (default: every module)
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEYS = os.path.join(ROOT, "core", "model", "src", "main", "resources", "i18n", "keys.txt")
CALL = re.compile(r'\bL\(\s*"((?:[^"\\]|\\.)*)"')


def unescape(s: str) -> str:
    return (s.replace('\\"', '"').replace("\\$", "$").replace("\\n", "\n").replace("\\t", "\t")
             .replace("\\'", "'").replace("\\\\", "\\"))


def main():
    keys = set()
    with open(KEYS, encoding="utf-8") as f:
        for line in f:
            keys.add(line.rstrip("\n").replace("\\n", "\n"))
    paths = sys.argv[1:] or [os.path.join(ROOT, d) for d in ("app", "core")]
    missing = []
    for base in paths:
        for dirpath, _, files in os.walk(base):
            if "/build/" in dirpath + "/":
                continue
            for name in files:
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, name)
                text = open(path, encoding="utf-8").read()
                for m in CALL.finditer(text):
                    raw = m.group(1)
                    if "${" in raw or re.search(r"\$[a-zA-Z_]", raw):
                        missing.append((path, text.count("\n", 0, m.start()) + 1, raw + "  (string template: pass arguments instead)"))
                        continue
                    key = unescape(raw)
                    if key not in keys:
                        missing.append((path, text.count("\n", 0, m.start()) + 1, key))
    for path, line, key in missing:
        print(f"{os.path.relpath(path, ROOT)}:{line}: not in the catalog: {key!r}")
    if missing:
        print(f"\n{len(missing)} key(s) missing.")
        sys.exit(1)
    print("All L(...) keys are in the catalog.")


if __name__ == "__main__":
    main()
