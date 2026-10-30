#!/usr/bin/env python3
"""Write the release notes for the current tag.

The notes go to resources/launcher/changelog.json — bundled into the APK and
shown by the in-app "What's new" dialog after an update — and to
RELEASE_NOTES.md, the GitHub Release description. Ollama Cloud (Beam) writes
them from the commits since the previous tag; if the API is unavailable or the
answer misses the schema, the committed changelog is kept and rendered instead,
so a release can never fail on note generation.
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

from validate_release_content import validate_changelog  # noqa: E402

CHANGELOG = ROOT / "resources" / "launcher" / "changelog.json"
NOTES = ROOT / "RELEASE_NOTES.md"
MODEL = os.environ.get("RELEASE_NOTES_MODEL", "beam")
REPO = os.environ.get("GITHUB_REPOSITORY", "sang765/LeviDroid-Modder")


def git(*args):
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout.strip()


def commit_range(tag):
    try:
        previous = git("describe", "--tags", "--abbrev=0", "--exclude", tag, f"{tag}^")
        return f"{previous}..{tag}", previous or "the first release"
    except RuntimeError:
        return "HEAD", "the first release"


def commits(tag):
    rng, previous = commit_range(tag)
    lines = git("log", "--format=%H %s", "-n", "40", rng).splitlines()
    if not lines:
        raise RuntimeError(f"no commits found for {rng}")
    return lines, previous


def ask_model(key, tag, changes, previous):
    changelog = json.loads(CHANGELOG.read_text(encoding="utf-8"))
    prompt = f"""Write the player-facing release notes for {REPO} version {tag}.

Return ONLY a JSON object with exactly this shape:
{{"schemaVersion": 1, "headline": "...", "summary": "...",
  "sections": [{{"title": "...", "items": [{{"text": "...", "url": "..."}}]}}]}}

Rules:
- headline: at most 80 characters, in the style of the previous release.
- summary: 1-2 sentences (at most 300 characters) telling players what matters.
- 2 to 4 sections, 3 to 6 items each. Item text is at most 140 characters,
  plain text, no markdown, no emoji.
- Group changes under headings like Improvements, Fixes, Under the hood.
  Drop merge commits, version bumps, and CI-only changes.
- url: https://github.com/{REPO}/commit/<sha> when an item comes from one
  commit; omit it otherwise.
- Write in the same language as the previous release notes.

Previous release notes (may be outdated, use for tone only):
headline: {changelog.get("headline", "")}
summary: {changelog.get("summary", "")}

Commits since {previous}:
{chr(10).join(changes)}
"""
    payload = {
        "model": MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.3,
    }
    request = urllib.request.Request(
        "https://ollama.com/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
    )
    # Ollama Cloud has transient 5xx outages; retry before falling back to the
    # committed changelog, which may lag several releases behind.
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                body = json.load(response)
            break
        except urllib.error.HTTPError as error:
            if error.code < 500 or attempt == 2:
                raise
            time.sleep(2 * (attempt + 1))
    text = body["choices"][0]["message"]["content"].strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1].rsplit("```", 1)[0].strip()
    data = json.loads(text)
    data["schemaVersion"] = 1
    return data


def render():
    subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "render_release_notes.py"),
         str(CHANGELOG), str(NOTES)],
        check=True, cwd=ROOT,
    )


def main():
    tag = os.environ.get("GITHUB_REF_NAME", "")
    key = os.environ.get("RELEASE_NOTES_API_KEY", "")
    if not key or not tag:
        print("release notes: no RELEASE_NOTES_API_KEY or tag; keeping the committed changelog")
        render()
        return 0

    backup = CHANGELOG.read_bytes()
    try:
        changes, previous = commits(tag)
        data = ask_model(key, tag, changes, previous)
        CHANGELOG.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n",
                             encoding="utf-8")
        validate_changelog(CHANGELOG)
    except Exception as error:  # never block a release on note generation
        print(f"release notes: {error}; keeping the committed changelog", file=sys.stderr)
        CHANGELOG.write_bytes(backup)
    render()
    return 0


if __name__ == "__main__":
    sys.exit(main())
