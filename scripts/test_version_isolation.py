#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import io
import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_version_isolation import main

VERSION = "0.1.0-alpha.7"


class VerifyVersionIsolationTest(unittest.TestCase):
    def archive(self, entries: dict[str, bytes]) -> bytes:
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, payload in entries.items():
                archive.writestr(name, payload)
        return buffer.getvalue()

    def write(self, root: Path, artifact_id: str, extension: str, payload: bytes) -> None:
        directory = root / artifact_id / VERSION
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f"{artifact_id}-{VERSION}{extension}").write_bytes(payload)

    def run_main(self, root: Path) -> tuple[int, str]:
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(io.StringIO()):
            exit_code = main([str(root), VERSION])
        return exit_code, stderr.getvalue()

    def write_owner(self, root: Path) -> None:
        self.write(root, "magrathea-build-info-iosarm64", ".klib", self.archive({"default/ir/strings.knt": VERSION.encode()}))

    def test_accepts_version_only_in_build_info_and_documentation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_owner(root)
            self.write(root, "magrathea-core-iosarm64", ".klib", self.archive({"default/ir/strings.knt": b"clean"}))
            self.write(root, "magrathea-core", "-javadoc.jar", self.archive({"index.html": VERSION.encode()}))

            self.assertEqual(self.run_main(root), (0, ""))

    def test_rejects_documentation_without_the_stamped_version(self) -> None:
        for page in (b"MAGRATHEA_DOCS_VERSION", b"no version"):
            with self.subTest(page=page), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.write_owner(root)
                self.write(root, "magrathea-core", "-javadoc.jar", self.archive({"index.html": VERSION.encode() + b" " + page}))
                self.write(root, "magrathea-runtime", "-javadoc.jar", self.archive({"index.html": page}))

                exit_code, diagnostic = self.run_main(root)

                self.assertEqual(exit_code, 1)
                self.assertIn("magrathea-runtime", diagnostic)
                self.assertIn("does not carry the stamped SDK version", diagnostic)

    def test_rejects_version_in_another_module_klib(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_owner(root)
            self.write(root, "magrathea-core-js", ".klib", self.archive({"default/ir/strings.knt": VERSION.encode()}))

            exit_code, diagnostic = self.run_main(root)

            self.assertEqual(exit_code, 1)
            self.assertIn(f"magrathea-core-js/{VERSION}/magrathea-core-js-{VERSION}.klib", diagnostic)

    def test_rejects_version_in_nested_android_classes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_owner(root)
            classes = self.archive({"saien/magrathea/core/Version.class": VERSION.encode()})
            self.write(root, "magrathea-core-android", ".aar", self.archive({"classes.jar": classes}))

            exit_code, diagnostic = self.run_main(root)

            self.assertEqual(exit_code, 1)
            self.assertIn("magrathea-core-android", diagnostic)

    def test_requires_build_info_to_embed_the_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, "magrathea-build-info-jvm", ".jar", self.archive({"Info.class": b"stale"}))

            exit_code, diagnostic = self.run_main(root)

            self.assertEqual(exit_code, 1)
            self.assertIn("no magrathea-build-info artifact embeds the SDK version", diagnostic)


if __name__ == "__main__":
    unittest.main()
