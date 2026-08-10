#!/usr/bin/env python3
"""Reject machine-specific source paths in recursively discovered KLIB artifacts."""

from __future__ import annotations

import argparse
import os
import re
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Iterable, Sequence


_SOURCE_SUFFIX = rb"(?:kt|kts|java|def|c|cc|cpp|h|hpp|m|mm)"
_PATH_COMPONENT = rb"[A-Za-z0-9_. @%+=,~(){}\[\]\-\x80-\xff]+"
_UNIX_ABSOLUTE_SOURCE = re.compile(
    rb"(?<![A-Za-z0-9_. @%+=,~(){}\[\]\-\x80-\xff/\\:])"
    rb"/(?:Users|home|root|private|tmp|var|opt|mnt|data|srv|build|builds|workspace|workspaces|"
    rb"github/workspace|__w|Volumes|code|app|src|runner|agent)/"
    rb"(?:" + _PATH_COMPONENT + rb"/){1,64}" + _PATH_COMPONENT + rb"\." + _SOURCE_SUFFIX,
    re.IGNORECASE,
)
_WINDOWS_ABSOLUTE_SOURCE = re.compile(
    rb"(?:[A-Z]:[\\/]|\\\\" + _PATH_COMPONENT + rb"[\\/]" + _PATH_COMPONENT + rb"[\\/])"
    rb"(?:" + _PATH_COMPONENT + rb"[\\/]){1,64}" + _PATH_COMPONENT + rb"\." + _SOURCE_SUFFIX,
    re.IGNORECASE,
)
_SCAN_CHUNK_BYTES = 1024 * 1024
_SCAN_OVERLAP_BYTES = 16 * 1024


@dataclass(frozen=True)
class Finding:
    member_index: int | None
    reason: str


def unsafe_member_reason(name: str) -> str | None:
    normalized = name.replace("\\", "/")
    if any(ord(character) < 32 or ord(character) == 127 for character in name):
        return "unsafe KLIB member name"
    if normalized.startswith("/") or re.match(r"^[A-Za-z]:/", normalized):
        return "unsafe KLIB member name"
    if any(component == ".." for component in normalized.split("/")):
        return "unsafe KLIB member name"
    return None


def forbidden_root_encodings(values: Iterable[str]) -> tuple[bytes, ...]:
    """Return slash variants of explicit build roots without logging their values."""
    encodings: set[bytes] = set()
    for value in values:
        if not value:
            continue
        absolute = os.path.abspath(value)
        candidates = {absolute, os.path.realpath(absolute)}
        for candidate in candidates:
            if candidate == os.path.sep:
                raise ValueError("the filesystem root cannot be used as a forbidden build root")
            raw = os.fsencode(candidate.rstrip("/\\"))
            if raw:
                encodings.add(raw)
                encodings.add(raw.replace(b"\\", b"/"))
                encodings.add(raw.replace(b"/", b"\\"))
    return tuple(sorted(encodings, key=len, reverse=True))


def leak_reason(data: bytes, forbidden_roots: Sequence[bytes]) -> str | None:
    if any(root in data for root in forbidden_roots):
        return "embedded build-root path"
    if _WINDOWS_ABSOLUTE_SOURCE.search(data):
        return "embedded Windows absolute source path"
    if _UNIX_ABSOLUTE_SOURCE.search(data):
        return "embedded Unix absolute source path"
    return None


def scan_stream(stream: BinaryIO, forbidden_roots: Sequence[bytes]) -> str | None:
    overlap = b""
    while chunk := stream.read(_SCAN_CHUNK_BYTES):
        window = overlap + chunk
        if reason := leak_reason(window, forbidden_roots):
            return reason
        overlap = window[-_SCAN_OVERLAP_BYTES:]
    return None


def scan_klib(path: Path, forbidden_roots: Sequence[bytes]) -> list[Finding]:
    findings: list[Finding] = []
    try:
        with zipfile.ZipFile(path) as archive:
            for member_index, member in enumerate(archive.infolist()):
                if member.is_dir():
                    continue
                if reason := unsafe_member_reason(member.filename):
                    findings.append(Finding(member_index, reason))
                    continue
                if reason := leak_reason(member.filename.encode("utf-8", "surrogateescape"), forbidden_roots):
                    findings.append(Finding(member_index, reason))
                    continue
                with archive.open(member) as stream:
                    if reason := scan_stream(stream, forbidden_roots):
                        findings.append(Finding(member_index, reason))
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        findings.append(Finding(None, f"unreadable KLIB ({type(error).__name__})"))
    return findings


def discover_klibs(root: Path) -> list[Path]:
    if root.is_file():
        return [root] if root.suffix.lower() == ".klib" else []
    if not root.is_dir():
        raise ValueError("scan root does not exist or is not a directory")
    return sorted(path for path in root.rglob("*.klib") if path.is_file())


def safe_artifact_label(path: Path, root: Path, artifact_index: int) -> str:
    try:
        relative = path.relative_to(root).as_posix()
    except ValueError:
        return f"artifact[{artifact_index}]"
    if (
        not relative
        or relative.startswith("/")
        or any(component == ".." for component in relative.split("/"))
        or any(ord(character) < 32 or ord(character) == 127 for character in relative)
        or re.fullmatch(r"[A-Za-z0-9._@+\-/]+", relative) is None
    ):
        return f"artifact[{artifact_index}]"
    return relative


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="KLIB file or directory to scan recursively")
    parser.add_argument(
        "--forbid-root",
        action="append",
        default=[],
        metavar="PATH",
        help="absolute build root that must not occur in any KLIB (repeatable)",
    )
    parser.add_argument("--expected-count", type=int, help="fail unless exactly this many KLIBs are found")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        forbidden_roots = forbidden_root_encodings(args.forbid_root)
        klibs = discover_klibs(args.root)
    except ValueError as error:
        print(f"KLIB path verification failed: {error}", file=sys.stderr)
        return 1

    if args.expected_count is not None and len(klibs) != args.expected_count:
        print(
            f"KLIB path verification failed: expected {args.expected_count} artifacts, found {len(klibs)}",
            file=sys.stderr,
        )
        return 1
    if not klibs:
        print("KLIB path verification failed: no KLIB artifacts found", file=sys.stderr)
        return 1

    failed = False
    display_root = args.root if args.root.is_dir() else args.root.parent
    for artifact_index, klib in enumerate(klibs):
        for finding in scan_klib(klib, forbidden_roots):
            failed = True
            artifact = safe_artifact_label(klib, display_root, artifact_index)
            member = "archive" if finding.member_index is None else f"member[{finding.member_index}]"
            print(
                f"KLIB path verification failed: {artifact}:{member}: {finding.reason}",
                file=sys.stderr,
            )

    if failed:
        return 1
    print(f"MAGRATHEA_KLIB_PATHS_PASS klibs={len(klibs)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
