"""Task cache provenance, partial retries and filesystem round trips."""

import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import ci_cache
from test_release import FakeGitHub


class CacheSelectionTests(unittest.TestCase):
    def setUp(self):
        self.gh = FakeGitHub()
        self.artifacts = []
        self.gh.responses['actions/runs/12/artifacts'] = self.artifacts

    def add(self, platform, gate, attempt=1, expired=False):
        artifact = {'id': len(self.artifacts) + 1, 'expired': expired,
                    'name': ci_cache.artifact_name(platform, gate, attempt)}
        self.artifacts.append(artifact)
        return artifact

    def test_selection_shares_only_the_requested_platform_from_successful_jobs(self):
        linux = self.add('Linux-X64', 'linux-gate')
        web = self.add('Linux-X64', 'web-gate')
        self.add('macOS-ARM64', 'apple-gate (published-consumer)')
        self.add('Linux-ARM64', 'linux-gate')
        self.add('Linux-X64', 'linux-gate', attempt=2)
        selected = ci_cache.select(self.gh, 12, 1, 'Linux-X64')
        self.assertEqual({a['id'] for a in selected}, {linux['id'], web['id']})

    def test_retry_cannot_use_an_artifact_from_the_failed_job_attempt(self):
        run = self.gh.responses['actions/runs/12/attempts/1']
        self.gh.responses['actions/runs/12/attempts/2'] = dict(run, run_attempt=2)
        run['conclusion'] = 'failure'
        jobs = self.gh.responses['actions/runs/12/jobs?filter=all']
        linux = next(j for j in jobs if j['name'] == 'linux-gate')
        linux['conclusion'] = 'failure'
        jobs.append(dict(linux, run_attempt=2, conclusion='success'))
        self.add('Linux-X64', 'linux-gate', attempt=1)
        web = self.add('Linux-X64', 'web-gate', attempt=1)
        # A failed cache upload in the rerun leaves no valid Linux snapshot.
        self.assertEqual([a['id'] for a in ci_cache.select(self.gh, 12, 2, 'Linux-X64')], [web['id']])
        replacement = self.add('Linux-X64', 'linux-gate', attempt=2)
        self.assertEqual({a['id'] for a in ci_cache.select(self.gh, 12, 2, 'Linux-X64')},
                         {web['id'], replacement['id']})
        jobs.append(dict(linux, run_attempt=3, conclusion='failure'))
        self.assertEqual(len(ci_cache.select(self.gh, 12, 2, 'Linux-X64')), 2)

    def test_expired_or_missing_cache_is_empty_but_ambiguous_cache_is_rejected(self):
        self.add('Linux-X64', 'linux-gate', expired=True)
        self.assertEqual(ci_cache.select(self.gh, 12, 1, 'Linux-X64'), [])
        self.add('Linux-X64', 'linux-gate')
        self.add('Linux-X64', 'linux-gate')
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            ci_cache.select(self.gh, 12, 1, 'Linux-X64')

    def test_incomplete_run_cannot_supply_task_cache(self):
        self.add('Linux-X64', 'linux-gate')
        self.gh.responses['actions/runs/12/attempts/1']['conclusion'] = 'failure'
        with self.assertRaises(ValueError):
            ci_cache.select(self.gh, 12, 1, 'Linux-X64')


class CacheTransferTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.home = self.root / 'producer'
        self.target = self.root / 'consumer'
        self.metadata = {'repository': 'senseFy/magrathea', 'run_id': 12, 'run_attempt': 1,
                         'platform': 'Linux-X64', 'gate': 'linux-gate'}
        name = ci_cache.artifact_name('Linux-X64', 'linux-gate', 1)
        self.selected = [dict(self.metadata, id=1, name=name)]
        self.snapshot = self.root / 'download' / name
        self.key = f'build-cache-1/{"a" * 32}'

    def write(self, name, contents=b'compiled-output'):
        path = self.home / 'caches' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(contents)
        return path

    def export(self):
        return ci_cache.export(self.home, self.snapshot, self.metadata)

    def restore(self):
        return ci_cache.restore(self.target, self.snapshot.parent, self.selected)

    def manifest(self, change):
        path = self.snapshot / 'manifest.json'
        data = json.loads(path.read_text())
        change(data)
        path.write_text(json.dumps(data))

    def test_round_trip_excludes_locks_dependencies_and_configuration_state(self):
        self.write(self.key)
        self.write('build-cache-1/build-cache-1.lock')
        self.write('modules-2/credentials')
        self.write('configuration-cache/state')
        self.assertTrue(self.export())
        self.assertEqual(self.restore(), 1)
        self.assertEqual((self.target / 'caches' / self.key).read_bytes(), b'compiled-output')
        self.assertEqual(list((self.target / 'caches').rglob('*')),
                         [self.target / 'caches/build-cache-1', self.target / 'caches' / self.key])

    def test_empty_or_oversized_cache_does_not_require_an_artifact(self):
        self.write(self.key, b'x' * 20)
        with patch.object(ci_cache, 'MAX_BYTES', 10):
            self.assertFalse(self.export())

    def test_snapshot_budget_prefers_recent_outputs(self):
        older = self.write(self.key, b'older')
        newer_key = f'build-cache-1/{"b" * 32}'
        self.write(newer_key, b'newer')
        os.utime(older, (1, 1))
        with patch.object(ci_cache, 'MAX_BYTES', 5):
            self.assertTrue(self.export())
        self.assertEqual(self.restore(), 1)
        self.assertFalse((self.target / 'caches' / self.key).exists())
        self.assertEqual((self.target / 'caches' / newer_key).read_bytes(), b'newer')

    def test_damaged_snapshot_is_rejected_before_any_entries_are_installed(self):
        self.write(self.key)
        self.write(f'build-cache-1/{"b" * 32}')
        self.export()
        (self.snapshot / self.key).write_bytes(b'corruption')
        self.assertEqual(self.restore(), 0)
        self.assertFalse(self.target.exists())

    def test_snapshot_must_match_the_selected_run_attempt_gate_and_platform(self):
        self.write(self.key)
        self.export()
        original = copy.deepcopy(self.selected)
        for field in ('repository', 'run_id', 'run_attempt', 'platform', 'gate'):
            self.selected = copy.deepcopy(original)
            self.selected[0][field] = 'different'
            with self.subTest(field=field):
                self.assertEqual(self.restore(), 0)
                self.assertFalse(self.target.exists())

    def test_traversal_and_symlinks_cannot_be_restored(self):
        self.write(self.key)
        self.export()
        manifest = (self.snapshot / 'manifest.json').read_text()
        for name in ('../outside', '/tmp/outside', 'build-cache-1/../../outside'):
            (self.snapshot / 'manifest.json').write_text(manifest)
            self.manifest(lambda m: m['files'].update({name: '0' * 64}))
            self.assertEqual(self.restore(), 0)
        (self.snapshot / 'manifest.json').write_text(manifest)
        entry = self.snapshot / self.key
        entry.unlink()
        entry.symlink_to(self.home / 'caches' / self.key)
        self.assertEqual(self.restore(), 0)
        self.assertFalse(self.target.exists())

    def test_export_does_not_follow_symlinks(self):
        entry = self.write(self.key)
        entry.unlink()
        entry.symlink_to(__file__)
        self.assertFalse(self.export())

    def test_one_missing_snapshot_does_not_discard_another_valid_snapshot(self):
        self.write(self.key)
        self.export()
        self.selected.insert(0, dict(self.selected[0], name='missing'))
        self.assertEqual(self.restore(), 1)

    def test_interrupted_copy_preserves_existing_local_task_output(self):
        self.write(self.key)
        self.export()
        target = self.target / 'caches' / self.key
        target.parent.mkdir(parents=True)
        target.write_bytes(b'previous-output')
        def interrupted(source, output):
            output.write(b'partial')
            raise OSError('interrupted')
        with patch.object(ci_cache.shutil, 'copyfileobj', side_effect=interrupted):
            self.assertEqual(self.restore(), 0)
        self.assertEqual(target.read_bytes(), b'previous-output')
        self.assertEqual(list(target.parent.iterdir()), [target])


if __name__ == '__main__':
    unittest.main()
