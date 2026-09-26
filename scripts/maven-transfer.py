#!/usr/bin/env python3
"""Transfer immutable Maven files with bounded curl concurrency and byte checks."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
from urllib.parse import quote, urlsplit


MAX_RETRY_DELAY = 60.0
TRANSIENT_HTTP = {408, 429}


class TransferError(Exception):
    pass


@dataclass(frozen=True)
class MavenFile:
    local: Path
    relative: str
    digest: str


@dataclass
class Result:
    file: MavenFile
    status: int
    exit_code: int
    digest: str | None
    retry_after: str | None

    @property
    def transient(self) -> bool:
        return bool(self.exit_code) or self.status in TRANSIENT_HTTP or 500 <= self.status <= 599


class Phase:
    def __init__(self, name: str, files: int, workers: int):
        self.name = name
        self.files = files
        self.workers = workers
        self.requests = 0
        self.retries = 0
        self.reconciliations = 0
        self.uploaded_bytes = 0
        self.downloaded_bytes = 0
        self.connections = 0

    def __enter__(self) -> Phase:
        self.started = time.monotonic()
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        print(
            f"MAGRATHEA_MAVEN_TRANSFER phase={self.name} files={self.files}"
            f" workers={self.workers} elapsed_seconds={time.monotonic() - self.started:.3f}"
            f" requests={self.requests} retries={self.retries}"
            f" reconciliations={self.reconciliations} uploaded_bytes={self.uploaded_bytes}"
            f" downloaded_bytes={self.downloaded_bytes} connections={self.connections}"
            f" outcome={'failed' if exc_type else 'ok'}",
            flush=True,
        )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_files(path: Path) -> list[MavenFile]:
    files = []
    seen = set()
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        raise TransferError("cannot read the transfer inventory") from error
    for line_number, line in enumerate(lines, 1):
        columns = line.split("\t")
        if len(columns) != 2:
            raise TransferError(f"malformed transfer inventory at line {line_number}")
        local_name, relative = columns
        if (
            not re.fullmatch(r"[A-Za-z0-9_./+@()-]+", relative)
            or relative.startswith("/")
            or ".." in relative
            or any(part in ("", ".") for part in relative.split("/"))
        ):
            raise TransferError(f"unsafe relative path at inventory line {line_number}")
        if relative in seen:
            raise TransferError(f"duplicate relative path: {relative}")
        local = Path(local_name)
        if not local.is_absolute() or not local.is_file():
            raise TransferError(f"staged file is missing or not absolute: {relative}")
        try:
            digest = sha256(local)
        except OSError as error:
            raise TransferError(f"cannot read staged file: {relative}") from error
        seen.add(relative)
        files.append(MavenFile(local, relative, digest))
    if not files:
        raise TransferError("transfer inventory is empty")
    return files


def config_string(value: str) -> str:
    # curl config quoting is similar to JSON, but supports only these escapes.
    if any(character in value for character in ("\x00", "\n", "\r")):
        raise TransferError("invalid control character in transfer configuration")
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("\t", "\\t") + '"'


def retry_after(path: Path) -> str | None:
    try:
        lines = path.read_text(encoding="iso-8859-1").splitlines()
    except OSError:
        return None
    value = None
    for line in lines:
        if line.startswith("HTTP/"):
            value = None  # Only the final response, not a proxy or redirect response.
        elif line.lower().startswith("retry-after:"):
            value = line.partition(":")[2].strip()
    return value


class Transport:
    def __init__(self, args: argparse.Namespace):
        self.repository = args.repository_url.rstrip("/")
        self.netrc = args.netrc_file
        self.workers = args.workers
        self.attempts = args.attempts
        self.retry_seconds = args.retry_seconds

    def batch(self, files: list[MavenFile], method: str, phase: Phase, *, retry=False) -> list[Result]:
        if not files:
            return []
        with tempfile.TemporaryDirectory(prefix="magrathea-maven-transfer-") as directory:
            root = Path(directory)
            configuration = []
            for index, file in enumerate(files):
                if index:
                    configuration.append("next")
                options = {
                    "url": self.repository + "/" + quote(file.relative, safe="/+@()-._"),
                    "output": str(root / f"body-{index}"),
                    "dump-header": str(root / f"headers-{index}"),
                    "connect-timeout": "10",
                    "max-time": "120" if method == "PUT" else "180",
                    "proto": "=http,https",
                    "proto-redir": "=https" if self.repository.startswith("https://") else "=http,https",
                }
                if self.netrc:
                    options["netrc-file"] = str(self.netrc)
                configuration.extend(["silent", "location"])
                configuration.extend(f"{key} = {config_string(value)}" for key, value in options.items())
                # curl >= 7.75 supplies exitcode and urlnum in the JSON result.
                configuration.append('write-out = "%{json}\\n"')
                if method == "PUT":
                    content_type = "application/octet-stream"
                    if file.relative.endswith(".pom"):
                        content_type = "application/xml"
                    elif file.relative.endswith((".module", ".json")):
                        content_type = "application/json"
                    elif file.relative.endswith(".asc"):
                        content_type = "text/plain"
                    configuration.append(f"header = {config_string('Content-Type: ' + content_type)}")
                    configuration.append(f"upload-file = {config_string(str(file.local))}")
            config_path = root / "curl.conf"
            config_path.write_text("\n".join(configuration) + "\n", encoding="utf-8")
            config_path.chmod(0o600)
            try:
                process = subprocess.Popen(
                    ["curl", "--disable", "--parallel", "--parallel-max", str(self.workers), "--config", str(config_path)],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                )
            except OSError as error:
                raise TransferError("cannot start curl") from error
            try:
                output, _ = process.communicate()
            except BaseException:
                # Do not remove temporary files while an interrupted curl is using them.
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                raise

            records = {}
            try:
                for line in output.splitlines():
                    record = json.loads(line)
                    index = int(record["urlnum"])
                    if index in records or not 0 <= index < len(files):
                        raise ValueError("invalid transfer index")
                    records[index] = record
                if len(records) != len(files):
                    raise ValueError("missing transfer result")
                results = []
                for index, file in enumerate(files):
                    record = records[index]
                    status = int(record["http_code"])
                    exit_code = int(record["exitcode"])
                    phase.requests += 1
                    phase.retries += int(retry)
                    phase.uploaded_bytes += int(float(record["size_upload"]))
                    phase.downloaded_bytes += int(float(record["size_download"]))
                    phase.connections += int(record["num_connects"])
                    digest = None
                    if method == "GET" and status == 200 and exit_code == 0:
                        digest = sha256(root / f"body-{index}")
                    results.append(Result(file, status, exit_code, digest, retry_after(root / f"headers-{index}")))
            except (KeyError, TypeError, ValueError, OSError) as error:
                raise TransferError("curl returned incomplete or invalid transfer results; curl >= 7.75 is required") from error
            if process.returncode and all(result.exit_code == 0 for result in results):
                raise TransferError(f"curl batch failed with exit code {process.returncode}")
            return results

    def delay(self, results: list[Result], attempt: int, *, include_backoff=True) -> float:
        seconds = min(MAX_RETRY_DELAY, self.retry_seconds * (2 ** min(attempt - 1, 10))) if include_backoff else 0.0
        for result in results:
            value = result.retry_after
            if value is None:
                continue
            requested = 0.0
            if value.isdigit():
                requested = float(value)
            else:
                try:
                    deadline = parsedate_to_datetime(value)
                    if deadline.tzinfo is None:
                        deadline = deadline.replace(tzinfo=timezone.utc)
                    requested = max(0.0, (deadline - datetime.now(timezone.utc)).total_seconds())
                except (ValueError, TypeError, OverflowError):
                    continue
            if requested > MAX_RETRY_DELAY:
                raise TransferError("repository Retry-After exceeds the 60-second retry-delay limit")
            seconds = max(seconds, requested)
        return seconds

    @staticmethod
    def failure(result: Result, action: str) -> TransferError:
        if result.status in (401, 403):
            return TransferError(f"repository authentication was rejected while {action} {result.file.relative}")
        status = f"transport exit {result.exit_code}" if result.exit_code else f"HTTP {result.status}"
        return TransferError(f"repository {action} failed at {result.file.relative}: {status}")

    def report_retry(self, results: list[Result], phase: Phase, method: str,
                     attempt: int, delay: float, *, action="retry") -> None:
        for result in results:
            print(
                f"MAGRATHEA_MAVEN_RETRY phase={phase.name} method={method}"
                f" file={result.file.relative} status={result.status} exit_code={result.exit_code}"
                f" attempt={attempt}/{self.attempts} action={action} delay_seconds={delay:.3f}",
                flush=True,
            )

    def fetch(self, files: list[MavenFile], phase: Phase, *, allow_missing: bool) -> dict[str, Result]:
        pending = files
        complete = {}
        for attempt in range(1, self.attempts + 1):
            results = self.batch(pending, "GET", phase, retry=attempt > 1)
            retry_results = []
            for result in results:
                if result.status in (401, 403):
                    raise self.failure(result, "download")
                if result.exit_code == 0 and result.status == 200:
                    if result.digest != result.file.digest:
                        raise TransferError(f"remote file differs at {result.file.relative}")
                    complete[result.file.relative] = result
                elif result.exit_code == 0 and result.status == 404 and allow_missing:
                    complete[result.file.relative] = result
                elif result.transient:
                    retry_results.append(result)
                else:
                    raise self.failure(result, "download")
            if not retry_results:
                return complete
            if attempt == self.attempts:
                raise TransferError(f"download exhausted {self.attempts} attempts at {retry_results[0].file.relative}")
            delay = self.delay(retry_results, attempt)
            self.report_retry(retry_results, phase, "GET", attempt, delay)
            time.sleep(delay)
            pending = [result.file for result in retry_results]
        raise AssertionError("unreachable")

    def upload_phase(self, files: list[MavenFile], phase: Phase) -> None:
        pending = files
        for attempt in range(1, self.attempts + 1):
            results = self.batch(pending, "PUT", phase, retry=attempt > 1)
            ambiguous = []
            for result in results:
                if result.status in (401, 403):
                    raise self.failure(result, "upload")
                if result.exit_code == 0 and result.status in (200, 201, 204):
                    print(f"[resume-publish-sdk] published exact {result.file.relative}", flush=True)
                elif result.transient or result.status == 409:
                    ambiguous.append(result)
                else:
                    raise self.failure(result, "upload")
            if not ambiguous:
                return

            # A timeout/conflict may already have stored the exact candidate. Honor
            # throttling before reconciliation, and never blindly repeat a PUT.
            before_fetch = self.delay(ambiguous, attempt, include_backoff=False)
            self.report_retry(ambiguous, phase, "PUT", attempt, before_fetch, action="reconcile")
            time.sleep(before_fetch)
            phase.reconciliations += len(ambiguous)
            remote = self.fetch([result.file for result in ambiguous], phase, allow_missing=True)
            pending = []
            for result in ambiguous:
                if remote[result.file.relative].status == 200:
                    print(f"[resume-publish-sdk] published exact {result.file.relative}", flush=True)
                else:
                    pending.append(result.file)
            if not pending:
                return
            if attempt == self.attempts:
                raise TransferError(f"upload exhausted {self.attempts} attempts at {pending[0].relative}")
            delay = max(0.0, self.delay(ambiguous, attempt) - before_fetch)
            pending_paths = {file.relative for file in pending}
            self.report_retry(
                [result for result in ambiguous if result.file.relative in pending_paths],
                phase, "PUT", attempt, delay,
            )
            time.sleep(delay)

    def upload(self, files: list[MavenFile], fresh: bool) -> None:
        missing = files
        if not fresh:
            with Phase("preflight", len(files), self.workers) as phase:
                remote = self.fetch(files, phase, allow_missing=True)
                missing = [file for file in files if remote[file.relative].status == 404]
        for name in ("payload", "pom-signature", "pom"):
            selected = []
            for file in missing:
                file_phase = "pom" if file.relative.endswith(".pom") else (
                    "pom-signature" if file.relative.endswith(".pom.asc") else "payload"
                )
                if file_phase == name:
                    selected.append(file)
            if selected:
                with Phase(name, len(selected), self.workers) as phase:
                    self.upload_phase(selected, phase)

    def verify(self, files: list[MavenFile]) -> None:
        with Phase("verify", len(files), self.workers) as phase:
            self.fetch(files, phase, allow_missing=False)


def positive_integer(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a positive integer") from error
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def nonnegative_number(value: str) -> float:
    try:
        parsed = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a non-negative finite number") from error
    if not 0 <= parsed <= MAX_RETRY_DELAY:
        raise argparse.ArgumentTypeError("must be between 0 and 60 seconds")
    return parsed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        "Each request has at most --attempts attempts. Ambiguous PUT responses are reconciled "
        "with a separately bounded GET before any PUT retry. Retry delays cap at 60 seconds; "
        "a longer server Retry-After fails instead of retrying early."
    ))
    parser.add_argument("--files", required=True, type=Path)
    parser.add_argument("--repository-url", required=True)
    parser.add_argument("--netrc-file", type=Path)
    parser.add_argument("--workers", type=positive_integer, default=os.environ.get("MAGRATHEA_MAVEN_WORKERS", "2"))
    parser.add_argument("--attempts", type=positive_integer, default=os.environ.get("MAGRATHEA_REMOTE_TRANSPORT_ATTEMPTS", "4"))
    parser.add_argument("--retry-seconds", type=nonnegative_number, default=os.environ.get("MAGRATHEA_REMOTE_TRANSPORT_RETRY_SECONDS", "2"))
    parser.add_argument("--mode", required=True, choices=("upload", "verify"))
    parser.add_argument("--fresh", action="store_true")
    args = parser.parse_args()
    try:
        if args.workers > 8:
            raise TransferError("workers must be between 1 and 8")
        if args.fresh and args.mode != "upload":
            raise TransferError("--fresh requires --mode upload")
        try:
            repository = urlsplit(args.repository_url)
            safe_scheme = repository.scheme == "https" or (
                repository.scheme == "http" and repository.hostname in ("127.0.0.1", "localhost")
            )
        except ValueError as error:
            raise TransferError("invalid repository URL") from error
        if (not safe_scheme or not repository.hostname or repository.username is not None
                or repository.password is not None or repository.query or repository.fragment
                or any(character in args.repository_url for character in ("\x00", "\n", "\r"))):
            raise TransferError("repository URL must use HTTPS or local HTTP, without credentials, query or fragment")
        if args.netrc_file:
            try:
                mode = stat.S_IMODE(args.netrc_file.stat().st_mode)
                if not args.netrc_file.is_file() or mode != 0o600:
                    raise TransferError("netrc file must be a regular file with permissions 0600")
            except OSError as error:
                raise TransferError("netrc file is unavailable") from error
            args.netrc_file = args.netrc_file.resolve()
        files = read_files(args.files)
        transport = Transport(args)
        if args.mode == "upload":
            transport.upload(files, args.fresh)
        else:
            transport.verify(files)
        return 0
    except (TransferError, KeyboardInterrupt, InterruptedError) as error:
        message = str(error) if isinstance(error, TransferError) else "transfer interrupted"
        print(f"Maven transfer failed: {message}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    def interrupted(signum, frame):
        raise InterruptedError("transfer interrupted")

    signal.signal(signal.SIGTERM, interrupted)
    sys.exit(main())
