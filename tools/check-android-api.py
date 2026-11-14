#!/usr/bin/env python3
"""Checks that compiled JVM classes only call Android platform APIs available at a minimum API level.

The pure Kotlin/JVM modules (transport, settings, chat, ...) run on the phone but compile against the
desktop JDK, and Android lint does not check them. A Java method newer than minSdk compiles fine and
then throws NoSuchMethodError on older phones: URLDecoder.decode(String, Charset) (API 33) left
SRT(LA) streams at "Connecting" on Android 10.

This reads every .class file in the given directories, collects the classes, methods and fields
they reference, and looks each one up in the Android SDK's api-versions.xml (the database Android
lint uses). It reports references that need a higher API level than --min-sdk, and references to
java.* / javax.* classes or members that Android does not have at all. Classes that are not part of
the platform (Kotlin's standard library, the project's own code, other libraries) are skipped.

Usage: check-android-api.py --api-versions <api-versions.xml> --min-sdk 29 <classes dir>...
Exit status 1 when anything is reported.
"""

import argparse
import os
import struct
import sys
import xml.etree.ElementTree as ElementTree

# Constant pool tags (JVMS 4.4) and the number of slots each entry takes.
UTF8, INTEGER, FLOAT, LONG, DOUBLE, CLASS, STRING = 1, 3, 4, 5, 6, 7, 8
FIELD_REF, METHOD_REF, INTERFACE_METHOD_REF, NAME_AND_TYPE = 9, 10, 11, 12
METHOD_HANDLE, METHOD_TYPE, DYNAMIC, INVOKE_DYNAMIC, MODULE, PACKAGE = 15, 16, 17, 18, 19, 20
FIXED_SIZES = {
    INTEGER: 4, FLOAT: 4, LONG: 8, DOUBLE: 8, CLASS: 2, STRING: 2, FIELD_REF: 4, METHOD_REF: 4,
    INTERFACE_METHOD_REF: 4, NAME_AND_TYPE: 4, METHOD_HANDLE: 3, METHOD_TYPE: 2, DYNAMIC: 4,
    INVOKE_DYNAMIC: 4, MODULE: 2, PACKAGE: 2,
}


def references(path):
    """The (kind, class, member) references in one class file; member is None for class references."""
    with open(path, "rb") as file:
        data = file.read()
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError(f"{path}: not a class file")
    count = struct.unpack_from(">H", data, 8)[0]
    entries = [None] * count
    offset = 10
    index = 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == UTF8:
            length = struct.unpack_from(">H", data, offset)[0]
            entries[index] = (UTF8, data[offset + 2:offset + 2 + length].decode("utf-8", "replace"))
            offset += 2 + length
        elif tag in FIXED_SIZES:
            size = FIXED_SIZES[tag]
            entries[index] = (tag, data[offset:offset + size])
            offset += size
            if tag in (LONG, DOUBLE):
                index += 1
        else:
            raise ValueError(f"{path}: unknown constant pool tag {tag}")
        index += 1

    def utf8(i):
        return entries[i][1]

    def class_name(i):
        return utf8(struct.unpack(">H", entries[i][1])[0])

    found = set()
    for entry in entries:
        if entry is None:
            continue
        tag, value = entry
        if tag == CLASS:
            found.add(("class", utf8(struct.unpack(">H", value)[0]), None))
        elif tag in (FIELD_REF, METHOD_REF, INTERFACE_METHOD_REF):
            owner, name_and_type = struct.unpack(">HH", value)
            name_index, descriptor_index = struct.unpack(">HH", entries[name_and_type][1])
            name, descriptor = utf8(name_index), utf8(descriptor_index)
            if tag == FIELD_REF:
                found.add(("field", class_name(owner), name))
            else:
                found.add(("method", class_name(owner), name + descriptor))
    return found


def level(text):
    """An API level such as "29" or "36.1" (minor SDK versions) as a number."""
    return float(text)


class Api:
    def __init__(self, path):
        self.classes = {}
        for element in ElementTree.parse(path).getroot().iter("class"):
            since = level(element.get("since", "1"))
            self.classes[element.get("name")] = {
                "since": since,
                "supers": [e.get("name") for e in element if e.tag in ("extends", "implements")],
                "methods": {e.get("name"): level(e.get("since", since)) for e in element.iter("method")},
                "fields": {e.get("name"): level(e.get("since", since)) for e in element.iter("field")},
            }

    def member_since(self, owner, kind, member):
        """The lowest API level at which [owner] (or a superclass or interface) has [member], or None."""
        table = "methods" if kind == "method" else "fields"
        best = None
        seen = set()
        pending = [owner]
        while pending:
            name = pending.pop()
            if name in seen or name not in self.classes:
                continue
            seen.add(name)
            info = self.classes[name]
            if member in info[table]:
                level = max(info[table][member], self.classes[owner]["since"])
                best = level if best is None else min(best, level)
                continue
            pending.extend(info["supers"])
        return best


# invokedynamic bootstraps (Kotlin lambdas, string templates): D8 rewrites them when it builds the
# APK, so the classes are never used on the phone.
DESUGARED = {"java/lang/invoke/LambdaMetafactory", "java/lang/invoke/StringConcatFactory"}


def is_jdk(name):
    return name.startswith("java/") or name.startswith("javax/")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--api-versions", required=True)
    parser.add_argument("--min-sdk", type=int, required=True)
    parser.add_argument("dirs", nargs="+")
    arguments = parser.parse_args()
    api = Api(arguments.api_versions)

    problems = {}
    checked = 0
    for directory in arguments.dirs:
        for root, _, files in os.walk(directory):
            for file in files:
                if not file.endswith(".class"):
                    continue
                path = os.path.join(root, file)
                checked += 1
                for kind, owner, member in references(path):
                    if owner.startswith("[") or owner in DESUGARED:
                        continue
                    if owner not in api.classes:
                        if is_jdk(owner):
                            problems.setdefault((owner, None, "not on Android"), set()).add(path)
                        continue
                    since = api.classes[owner]["since"]
                    if member is None:
                        if since > arguments.min_sdk:
                            problems.setdefault((owner, None, f"API {since:g}"), set()).add(path)
                        continue
                    needed = api.member_since(owner, kind, member)
                    if needed is None:
                        if is_jdk(owner):
                            problems.setdefault((owner, member, "not on Android"), set()).add(path)
                    elif needed > arguments.min_sdk:
                        problems.setdefault((owner, member, f"API {needed:g}"), set()).add(path)

    for (owner, member, reason), paths in sorted(problems.items(), key=lambda item: (item[0][0], item[0][1] or "")):
        where = sorted(paths)
        name = owner.replace("/", ".") + (f".{member}" if member else "")
        print(f"{name}: {reason} (minSdk {arguments.min_sdk}), used in {where[0]}" + (f" and {len(where) - 1} more" if len(where) > 1 else ""))
    if problems:
        print(f"{len(problems)} Android API problem(s) in {checked} classes.", file=sys.stderr)
        return 1
    print(f"Android API check passed: {checked} classes use only APIs available at API {arguments.min_sdk}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
