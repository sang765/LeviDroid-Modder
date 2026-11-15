#!/usr/bin/env python3
import argparse
import re
import sys
from pathlib import Path

LEGACY_QUALIFIERS = {
    "idn": "in",
    "id": "in",
    "he": "iw",
}

STRING_ENTRY = re.compile(r"<string\s+name=\"([^\"]+)\"[^>]*>.*?</string>", re.DOTALL)


def parse_string_entries(text):
    return {match.group(1): match.group(0) for match in STRING_ENTRY.finditer(text)}


def merge_into(legacy_file, target_file):
    if not target_file.exists():
        target_file.parent.mkdir(parents=True, exist_ok=True)
        target_file.write_text(legacy_file.read_text(encoding="utf-8"), encoding="utf-8")
        legacy_file.unlink()
        return f"move {legacy_file} -> {target_file}"

    legacy_text = legacy_file.read_text(encoding="utf-8")
    target_text = target_file.read_text(encoding="utf-8")
    if legacy_text == target_text:
        legacy_file.unlink()
        return f"drop duplicate {legacy_file}"

    legacy_entries = parse_string_entries(legacy_text)
    target_entries = parse_string_entries(target_text)
    conflicts = sorted(
        key
        for key, body in legacy_entries.items()
        if key in target_entries and target_entries[key] != body
    )
    if conflicts:
        return f"conflict {legacy_file} vs {target_file}: {', '.join(conflicts)}"

    missing = [key for key in legacy_entries if key not in target_entries]
    if not missing:
        legacy_file.unlink()
        return f"drop duplicate {legacy_file}"

    addition = "".join(legacy_entries[key] for key in legacy_entries if key in missing)
    target_file.write_text(
        target_text.replace("</resources>", addition + "\n</resources>", 1),
        encoding="utf-8",
    )
    legacy_file.unlink()
    return f"merge {len(missing)} entries {legacy_file} -> {target_file}"


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

    conflicts = []
    for legacy_file, target_file in plan:
        if not args.apply:
            target_state = "exists" if target_file.exists() else "missing"
            print(f"would fold {legacy_file} into {target_file} ({target_state})")
            continue
        result = merge_into(legacy_file, target_file)
        print(result)
        if result.startswith("conflict"):
            conflicts.append(result)

    if args.apply:
        for legacy_dir in sorted({path.parent for path, _ in plan}):
            try:
                legacy_dir.rmdir()
            except OSError:
                pass
        for legacy_code in sorted(LEGACY_QUALIFIERS):
            leftover = args.res_dir / f"values-{legacy_code}"
            if leftover.is_dir() and any(leftover.rglob("*.xml")):
                print(f"left in place: {leftover}")
                return 1
    else:
        print("rerun with --apply to write these changes")

    return 1 if conflicts else 0


if __name__ == "__main__":
    sys.exit(main())
