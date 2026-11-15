#!/usr/bin/env python3
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

LEGACY_QUALIFIERS = {
    "idn": "in",
    "id": "in",
    "he": "iw",
}

ENTRY_PATTERN = re.compile(r"<(string-array|plurals|string)\b[^>]*?(?:/>|>.*?</\1>)", re.DOTALL)
NAME_PATTERN = re.compile(r"\bname=\"([^\"]+)\"")


def parse_named_elements(text):
    root = ET.fromstring(text)
    elements = {}
    for child in root:
        name = child.get("name")
        if not name:
            raise ValueError(f"<{child.tag}> element without a name attribute")
        if name in elements:
            raise ValueError(f"duplicate resource name {name}")
        elements[name] = (child.tag, tuple(sorted(child.attrib.items())), "".join(child.itertext()))
    return elements


def scan_resource_blocks(text):
    blocks = {}
    for match in ENTRY_PATTERN.finditer(text):
        name = NAME_PATTERN.search(match.group(0))
        if not name:
            raise ValueError(f"a <{match.group(1)}> element without a name attribute")
        blocks[name.group(1)] = match.group(0)
    return blocks


def read_entries(path):
    text = path.read_text(encoding="utf-8")
    elements = parse_named_elements(text)
    blocks = scan_resource_blocks(text)
    if set(blocks) != set(elements):
        raise ValueError("some resources cannot be read back verbatim, so nothing is folded")
    return text, elements, blocks


def analyze(legacy_file, target_file):
    if not target_file.exists():
        try:
            parse_named_elements(legacy_file.read_text(encoding="utf-8"))
        except (ET.ParseError, ValueError) as error:
            return "skip", f"skipped {legacy_file}: {error}"
        return "move", f"move {legacy_file} -> {target_file}"
    legacy_text = legacy_file.read_text(encoding="utf-8")
    target_text = target_file.read_text(encoding="utf-8")
    if legacy_text == target_text:
        return "drop", f"drop duplicate {legacy_file}"
    try:
        _, legacy_entries, _ = read_entries(legacy_file)
        target_entries = parse_named_elements(target_text)
    except (ET.ParseError, ValueError) as error:
        return "skip", f"skipped {legacy_file}: {error}"
    conflicts = sorted(
        name
        for name, value in legacy_entries.items()
        if name in target_entries and target_entries[name] != value
    )
    if conflicts:
        return "conflict", f"conflict {legacy_file} vs {target_file}: {', '.join(conflicts)}"
    missing = [name for name in legacy_entries if name not in target_entries]
    if not missing:
        return "drop", f"drop duplicate {legacy_file}"
    return "merge", f"merge {len(missing)} entries {legacy_file} -> {target_file}"


def indent_block(block):
    return "\n".join(f"    {line}" if line.strip() else line for line in block.splitlines())


def apply_action(kind, legacy_file, target_file):
    if kind == "move":
        target_file.parent.mkdir(parents=True, exist_ok=True)
        target_file.write_text(legacy_file.read_text(encoding="utf-8"), encoding="utf-8")
    elif kind == "merge":
        _, legacy_entries, blocks = read_entries(legacy_file)
        target_text = target_file.read_text(encoding="utf-8")
        target_entries = parse_named_elements(target_text)
        missing = [name for name in legacy_entries if name not in target_entries]
        addition = "\n".join(indent_block(blocks[name]) for name in legacy_entries if name in missing)
        target_file.write_text(
            target_text.replace("</resources>", addition + "\n</resources>", 1),
            encoding="utf-8",
        )
    legacy_file.unlink()


def collect_plan(res_dir):
    plan = []
    for legacy_code, target_code in sorted(LEGACY_QUALIFIERS.items()):
        legacy_dir = res_dir / f"values-{legacy_code}"
        target_dir = res_dir / f"values-{target_code}"
        if not legacy_dir.is_dir():
            continue
        for legacy_file in sorted(legacy_dir.rglob("*.xml")):
            plan.append((legacy_file, target_dir / legacy_file.relative_to(legacy_dir)))
    return plan


def main():
    parser = argparse.ArgumentParser(
        description=(
            "Fold Android locale directories that the resource matcher never selects "
            "into the qualifier it does use, such as values-idn and values-id into values-in."
        )
    )
    parser.add_argument("--res-dir", type=Path, default=Path("app/src/main/res"))
    parser.add_argument("--apply", action="store_true", help="write the changes instead of listing them")
    args = parser.parse_args()

    if not args.res_dir.is_dir():
        print(f"resource directory not found: {args.res_dir}")
        return 1

    plan = collect_plan(args.res_dir)
    if not plan:
        print("no legacy locale directories found")
        return 0

    problems = 0
    for legacy_file, target_file in plan:
        kind, message = analyze(legacy_file, target_file)
        if kind in {"conflict", "skip"}:
            problems += 1
        if args.apply and kind in {"move", "merge", "drop"}:
            apply_action(kind, legacy_file, target_file)
            print(message)
        else:
            print(f"would {message}")

    if not args.apply:
        print("rerun with --apply to write these changes")

    if args.apply:
        for legacy_code in sorted(LEGACY_QUALIFIERS):
            leftover = args.res_dir / f"values-{legacy_code}"
            if not leftover.is_dir():
                continue
            remaining = list(leftover.rglob("*.xml"))
            if remaining:
                print(f"left in place: {leftover}")
                problems += 1
                continue
            try:
                leftover.rmdir()
            except OSError:
                pass

    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
