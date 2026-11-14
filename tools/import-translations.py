"""Generates app/src/main/res/values-*/strings.xml from upstream Moblin's string catalog.

Every English string in app/src/main/res/values/strings.xml whose text exactly matches an English
source string in upstream/Common/Localizable.xcstrings (MIT, see NOTICE) gets upstream's
translations. Strings without a match fall back to English. Only entries upstream marks as
"translated" are used; the "eo" pseudo-locale is skipped.

Format arguments: Android's positional %1$s / %1$d are matched against upstream's %@ / %lld (in
order), and a translation is converted back only if it uses exactly the same arguments with the
same types, so the generated strings stay valid Android format strings. Anything else falls back to
English.

Usage: python tools/import-translations.py [--report]
Re-run after changing strings.xml or updating the upstream submodule. Do not edit the generated
files by hand.
"""

import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "upstream", "Common", "Localizable.xcstrings")
RES = os.path.join(ROOT, "app", "src", "main", "res")

# Upstream locale -> Android resource folder qualifier.
LOCALES = {
    "de": "de", "es": "es", "fi": "fi", "fr": "fr", "hi": "hi", "id": "in", "it": "it", "ja": "ja",
    "ko": "ko", "nl": "nl", "pl": "pl", "pt-BR": "pt-rBR", "pt-PT": "pt-rPT", "ru": "ru", "sk": "sk",
    "sl": "sl", "sv": "sv", "tr": "tr", "uk": "uk", "vi": "vi", "zh-HK": "zh-rHK",
    "zh-Hans": "b+zh+Hans", "zh-Hant": "b+zh+Hant", "zh-Hant-TW": "b+zh+Hant+TW",
}

# Strings that must stay as they are (names, protocols).
UNTRANSLATABLE = {"app_name", "srt", "iso"}

# Upstream translations that are wrong and fall back to English: (upstream locale, string name).
EXCLUDED = {
    ("de", "connection_priorities_not_supported"),  # No space after the placeholder ("%@nicht").
    ("nl", "obs_fix_source"),  # Typo: "Born" for "Bron".
    ("zh-HK", "hype_train_level"),  # "水平" is level as in standard, not a hype train level.
}

ANDROID_ARG = re.compile(r"%(\d+)\$([sd])")
XCODE_ARG = re.compile(r"%%|%(?:(\d+)\$)?(@|lld|ld|d)")
XCODE_TYPES = {"@": "s", "lld": "d", "ld": "d", "d": "d"}


def android_escape(text):
    text = text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    text = text.replace("\n", "\\n").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    if text.startswith(("@", "?")):
        text = "\\" + text
    return text


def android_unescape(text):
    return re.sub(r"\\(.)", lambda m: {"n": "\n"}.get(m.group(1), m.group(1)), text)


def android_args(text):
    """[(index, type), ...] for a format string, [] for plain text, None if not convertible."""
    args = [(int(m.group(1)), m.group(2)) for m in ANDROID_ARG.finditer(text)]
    if not args:
        return []
    if [index for index, _ in args] != list(range(1, len(args) + 1)):
        return None
    if "%" in ANDROID_ARG.sub("", text).replace("%%", ""):
        return None
    return args


def catalog_key(text):
    """Upstream source string for an English Android string, or None."""
    args = android_args(text)
    if args is None:
        return None
    return ANDROID_ARG.sub(lambda m: "%@" if m.group(2) == "s" else "%lld", text)


def android_format(value, args):
    """Converts a translation's %@ / %lld to %N$s / %N$d, or None if its arguments differ."""
    used = []

    def convert(match):
        if match.group(0) == "%%":
            return "%%"
        index = int(match.group(1)) if match.group(1) else len(used) + 1
        kind = XCODE_TYPES[match.group(2)]
        used.append((index, kind))
        return f"%{index}${kind}"

    converted = XCODE_ARG.sub(convert, value)
    if sorted(used) != sorted(args):
        return None
    if "%" in ANDROID_ARG.sub("", converted).replace("%%", ""):
        return None
    return converted


def main():
    catalog = json.load(open(CATALOG, encoding="utf-8"))["strings"]
    english = [
        (element.get("name"), android_unescape(element.text or ""))
        for element in ET.parse(os.path.join(RES, "values", "strings.xml")).getroot()
        if element.tag == "string" and element.get("translatable") != "false"
    ]
    matched = {}
    args = {}
    for name, text in english:
        if name in UNTRANSLATABLE:
            continue
        key = catalog_key(text)
        entry = catalog.get(key) if key is not None else None
        if entry is not None:
            matched[name] = entry.get("localizations", {})
            args[name] = android_args(text)
    for locale, qualifier in LOCALES.items():
        rows = []
        for name, _ in english:
            if (locale, name) in EXCLUDED:
                continue
            unit = matched.get(name, {}).get(locale, {}).get("stringUnit", {})
            if unit.get("state") == "translated" and unit.get("value"):
                value = unit["value"]
                if args[name]:
                    value = android_format(value, args[name])
                    if value is None:
                        continue
                rows.append(f'    <string name="{name}">{android_escape(value)}</string>')
        folder = os.path.join(RES, f"values-{qualifier}")
        path = os.path.join(folder, "strings.xml")
        if not rows:
            if os.path.exists(path):
                os.remove(path)
            continue
        os.makedirs(folder, exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n')
            f.write("<!-- Generated by tools/import-translations.py from upstream Moblin's Localizable.xcstrings (MIT). Do not edit. -->\n")
            f.write("<resources>\n" + "\n".join(rows) + "\n</resources>\n")
    # Per-app language picker (Android 13+): English plus every generated locale.
    tags = ["en"] + [
        locale for locale, qualifier in LOCALES.items()
        if os.path.exists(os.path.join(RES, f"values-{qualifier}", "strings.xml"))
    ]
    os.makedirs(os.path.join(RES, "xml"), exist_ok=True)
    with open(os.path.join(RES, "xml", "locales_config.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write("<!-- Generated by tools/import-translations.py. Do not edit. -->\n")
        f.write('<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n')
        f.write("".join(f'    <locale android:name="{tag}" />\n' for tag in tags))
        f.write("</locale-config>\n")
    if "--report" in sys.argv:
        print(f"{len(matched)} of {len(english)} strings have upstream translations:")
        for name, text in english:
            print(("  + " if name in matched else "  - ") + f"{name}: {text[:70]}")


main()
