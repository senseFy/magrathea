#!/usr/bin/env python3
"""Verify the canonical project license inside every published SDK archive."""

import argparse
import pathlib
import re
import sys
import zipfile


ARCHIVE_SUFFIXES = frozenset((".aar", ".jar", ".klib"))
SAFE_ARTIFACT_PATTERN = re.compile(r"^[A-Za-z0-9._@+\-/]+$")


def license_entries(archive: pathlib.Path, entries: list[zipfile.ZipInfo]) -> list[zipfile.ZipInfo]:
    if archive.suffix == ".klib":
        return [
            entry
            for entry in entries
            if not entry.is_dir()
            and (
                entry.filename == "META-INF/LICENSE"
                or entry.filename == "default/resources/META-INF/LICENSE"
            )
        ]
    return [
        entry
        for entry in entries
        if not entry.is_dir() and entry.filename == "META-INF/LICENSE"
    ]


def verify_archive(archive: pathlib.Path, expected_license: bytes) -> None:
    try:
        with zipfile.ZipFile(archive) as container:
            matches = license_entries(archive, container.infolist())
            if len(matches) != 1:
                raise ValueError(
                    "expected exactly one canonical LICENSE entry, found "
                    f"{len(matches)}"
                )
            actual_license = container.read(matches[0])
    except ValueError as error:
        raise ValueError(str(error)) from None
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        raise ValueError(f"unreadable archive ({type(error).__name__})") from None

    if actual_license != expected_license:
        raise ValueError("embedded LICENSE differs from the repository LICENSE")


def safe_artifact_label(archive: pathlib.Path, repository: pathlib.Path, artifact_index: int) -> str:
    try:
        relative = archive.relative_to(repository).as_posix()
    except ValueError:
        return f"artifact[{artifact_index}]"
    if (
        not relative
        or relative.startswith("/")
        or any(component == ".." for component in relative.split("/"))
        or any(ord(character) < 32 or ord(character) == 127 for character in relative)
        or SAFE_ARTIFACT_PATTERN.fullmatch(relative) is None
    ):
        return f"artifact[{artifact_index}]"
    return relative


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("license", type=pathlib.Path)
    parser.add_argument("repository", type=pathlib.Path)
    arguments = parser.parse_args()

    try:
        expected_license = arguments.license.read_bytes()
        archives = sorted(
            path
            for path in arguments.repository.rglob("*")
            if path.is_file() and path.suffix in ARCHIVE_SUFFIXES
        )
    except OSError as error:
        raise ValueError(f"verification input is unreadable ({type(error).__name__})") from None
    if not archives:
        raise ValueError("no published archives found")

    for artifact_index, archive in enumerate(archives):
        try:
            verify_archive(archive, expected_license)
        except ValueError as error:
            label = safe_artifact_label(archive, arguments.repository, artifact_index)
            raise ValueError(f"{label}: {error}") from None

    print(f"MAGRATHEA_ARCHIVE_LICENSE_PASS archives={len(archives)}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError) as error:
        print(f"archive license verification failed: {error}", file=sys.stderr)
        sys.exit(1)
