#!/usr/bin/env python3
"""Offline HTTP contract tests for immutable Maven artifact transfers."""
from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import traceback
from types import SimpleNamespace
import unittest
from unittest.mock import patch


HELPER = Path(__file__).resolve().with_name('maven-transfer.py')


class Repository(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self):
        super().__init__(('127.0.0.1', 0), RepositoryHandler)
        self.lock = threading.Lock()
        self.files = {}
        self.responses = defaultdict(deque)
        self.requests = []
        self.connections = 0
        self.active = 0
        self.max_active = 0
        self.delay = 0.02
        self.errors = []

    def get_request(self):
        connection = super().get_request()
        with self.lock:
            self.connections += 1
        return connection

    def handle_error(self, request, client_address):
        with self.lock:
            self.errors.append(traceback.format_exc())


class RepositoryHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    disable_nagle_algorithm = True

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.transfer()

    def do_PUT(self):
        self.transfer()

    def transfer(self):
        repository = self.server
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        with repository.lock:
            repository.active += 1
            repository.max_active = max(repository.max_active, repository.active)
            record = {'method': self.command, 'path': self.path,
                      'start': time.monotonic(), 'body': body}
            repository.requests.append(record)
            response_queue = repository.responses[self.command, self.path]
            action = response_queue.popleft() if response_queue else None
        try:
            time.sleep(repository.delay)
            if action == 'store-and-disconnect':
                with repository.lock:
                    repository.files[self.path] = body
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                return
            headers = {}
            response_body = b''
            with repository.lock:
                if isinstance(action, tuple):
                    status, headers = action
                elif isinstance(action, int):
                    status = action
                elif self.command == 'GET':
                    status = 200 if self.path in repository.files else 404
                    response_body = repository.files.get(self.path, b'')
                elif self.path in repository.files:
                    status = 409
                else:
                    repository.files[self.path] = body
                    status = 201
            record['status'] = status
            self.send_response(status)
            self.send_header('Content-Length', str(len(response_body)))
            for key, value in headers.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(response_body)
            self.wfile.flush()
        finally:
            with repository.lock:
                record['end'] = time.monotonic()
                repository.active -= 1


class MavenTransferTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.repository = Repository()
        self.thread = threading.Thread(
            target=self.repository.serve_forever, kwargs={'poll_interval': 0.01}, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.url = f'http://127.0.0.1:{self.repository.server_port}/repository'
        self.inventory = self.root / 'files.tsv'

    def stop_server(self):
        self.repository.shutdown()
        self.repository.server_close()
        self.thread.join(timeout=2)
        self.assertEqual(self.repository.errors, [], '\n'.join(self.repository.errors))

    def files(self, suffixes=('jar',), coordinates=1):
        result = {}
        lines = []
        for coordinate in range(coordinates):
            for suffix in suffixes:
                name = f'library-{coordinate}-1.{suffix}'
                relative = f'saien/magrathea/library-{coordinate}/1/{name}'
                local = self.root / name
                # Binary and text files must both survive an exact-byte round trip.
                content = (name.encode() + b'\x00\xff\n') * 8
                local.write_bytes(content)
                result[f'/repository/{relative}'] = content
                lines.append(f'{local}\t{relative}\n')
        self.inventory.write_text(''.join(lines))
        return result

    def run_transfer(self, mode='upload', fresh=False, attempts=3, workers=2):
        arguments = [sys.executable, str(HELPER), '--files', str(self.inventory),
                     '--repository-url', self.url, '--mode', mode,
                     '--workers', str(workers), '--attempts', str(attempts),
                     '--retry-seconds', '0']
        if fresh:
            arguments.append('--fresh')
        return subprocess.run(arguments, capture_output=True, text=True, timeout=20)

    def assert_success(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def requests(self, method, path=None):
        return [request for request in self.repository.requests
                if request['method'] == method and (path is None or request['path'] == path)]

    def test_bounded_parallelism_connection_reuse_and_publication_barriers(self):
        # POMs deliberately precede payload in the inventory.
        expected = self.files(('pom', 'pom.asc', 'jar', 'jar.asc'), coordinates=4)
        self.assert_success(self.run_transfer(fresh=True))
        self.assertEqual(self.repository.files, expected)
        self.assertGreater(self.repository.max_active, 1)
        self.assertLessEqual(self.repository.max_active, 2)
        puts = self.requests('PUT')
        self.assertEqual(len(puts), len(expected))
        self.assertLess(self.repository.connections, len(puts))
        phases = [[], [], []]
        for request in puts:
            phase = (2 if request['path'].endswith('.pom') else
                     1 if request['path'].endswith('.pom.asc') else 0)
            phases[phase].append(request)
        for before, after in zip(phases, phases[1:]):
            self.assertLessEqual(max(request['end'] for request in before),
                                 min(request['start'] for request in after))
        self.assert_success(self.run_transfer(mode='verify'))
        self.assertEqual({request['path'] for request in self.requests('GET')}, set(expected))

    def test_existing_equal_files_are_not_reuploaded(self):
        self.repository.files.update(self.files())
        self.assert_success(self.run_transfer())
        self.assertEqual(self.requests('PUT'), [])

    def test_one_worker_keeps_requests_serial(self):
        self.files(('pom', 'pom.asc', 'jar', 'jar.asc'), coordinates=2)
        self.assert_success(self.run_transfer(fresh=True, workers=1))
        self.assert_success(self.run_transfer(mode='verify', workers=1))
        self.assertEqual(self.repository.max_active, 1)

    def test_worker_limits_are_rejected_before_network_io(self):
        self.files()
        for workers in (0, 9):
            with self.subTest(workers=workers):
                self.assertNotEqual(self.run_transfer(fresh=True, workers=workers).returncode, 0)
        self.assertEqual(self.repository.requests, [])

    def test_duplicate_remote_path_is_rejected_before_network_io(self):
        self.files()
        line = self.inventory.read_text()
        self.inventory.write_text(line + line)
        self.assertNotEqual(self.run_transfer(fresh=True).returncode, 0)
        self.assertEqual(self.repository.requests, [])

    def test_unsafe_relative_paths_are_rejected_before_network_io(self):
        self.files()
        local = self.inventory.read_text().split('\t')[0]
        for relative in ('../outside.jar', '/outside.jar', 'module/../outside.jar'):
            with self.subTest(relative=relative):
                self.inventory.write_text(f'{local}\t{relative}\n')
                self.assertNotEqual(self.run_transfer(fresh=True).returncode, 0)
        self.assertEqual(self.repository.requests, [])

    def test_existing_different_file_is_rejected_without_overwrite(self):
        path = next(iter(self.files()))
        self.repository.files[path] = b'original published bytes'
        self.assertNotEqual(self.run_transfer().returncode, 0)
        self.assertEqual(self.requests('PUT'), [])
        self.assertEqual(self.repository.files[path], b'original published bytes')

    def test_conflict_with_equal_bytes_is_accepted(self):
        self.repository.files.update(self.files())
        self.assert_success(self.run_transfer(fresh=True))
        self.assertEqual(len(self.requests('PUT')), 1)
        self.assertGreaterEqual(len(self.requests('GET')), 1)

    def test_conflict_with_different_bytes_is_rejected(self):
        path = next(iter(self.files()))
        self.repository.files[path] = b'another candidate'
        self.assertNotEqual(self.run_transfer(fresh=True).returncode, 0)
        self.assertEqual(self.repository.files[path], b'another candidate')
        self.assertEqual(len(self.requests('PUT')), 1)

    def test_rate_limit_honors_retry_after(self):
        path = next(iter(self.files()))
        self.repository.responses['PUT', path].append((429, {'Retry-After': '1'}))
        self.assert_success(self.run_transfer(fresh=True))
        attempts = self.requests('PUT', path)
        self.assertEqual(len(attempts), 2)
        self.assertGreaterEqual(attempts[1]['start'] - attempts[0]['end'], 0.95)

    def test_transient_server_error_is_retried(self):
        path = next(iter(self.files()))
        self.repository.responses['PUT', path].append(500)
        self.assert_success(self.run_transfer(fresh=True))
        self.assertEqual(len(self.requests('PUT', path)), 2)

    def test_verification_retries_rate_limited_download(self):
        expected = self.files()
        self.repository.files.update(expected)
        path = next(iter(expected))
        self.repository.responses['GET', path].append((429, {'Retry-After': '0'}))
        self.assert_success(self.run_transfer(mode='verify'))
        self.assertEqual(len(self.requests('GET', path)), 2)

    def test_ambiguous_upload_is_reconciled_before_another_put(self):
        expected = self.files()
        path = next(iter(expected))
        self.repository.responses['PUT', path].append('store-and-disconnect')
        self.assert_success(self.run_transfer(fresh=True))
        self.assertEqual(self.repository.files, expected)
        self.assertEqual(len(self.requests('PUT', path)), 1)
        self.assertGreaterEqual(len(self.requests('GET', path)), 1)

    def test_failed_payload_prevents_later_publication_phases(self):
        expected = self.files(('pom', 'pom.asc', 'jar', 'jar.asc'), coordinates=2)
        payload = next(path for path in expected if path.endswith('.jar'))
        self.repository.responses['PUT', payload].append(403)
        self.assertNotEqual(self.run_transfer(fresh=True).returncode, 0)
        self.assertFalse(any(request['path'].endswith(('.pom', '.pom.asc'))
                             for request in self.requests('PUT')))

    def test_failed_pom_signature_prevents_pom_publication(self):
        expected = self.files(('pom', 'pom.asc', 'jar'), coordinates=2)
        signature = next(path for path in expected if path.endswith('.pom.asc'))
        self.repository.responses['PUT', signature].append(403)
        self.assertNotEqual(self.run_transfer(fresh=True).returncode, 0)
        self.assertFalse(any(request['path'].endswith('.pom')
                             for request in self.requests('PUT')))

    def test_verification_rejects_missing_files(self):
        self.files()
        self.assertNotEqual(self.run_transfer(mode='verify').returncode, 0)
        self.assertEqual(self.requests('PUT'), [])

    def test_verification_checks_full_content(self):
        expected = self.files()
        path = next(iter(expected))
        self.repository.files[path] = expected[path][:-1] + b'x'
        self.assertNotEqual(self.run_transfer(mode='verify').returncode, 0)
        self.assertEqual(self.requests('PUT'), [])

    def test_upload_retries_are_bounded(self):
        path = next(iter(self.files()))
        self.repository.responses['PUT', path].extend([500] * 10)
        self.assertNotEqual(self.run_transfer(fresh=True, attempts=3).returncode, 0)
        self.assertEqual(len(self.requests('PUT', path)), 3)

    def test_download_retries_are_bounded(self):
        path = next(iter(self.files()))
        self.repository.responses['GET', path].extend([500] * 10)
        self.assertNotEqual(self.run_transfer(mode='verify', attempts=3).returncode, 0)
        self.assertEqual(len(self.requests('GET', path)), 3)


class TransportUnitTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('maven_transfer_test_module', HELPER)
        cls.transfer = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = cls.transfer
        spec.loader.exec_module(cls.transfer)

    @classmethod
    def tearDownClass(cls):
        del sys.modules[cls.transfer.__name__]

    def test_http_date_retry_after_and_delay_limit(self):
        transfer = self.transfer
        transport = transfer.Transport(SimpleNamespace(
            repository_url='http://127.0.0.1:1', netrc_file=None,
            workers=2, attempts=3, retry_seconds=2))
        file = transfer.MavenFile(Path('unused.jar'), 'unused.jar', 'unused-digest')
        now = datetime(2026, 9, 26, tzinfo=timezone.utc)
        with patch.object(transfer, 'datetime') as clock:
            clock.now.return_value = now
            date = format_datetime(now + timedelta(seconds=7), usegmt=True)
            result = transfer.Result(file, 429, 0, None, date)
            self.assertEqual(transport.delay([result], attempt=1), 7)
            # Backoff must also remain effective when the server asks for less.
            self.assertEqual(transport.delay([result], attempt=3), 8)
            result.retry_after = format_datetime(now + timedelta(seconds=61), usegmt=True)
            with self.assertRaises(transfer.TransferError):
                transport.delay([result], attempt=1)

    def test_interruption_stops_curl_before_removing_transfer_files(self):
        transfer = self.transfer
        transport = transfer.Transport(SimpleNamespace(
            repository_url='http://127.0.0.1:1', netrc_file=None,
            workers=2, attempts=3, retry_seconds=0))
        file = transfer.MavenFile(Path('/unused.jar'), 'unused.jar', 'unused-digest')
        with patch.object(transfer.subprocess, 'Popen') as popen:
            process = popen.return_value
            process.communicate.side_effect = KeyboardInterrupt

            def wait_for_exit(timeout):
                config = Path(popen.call_args.args[0][-1])
                self.assertTrue(config.is_file(), 'curl still needs its transfer configuration')

            process.wait.side_effect = wait_for_exit
            with self.assertRaises(KeyboardInterrupt):
                transport.batch([file], 'PUT', transfer.Phase('payload', 1, 2))
            process.terminate.assert_called_once()
            process.wait.assert_called_once_with(timeout=5)
            self.assertFalse(Path(popen.call_args.args[0][-1]).exists())


if __name__ == '__main__':
    unittest.main()
