#!/usr/bin/env python3
"""Keep the SDK version out of every compiled artifact except the build-info module."""

from __future__ import annotations

import argparse
import io
import sys
import zipfile
from pathlib import Path
from typing import Sequence

_COMPILED_SUFFIXES = (".klib", ".jar", ".aar")
_DOCUMENTATION_SUFFIXES = ("-sources.jar", "-javadoc.jar")


def compiled_artifacts(repository: Path, version: str) -> list[Path]:
    if not repository.is_dir():
        raise ValueError("repository does not exist or is not a directory")
    return sorted(
        path
        for path in repository.glob(f"*/{version}/*")
        if path.is_file()
        and path.name.endswith(_COMPILED_SUFFIXES)
        and not path.name.endswith(_DOCUMENTATION_SUFFIXES)
    )


def archive_mentions(payload: bytes, needle: bytes) -> bool:
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        for member in archive.infolist():
            if member.is_dir():
                continue
            data = archive.read(member)
            if needle in data or (member.filename.endswith(".jar") and archive_mentions(data, needle)):
                return True
    return False


def is_owned(artifact: Path, owner: str) -> bool:
    module = artifact.parent.parent.name
    return module == owner or module.startswith(f"{owner}-")


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path, help="Maven group directory containing artifact folders")
    parser.add_argument("version", help="SDK version that only the owner module may embed")
    parser.add_argument("--owner", default="magrathea-build-info", help="logical module that owns the version")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    needle = args.version.encode()
    try:
        artifacts = compiled_artifacts(args.repository, args.version)
        if not artifacts:
            raise ValueError("no compiled artifacts found")
        mentions = {artifact: archive_mentions(artifact.read_bytes(), needle) for artifact in artifacts}
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"version isolation verification failed: {error}", file=sys.stderr)
        return 1

    leaks = [artifact for artifact, found in mentions.items() if found and not is_owned(artifact, args.owner)]
    for artifact in leaks:
        print(
            f"version isolation verification failed: {artifact.relative_to(args.repository).as_posix()} "
            f"embeds the SDK version outside {args.owner}",
            file=sys.stderr,
        )
    if not any(found for artifact, found in mentions.items() if is_owned(artifact, args.owner)):
        print(f"version isolation verification failed: no {args.owner} artifact embeds the SDK version", file=sys.stderr)
        return 1
    if leaks:
        return 1
    print(f"MAGRATHEA_VERSION_ISOLATION_PASS artifacts={len(artifacts)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
