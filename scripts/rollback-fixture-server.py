#!/usr/bin/env python3

from __future__ import annotations

import http.server
import os
from pathlib import Path
import sys


HEALTH_PATH = "/.magrathea-health"


class FixtureHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args: object, directory: str, **kwargs: object) -> None:
        self.repository = Path(directory).resolve(strict=True)
        super().__init__(*args, directory=directory, **kwargs)

    def do_GET(self) -> None:
        if self.path == HEALTH_PATH:
            self.send_response(http.HTTPStatus.NO_CONTENT)
            self.end_headers()
            return
        super().do_GET()

    def do_HEAD(self) -> None:
        if self.path == HEALTH_PATH:
            self.send_response(http.HTTPStatus.NO_CONTENT)
            self.end_headers()
            return
        super().do_HEAD()

    def do_PUT(self) -> None:
        destination = Path(self.translate_path(self.path)).resolve()
        if not destination.is_relative_to(self.repository):
            self.send_error(http.HTTPStatus.BAD_REQUEST)
            return
        try:
            content_length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            self.send_error(http.HTTPStatus.LENGTH_REQUIRED)
            return
        if content_length < 0 or content_length > 256 * 1024 * 1024:
            self.send_error(http.HTTPStatus.REQUEST_ENTITY_TOO_LARGE)
            return

        existed = destination.exists()
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(f".{destination.name}.{os.getpid()}.upload")
        remaining = content_length
        try:
            with temporary.open("wb") as output:
                while remaining:
                    chunk = self.rfile.read(min(remaining, 1024 * 1024))
                    if not chunk:
                        raise ConnectionError("request body ended early")
                    output.write(chunk)
                    remaining -= len(chunk)
            temporary.replace(destination)
        except (ConnectionError, OSError):
            temporary.unlink(missing_ok=True)
            self.send_error(http.HTTPStatus.BAD_REQUEST)
            return

        self.send_response(
            http.HTTPStatus.NO_CONTENT if existed else http.HTTPStatus.CREATED
        )
        self.end_headers()

    def log_message(self, format: str, *args: object) -> None:
        return


def main() -> int:
    if len(sys.argv) != 3:
        print(
            "usage: rollback-fixture-server.py REPOSITORY_DIRECTORY PORT_FILE",
            file=sys.stderr,
        )
        return 2

    repository = Path(sys.argv[1]).resolve(strict=True)
    port_file = Path(sys.argv[2])
    if not repository.is_dir():
        print(f"fixture repository is not a directory: {repository}", file=sys.stderr)
        return 2

    handler = lambda *args, **kwargs: FixtureHandler(  # noqa: E731
        *args,
        directory=str(repository),
        **kwargs,
    )
    with http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler) as server:
        temporary_port_file = port_file.with_name(f".{port_file.name}.{os.getpid()}")
        temporary_port_file.write_text(f"{server.server_address[1]}\n", encoding="utf-8")
        temporary_port_file.chmod(0o600)
        temporary_port_file.replace(port_file)
        server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
