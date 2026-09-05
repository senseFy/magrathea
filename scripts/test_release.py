#!/usr/bin/env python3
"""Offline release lifecycle and metadata regression tests."""
import copy
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from release_notes import ROOT, check_metadata, render

loader = importlib.machinery.SourceFileLoader('release_command', str(ROOT / 'scripts/release'))
spec = importlib.util.spec_from_loader(loader.name, loader)
release = importlib.util.module_from_spec(spec)
loader.exec_module(release)
SHA = 'a' * 40
VERSION = '0.1.0-alpha.11'


def pr():
    return {'number': 7, 'merged': True, 'merge_commit_sha': SHA,
            'labels': [{'name': 'autorelease: pending'}],
            'base': {'ref': 'main', 'repo': {'full_name': 'senseFy/magrathea'}},
            'head': {'ref': 'release-please--branches--main', 'repo': {'full_name': 'senseFy/magrathea'}}}


class FakeGitHub:
    repo = 'senseFy/magrathea'

    def __init__(self):
        self.responses = {
            'pulls/7': pr(), f'commits/{SHA}/pulls?per_page=100': [pr()],
            'actions/runs/12': {'head_sha': SHA, 'head_branch': 'main', 'event': 'push',
                               'path': '.github/workflows/verify.yml', 'status': 'completed', 'conclusion': 'success'},
            'actions/runs/20/artifacts?per_page=100': {'artifacts': []},
            f'git/ref/tags/v{VERSION}': None,
        }
        self.publications = []
        self.writes = []

    def api(self, path, method='GET', data=None, missing=False):
        if method != 'GET':
            self.writes.append((path, method, data))
            return None
        if missing and path not in self.responses:
            return None
        return copy.deepcopy(self.responses[path])

    def runs(self, version):
        return self.publications


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / 'gradle.properties').write_text(f'magrathea.version={VERSION}\n')
        (self.root / '.release-please-manifest.json').write_text(json.dumps({'.': VERSION}))
        (self.root / 'CHANGELOG.md').write_text(f'# Changelog\n\n## [{VERSION}](https://example.invalid/compare) (2026-09-06)\n\n### Fixed\n\n- Keep reviewed changes.\n\n## 0.1.0-alpha.9 — 2026-09-04\n\nOld release.\n')
        self.gh = FakeGitHub()

    def validate(self):
        with patch.object(release.subprocess, 'check_output', return_value=SHA + '\n'):
            return release.validate(self.gh, self.root, VERSION, SHA, 7, 12, 20)

    def test_committed_release_metadata_is_consistent(self):
        check_metadata(ROOT)

    def test_notes_and_manifest_are_bound_to_gradle(self):
        self.assertEqual(check_metadata(self.root)[0], VERSION)
        notes = render(self.root, VERSION)
        self.assertIn('Keep reviewed changes.', notes)
        self.assertNotIn('Old release.', notes)
        (self.root / '.release-please-manifest.json').write_text('{".":"0.1.0-alpha.9"}')
        with self.assertRaisesRegex(ValueError, 'manifest and Gradle'):
            check_metadata(self.root)

    def test_empty_or_unpublished_notes_are_rejected(self):
        for body in ('', 'Not published. Failed verification.'):
            (self.root / 'CHANGELOG.md').write_text(f'## {VERSION} — 2026-09-06\n\n{body}\n')
            with self.assertRaises(ValueError):
                check_metadata(self.root)

    def test_request_pins_merge_commit_and_reuses_existing_run(self):
        release.request(self.gh, self.root, SHA, 12)
        inputs = self.gh.writes[0][2]['inputs']
        self.assertEqual(inputs, {'commit': SHA, 'version': VERSION, 'pull_request': '7', 'ci_run': '12'})
        self.gh.publications = [{'id': 20, 'html_url': 'existing'}]
        release.request(self.gh, self.root, SHA, 12)
        self.assertEqual(len(self.gh.writes), 1)

    def test_non_release_push_does_not_publish(self):
        self.gh.responses[f'commits/{SHA}/pulls?per_page=100'] = []
        release.request(self.gh, self.root, SHA, 12)
        self.assertEqual(self.gh.writes, [])

    def test_ci_rejects_other_commit_event_workflow_and_failure(self):
        for field, value in [('head_sha', 'b' * 40), ('event', 'pull_request'),
                             ('path', '.github/workflows/nightly.yml'), ('conclusion', 'failure')]:
            gh = FakeGitHub()
            gh.responses['actions/runs/12'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                release.require_ci(gh, 12, SHA)

    def test_fresh_and_restored_candidates_do_not_need_main_tip(self):
        self.assertFalse(self.validate())
        self.gh.responses['actions/runs/20/artifacts?per_page=100']['artifacts'] = [
            {'name': 'magrathea-release-candidate', 'expired': False}]
        self.gh.responses[f'git/ref/tags/v{VERSION}'] = {'object': {'type': 'tag'}}
        self.assertTrue(self.validate())

    def test_tag_without_candidate_or_duplicate_authorization_rejects_rebuild(self):
        self.gh.responses[f'git/ref/tags/v{VERSION}'] = {'object': {'type': 'tag'}}
        with self.assertRaisesRegex(ValueError, 'instead of rebuilding'):
            self.validate()
        self.gh.publications = [{'id': 19, 'html_url': 'original'}]
        with self.assertRaisesRegex(ValueError, 'already authorized'):
            self.validate()

    def test_release_pr_must_be_merged_from_our_release_branch(self):
        for field in ('merged', 'merge_commit_sha'):
            gh = FakeGitHub()
            gh.responses['pulls/7'][field] = False if field == 'merged' else 'b' * 40
            with self.subTest(field=field), self.assertRaises(ValueError):
                release.release_pr(gh, 7, SHA)

    def test_complete_requires_assets_then_closes_pending_and_refreshes_next_pr(self):
        names = [f'Magrathea-{VERSION}-release-bundle.zip', f'Magrathea-{VERSION}-release-bundle.zip.sha256',
                 f'Magrathea-{VERSION}-maven-coordinates.txt', f'Magrathea-{VERSION}-maven-files.sha256',
                 'magrathea-sbom.cdx.json', 'third-party-licenses.tsv', 'publish-verification.receipt']
        self.gh.responses[f'releases/tags/v{VERSION}'] = {'draft': False, 'prerelease': True,
            'assets': [{'name': name} for name in names], 'html_url': 'published'}
        self.gh.responses[f'git/ref/tags/v{VERSION}'] = {'object': {'type': 'tag', 'sha': 'tag-object'}}
        self.gh.responses['git/tags/tag-object'] = {'object': {'sha': SHA}}
        release.complete(self.gh, VERSION, SHA, 7)
        self.assertEqual(self.gh.writes[0][2]['labels'], ['autorelease: tagged'])
        self.assertEqual(self.gh.writes[1][0], 'actions/workflows/release-please.yml/dispatches')
        self.gh.writes.clear()
        self.gh.responses[f'releases/tags/v{VERSION}']['assets'] = []
        with self.assertRaises(ValueError):
            release.complete(self.gh, VERSION, SHA, 7)
        self.assertFalse(self.gh.writes)

    def test_supersede_never_rewrites_published_release(self):
        import base64
        self.gh.responses[f'contents/.release-please-manifest.json?ref={SHA}'] = {
            'content': base64.b64encode(json.dumps({'.': VERSION}).encode()).decode()}
        self.gh.responses[f'releases/tags/v{VERSION}'] = {'draft': False}
        with self.assertRaises(ValueError):
            release.supersede(self.gh, 7)
        self.gh.responses[f'releases/tags/v{VERSION}'] = None
        self.gh.responses[f'git/ref/tags/v{VERSION}'] = {'object': {'type': 'tag'}}
        release.supersede(self.gh, 7)
        self.assertEqual(self.gh.writes[-1][2]['labels'], ['autorelease: superseded'])


if __name__ == '__main__':
    unittest.main()
