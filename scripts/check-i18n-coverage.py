#!/usr/bin/env python3
"""Fail when a key in a module's English strings.xml is missing from a locale.

Android's own `MissingTranslation` lint covers this, but it is downgraded to a
warning in build.gradle.kts and `lintDebug` only runs on PRs — so direct pushes
to main ship untranslated keys unnoticed. That is how 37 keys drifted out of all
11 locales at once (feature/keys 19, feature/settings 13, feature/rdp 5).

This runs in the fast per-push `checks` job instead: pure stdlib XML, ~1s.

`translatable="false"` keys are exempt, as are modules with no values-<loc>/
directory at all (a module that ships no translations is not "drifting").
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LOCALES = ["ar", "bn", "de", "es", "fr", "hi", "ja", "ko", "pt", "ru", "zh"]


def keys(path):
    """Translatable resource names in one strings.xml, or None if absent."""
    if not path.exists():
        return None
    root = ET.parse(path).getroot()
    return {
        el.get("name")
        for el in root
        if el.tag in ("string", "plurals", "string-array")
        and el.get("translatable") != "false"
    }


def main():
    missing = 0
    for source in sorted(REPO.glob("**/src/main/res/values/strings.xml")):
        res = source.parent.parent
        module = str(res.relative_to(REPO)).replace("/src/main/res", "")
        english = keys(source)
        if not english:
            continue
        for locale in LOCALES:
            translated = keys(res / f"values-{locale}/strings.xml")
            if translated is None:
                continue  # ponytail: module ships no translations at all — not drift
            gap = sorted(english - translated)
            if gap:
                missing += len(gap)
                print(f"✖ {module} values-{locale}: {len(gap)} missing — {', '.join(gap)}")

    if missing:
        print(
            f"\n{missing} missing translations. Add each key to the locale's "
            f"strings.xml (translated, not copied from English), then rerun "
            f"scripts/i18n_export.py and commit docs/i18n/strings.json."
        )
        return 1
    print("✓ Every English key is present in all 11 locales.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
