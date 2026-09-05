#!/usr/bin/env python3
"""Render release notes from the committed changelog and validate version metadata."""

import argparse
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parent.parent
VERSION = r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?"


def configured_version(root):
    text = (root / "gradle.properties").read_text()
    match = re.search(rf"^magrathea\.version=({VERSION})$", text, re.M)
    if not match:
        raise ValueError("invalid magrathea.version")
    return match[1]


def release_entry(root, version):
    if not re.fullmatch(VERSION, version):
        raise ValueError("invalid release version")
    text = (root / "CHANGELOG.md").read_text()
    # Published history predates Release Please; both formats carry a version and date.
    heading = re.compile(
        rf"^## (?:\[{re.escape(version)}\]\([^\n]+\) \(\d{{4}}-\d{{2}}-\d{{2}}\)"
        rf"|{re.escape(version)} — \d{{4}}-\d{{2}}-\d{{2}})\s*$", re.M
    )
    matches = list(heading.finditer(text))
    if len(matches) != 1:
        raise ValueError(f"expected one dated CHANGELOG entry for {version}")
    remainder = text[matches[0].end():]
    body = re.split(r"^## ", remainder, maxsplit=1, flags=re.M)[0].strip()
    if not body or body.startswith("Not published."):
        raise ValueError(f"release notes for {version} are empty or unpublished")
    return body


def check_metadata(root, version=None):
    configured = configured_version(root)
    if version is not None and version != configured:
        raise ValueError(f"requested version does not match magrathea.version={configured}")
    manifest = json.loads((root / ".release-please-manifest.json").read_text())
    if manifest != {".": configured}:
        raise ValueError("Release Please manifest and Gradle version differ")
    return configured, release_entry(root, configured)


def render(root, version):
    body = release_entry(root, version)
    return f"""# Magrathea {version}

{body}

## Distribution

The 16 logical SDK modules and their Kotlin Multiplatform variants are available from
GitHub Packages under `saien.magrathea`. See the
[consumer setup](https://github.com/senseFy/magrathea/blob/v{version}/docs/publishing.md#consume).

Assets contain the signed Maven and Web bundle, checksums, coordinate inventory,
Maven file manifest, SBOM, license report, and source/CI verification receipt.
The bundle includes the release public key and Known Issues.
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--version")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.check:
        check_metadata(args.root, args.version)
        return
    version = args.version or configured_version(args.root)
    notes = render(args.root, version)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(notes)
    else:
        print(notes, end="")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, FileNotFoundError, json.JSONDecodeError) as error:
        raise SystemExit(f"release metadata invalid: {error}")
