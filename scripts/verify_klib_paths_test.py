#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import io
import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_klib_paths import discover_klibs, forbidden_root_encodings, leak_reason, main, scan_klib


class VerifyKlibPathsTest(unittest.TestCase):
    def write_klib(self, root: Path, name: str, payload: bytes) -> Path:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("default/ir/strings.knt", payload)
        return path

    def test_accepts_relative_source_paths_and_discovers_nested_klibs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = self.write_klib(root, "nested/clean.klib", b"magrathea-core/src/commonMain/Foo.kt")

            self.assertEqual(discover_klibs(root), [artifact])
            self.assertEqual(scan_klib(artifact, ()), [])

    def test_rejects_explicit_build_root_without_disclosing_payload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = self.write_klib(root, "leak.klib", f"{directory}/generated/source.bin".encode())

            findings = scan_klib(artifact, forbidden_root_encodings([directory]))

            self.assertEqual(len(findings), 1)
            self.assertEqual(findings[0].reason, "embedded build-root path")
            self.assertNotIn(directory, findings[0].reason)

    def test_rejects_unix_absolute_source_path_from_another_machine(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = self.write_klib(
                Path(directory),
                "unix.klib",
                b"binary-prefix\x00/home/ci/work/project/src/commonMain/Foo.kt\x00binary-suffix",
            )

            findings = scan_klib(artifact, ())

            self.assertEqual([finding.reason for finding in findings], ["embedded Unix absolute source path"])

    def test_rejects_windows_absolute_source_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = self.write_klib(
                Path(directory),
                "windows.klib",
                rb"C:\Users\builder\project\src\commonMain\Foo.kt",
            )

            findings = scan_klib(artifact, ())

            self.assertEqual([finding.reason for finding in findings], ["embedded Windows absolute source path"])

    def test_rejects_common_unix_build_roots(self) -> None:
        for root in ("root", "data", "srv", "build", "code", "app", "src", "runner", "agent"):
            with self.subTest(root=root):
                payload = f"/{root}/ci/project/src/Foo.kt".encode()
                self.assertEqual(leak_reason(payload, ()), "embedded Unix absolute source path")

    def test_unsafe_member_diagnostic_does_not_disclose_its_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "leak.klib"
            secret_member = "/Users/private-builder/secret-project/Foo.kt\nforged-log-line"
            with zipfile.ZipFile(artifact, "w") as archive:
                archive.writestr(secret_member, b"clean")
            stderr = io.StringIO()

            with contextlib.redirect_stderr(stderr):
                exit_code = main([str(artifact)])

            diagnostic = stderr.getvalue()
            self.assertEqual(exit_code, 1)
            self.assertIn("member[0]: unsafe KLIB member name", diagnostic)
            self.assertNotIn("private-builder", diagnostic)
            self.assertNotIn("forged-log-line", diagnostic)

    def test_rejects_corrupt_klib(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "corrupt.klib"
            artifact.write_bytes(b"not a zip archive")

            findings = scan_klib(artifact, ())

            self.assertEqual(len(findings), 1)
            self.assertIn("unreadable KLIB", findings[0].reason)


if __name__ == "__main__":
    unittest.main()
