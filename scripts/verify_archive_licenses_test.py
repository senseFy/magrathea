#!/usr/bin/env python3

from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_archive_licenses import verify_archive


class VerifyArchiveLicensesTest(unittest.TestCase):
    LICENSE = b"canonical license\n"

    def write_archive(self, path: Path, entries: dict[str, bytes]) -> None:
        with zipfile.ZipFile(path, "w") as archive:
            for name, payload in entries.items():
                archive.writestr(name, payload)

    def test_accepts_exact_canonical_license(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "valid.klib"
            self.write_archive(archive, {"META-INF/LICENSE": self.LICENSE})

            verify_archive(archive, self.LICENSE)

    def test_failure_does_not_disclose_unsafe_member_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "invalid.klib"
            secret_member = "/Users/private-builder/secret/resources/META-INF/LICENSE\nforged-log-line"
            self.write_archive(
                archive,
                {
                    "META-INF/LICENSE": self.LICENSE,
                    secret_member: self.LICENSE,
                },
            )

            with self.assertRaises(ValueError) as raised:
                verify_archive(archive, b"different canonical license\n")

            diagnostic = str(raised.exception)
            self.assertNotIn("private-builder", diagnostic)
            self.assertNotIn("forged-log-line", diagnostic)
            self.assertNotIn(directory, diagnostic)


if __name__ == "__main__":
    unittest.main()
