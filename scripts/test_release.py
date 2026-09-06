#!/usr/bin/env python3
"""Offline release lifecycle and metadata regression tests."""
import copy
import importlib.machinery
import importlib.util
import json
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess
import zipfile

from release_notes import ROOT, check_metadata, render

loader = importlib.machinery.SourceFileLoader('release_command', str(ROOT / 'scripts/release'))
spec = importlib.util.spec_from_loader(loader.name, loader)
release = importlib.util.module_from_spec(spec)
loader.exec_module(release)
SHA = 'a' * 40
TREE = 'c' * 40
PR_HEAD = 'd' * 40
PR_MERGE = 'e' * 40
VERSION = '0.1.0-alpha.11'


def pr():
    return {'number': 7, 'merged': True, 'merge_commit_sha': SHA,
            'labels': [{'name': 'autorelease: pending'}],
            'base': {'ref': 'main', 'repo': {'full_name': 'senseFy/magrathea'}},
            'head': {'ref': 'release-please--branches--main', 'sha': PR_HEAD, 'repo': {'full_name': 'senseFy/magrathea'}}}


class FakeGitHub:
    repo = 'senseFy/magrathea'

    def __init__(self):
        self.responses = {
            'pulls/7': pr(), f'commits/{SHA}/pulls': [pr()],
            'actions/runs/12/attempts/1': {'id': 12, 'run_attempt': 1, 'head_sha': SHA, 'head_branch': 'main', 'event': 'push',
                               'repository': {'full_name': self.repo}, 'head_repository': {'full_name': self.repo},
                               'path': '.github/workflows/verify.yml', 'status': 'completed', 'conclusion': 'success'},
            'actions/runs/20/artifacts?per_page=100': {'artifacts': []},
            f'git/ref/tags/v{VERSION}': None,
        }
        self.responses['actions/runs/12/jobs?filter=all'] = [
            {'name': name, 'conclusion': 'success', 'run_attempt': 1} for name in release.ci_evidence.GATES]
        self.responses[f'git/commits/{SHA}'] = {'tree': {'sha': TREE}, 'parents': []}
        self.records = {(12, 1): {'format': 1, 'repository': self.repo, 'run_id': 12,
                                 'run_attempt': 1, 'pull_request': 0, 'commit': SHA, 'tree': TREE}}
        self.publications = []
        self.writes = []

    def api(self, path, method='GET', data=None, missing=False):
        if method != 'GET':
            self.writes.append((path, method, data))
            return None
        if missing and path not in self.responses:
            return None
        return copy.deepcopy(self.responses[path])

    def pages(self, path, key):
        return copy.deepcopy(self.responses[path])

    def evidence(self, run_id, attempt):
        recorded_attempt = max(a for r, a in self.records if r == run_id and a <= attempt)
        return copy.deepcopy(self.records[(run_id, recorded_attempt)])

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
        self.output = self.root / 'outputs'
        self.env = patch.dict(release.os.environ, {'GITHUB_OUTPUT': str(self.output), 'GITHUB_EVENT_NAME': 'push'})
        self.env.start()
        self.addCleanup(self.env.stop)
        tree = patch.object(release.ci_evidence, 'git', return_value=TREE)
        tree.start()
        self.addCleanup(tree.stop)

    def validate(self):
        with patch.object(release.subprocess, 'check_output', return_value=SHA + '\n'):
            return release.validate(self.gh, self.root, VERSION, SHA, 7, 12, 1, 20)

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
        release.request(self.gh, self.root, SHA, 12, 1)
        inputs = self.gh.writes[0][2]['inputs']
        self.assertEqual(inputs, {'commit': SHA, 'version': VERSION, 'pull_request': '7', 'ci_run': '12', 'ci_attempt': '1'})
        self.gh.publications = [{'id': 20, 'html_url': 'existing'}]
        release.request(self.gh, self.root, SHA, 12, 1)
        self.assertEqual(len(self.gh.writes), 1)

    def test_non_release_push_does_not_publish(self):
        self.gh.responses[f'commits/{SHA}/pulls'] = []
        release.request(self.gh, self.root, SHA, 12, 1)
        self.assertEqual(self.gh.writes, [])

    def test_ci_rejects_other_commit_event_workflow_and_failure(self):
        for field, value in [('head_sha', 'b' * 40), ('event', 'pull_request'),
                             ('path', '.github/workflows/nightly.yml'), ('conclusion', 'failure')]:
            gh = FakeGitHub()
            gh.responses['actions/runs/12/attempts/1'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                release.require_ci(gh, self.root, 12, 1, SHA, pr())

    def pr_verification(self):
        run = copy.deepcopy(self.gh.responses['actions/runs/12/attempts/1'])
        run.update(id=13, event='pull_request', head_sha=PR_HEAD,
                   head_branch=pr()['head']['ref'], pull_requests=[{'number': 7}], html_url='verified-pr')
        self.gh.responses['actions/runs/13/attempts/1'] = run
        self.gh.responses['actions/runs/13/jobs?filter=all'] = copy.deepcopy(
            self.gh.responses['actions/runs/12/jobs?filter=all'])
        self.gh.responses[f'actions/workflows/verify.yml/runs?event=pull_request&head_sha={PR_HEAD}'] = [run]
        self.gh.responses[f'git/commits/{PR_MERGE}'] = {
            'tree': {'sha': TREE}, 'parents': [{'sha': 'b' * 40}, {'sha': PR_HEAD}]}
        self.gh.records[(13, 1)] = dict(self.gh.records[(12, 1)], run_id=13,
                                      commit=PR_MERGE, pull_request=7)
        return run

    def test_squash_merge_reuses_tested_merge_tree_not_pr_head_sha(self):
        self.pr_verification()
        evidence = release.ci_plan(self.gh, self.root, SHA)
        self.assertEqual(evidence['commit'], PR_MERGE)
        self.assertNotEqual(evidence['commit'], PR_HEAD)
        self.assertNotEqual(evidence['commit'], SHA)
        self.assertEqual(evidence['tree'], TREE)
        release.request(self.gh, self.root, SHA, evidence['run_id'], evidence['run_attempt'])
        inputs = self.gh.writes[-1][2]['inputs']
        self.assertEqual((inputs['commit'], inputs['ci_run'], inputs['ci_attempt']), (SHA, '13', '1'))

    def test_prs_never_skip_platform_tests(self):
        self.pr_verification()
        with patch.dict(release.os.environ, {'GITHUB_EVENT_NAME': 'pull_request'}):
            self.assertIsNone(release.ci_plan(self.gh, self.root, SHA))

    def test_merged_pr_can_lose_api_links_but_must_keep_exact_recorded_identity(self):
        run = self.pr_verification()
        run['pull_requests'] = []
        self.assertEqual(release.ci_plan(self.gh, self.root, SHA)['run_id'], 13)
        self.assertEqual(release.ci_cache.source(self.gh, self.root, SHA)['run_id'], 13)
        self.gh.records[(13, 1)]['pull_request'] = 8
        self.assertIsNone(release.ci_plan(self.gh, self.root, SHA))
        self.assertIsNone(release.ci_cache.source(self.gh, self.root, SHA))

    def test_run_with_explicit_other_pr_link_is_not_selected(self):
        run = self.pr_verification()
        run['pull_requests'] = [{'number': 8}]
        self.assertIsNone(release.ci_plan(self.gh, self.root, SHA))
        with self.assertRaises(ValueError):
            release.require_ci(self.gh, self.root, 13, 1, SHA, pr())

    def test_base_cache_accepts_a_merged_feature_pr_without_authorizing_release(self):
        run = self.pr_verification()
        self.gh.responses['pulls/7']['head']['ref'] = 'fix/example'
        self.gh.responses['pulls/7']['labels'] = []
        run['head_branch'] = 'fix/example'
        evidence = release.ci_cache.source(self.gh, self.root, SHA)
        self.assertEqual((evidence['run_id'], evidence['run_attempt']), (13, 1))
        with self.assertRaises(ValueError):
            release.release_pr(self.gh, 7, SHA)

    def test_base_cache_rejects_unmerged_foreign_or_different_contents(self):
        self.pr_verification()
        original = copy.deepcopy(self.gh.responses['pulls/7'])
        for field in ('open', 'fork', 'base', 'commit', 'tree'):
            self.gh.responses['pulls/7'] = copy.deepcopy(original)
            self.gh.responses[f'git/commits/{PR_MERGE}']['tree']['sha'] = TREE
            current = self.gh.responses['pulls/7']
            if field == 'open':
                current['merged'] = False
            elif field == 'fork':
                current['head']['repo']['full_name'] = 'fork/magrathea'
            elif field == 'base':
                current['base']['ref'] = 'other'
            elif field == 'commit':
                current['merge_commit_sha'] = 'f' * 40
            else:
                self.gh.responses[f'git/commits/{PR_MERGE}']['tree']['sha'] = 'f' * 40
            with self.subTest(field=field):
                self.assertIsNone(release.ci_cache.source(self.gh, self.root, SHA))

    def test_cache_source_is_optional_and_never_searches_past_newer_failure(self):
        run = self.pr_verification()
        failed = dict(run, id=14, conclusion='failure')
        self.gh.responses['actions/runs/14/attempts/1'] = failed
        self.gh.responses[f'actions/workflows/verify.yml/runs?event=pull_request&head_sha={PR_HEAD}'] = [run, failed]
        self.assertIsNone(release.ci_cache.source(self.gh, self.root, SHA))
        self.gh.responses[f'actions/workflows/verify.yml/runs?event=pull_request&head_sha={PR_HEAD}'] = [run]
        with patch.object(self.gh, 'evidence', side_effect=ValueError('expired')):
            self.assertIsNone(release.ci_cache.source(self.gh, self.root, SHA))
        self.gh.responses[f'commits/{SHA}/pulls'] = []
        self.assertIsNone(release.ci_cache.source(self.gh, self.root, SHA))
        self.assertIsNone(release.ci_cache.source(self.gh, self.root, ''))
        self.assertFalse(self.gh.writes)

    def test_changed_tree_or_forged_tree_falls_back_to_full_verification(self):
        self.pr_verification()
        for change in ('checkout', 'record'):
            with self.subTest(change=change):
                self.gh.records[(13, 1)]['tree'] = TREE
                self.gh.responses[f'git/commits/{PR_MERGE}']['tree']['sha'] = TREE
                if change == 'checkout':
                    self.gh.responses[f'git/commits/{PR_MERGE}']['tree']['sha'] = 'f' * 40
                else:
                    self.gh.records[(13, 1)]['tree'] = 'f' * 40
                self.assertIsNone(release.ci_plan(self.gh, self.root, SHA))

    def test_wrong_pr_revision_repository_or_attempt_cannot_supply_evidence(self):
        self.pr_verification()
        mutations = [('head_sha', SHA), ('head_branch', 'feature'), ('run_attempt', 2),
                     ('pull_requests', [{'number': 8}]),
                     ('head_repository', {'full_name': 'someone/fork'}),
                     ('repository', {'full_name': 'someone/fork'})]
        original = copy.deepcopy(self.gh.responses['actions/runs/13/attempts/1'])
        for key, value in mutations:
            self.gh.responses['actions/runs/13/attempts/1'] = dict(original, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                release.require_ci(self.gh, self.root, 13, 1, SHA, pr())

    def test_missing_expired_or_unreadable_evidence_falls_back(self):
        self.pr_verification()
        for error in (ValueError('expired'), KeyError('missing'), release.zipfile.BadZipFile('bad archive')):
            with self.subTest(error=str(error)), patch.object(self.gh, 'evidence', side_effect=error):
                self.assertIsNone(release.ci_plan(self.gh, self.root, SHA))

    def test_incomplete_or_skipped_platform_attempt_is_not_reusable(self):
        self.pr_verification()
        path = 'actions/runs/13/jobs?filter=all'
        original = copy.deepcopy(self.gh.responses[path])
        for conclusion in ('skipped', 'failure', 'cancelled', None):
            self.gh.responses[path] = copy.deepcopy(original)
            self.gh.responses[path][0]['conclusion'] = conclusion
            with self.subTest(conclusion=conclusion):
                self.assertIsNone(release.ci_plan(self.gh, self.root, SHA))
        # Missing platform results cannot become reusable evidence.
        self.gh.responses[path] = original[:1]
        self.assertIsNone(release.ci_plan(self.gh, self.root, SHA))

    def test_newer_failed_run_does_not_fall_back_to_older_green_run(self):
        run = self.pr_verification()
        failed = dict(run, id=14, conclusion='failure')
        self.gh.responses['actions/runs/14/attempts/1'] = failed
        self.gh.responses[f'actions/workflows/verify.yml/runs?event=pull_request&head_sha={PR_HEAD}'] = [run, failed]
        self.assertIsNone(release.ci_plan(self.gh, self.root, SHA))

    def test_release_pins_original_attempt_when_ci_is_rerun(self):
        self.pr_verification()
        self.gh.responses['actions/runs/13/attempts/2'] = dict(
            self.gh.responses['actions/runs/13/attempts/1'], run_attempt=2, conclusion='failure')
        evidence = release.require_ci(self.gh, self.root, 13, 1, SHA, pr())
        self.assertEqual(evidence['run_attempt'], 1)
        with self.assertRaises(ValueError):
            release.require_ci(self.gh, self.root, 13, 2, SHA, pr())

    def test_retry_failed_jobs_can_use_successful_jobs_from_same_run(self):
        self.pr_verification()
        run = self.gh.responses['actions/runs/13/attempts/1']
        self.gh.responses['actions/runs/13/attempts/2'] = dict(run, run_attempt=2)
        run['conclusion'] = 'failure'
        self.gh.records[(13, 2)] = dict(self.gh.records[(13, 1)], run_attempt=2)
        jobs = self.gh.responses['actions/runs/13/jobs?filter=all']
        jobs[0]['conclusion'] = 'failure'
        jobs.append(dict(jobs[0], run_attempt=2, conclusion='success'))
        evidence = release.require_ci(self.gh, self.root, 13, 2, SHA, pr())
        self.assertEqual(evidence['run_attempt'], 2)
        # A later retry cannot overwrite the result of the pinned successful attempt.
        jobs.append(dict(jobs[0], run_attempt=3, conclusion='failure'))
        self.assertEqual(release.require_ci(self.gh, self.root, 13, 2, SHA, pr()), evidence)

    def test_retry_downstream_job_can_keep_the_original_checkout_record(self):
        self.pr_verification()
        run = self.gh.responses['actions/runs/13/attempts/1']
        self.gh.responses['actions/runs/13/attempts/2'] = dict(run, run_attempt=2)
        run['conclusion'] = 'failure'
        evidence = release.require_ci(self.gh, self.root, 13, 2, SHA, pr())
        self.assertEqual(evidence['run_attempt'], 2)
        self.assertEqual(evidence['commit'], PR_MERGE)
        self.assertNotIn((13, 2), self.gh.records)

    def test_evidence_must_bind_run_attempt_pr_and_actual_merge_checkout(self):
        self.pr_verification()
        original = self.gh.records[(13, 1)].copy()
        for key, value in [('format', 2), ('run_id', 12), ('run_attempt', 2),
                           ('pull_request', 8), ('commit', 'invalid')]:
            self.gh.records[(13, 1)] = dict(original, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                release.require_ci(self.gh, self.root, 13, 1, SHA, pr())
        self.gh.records[(13, 1)] = original
        self.gh.responses[f'git/commits/{PR_MERGE}']['parents'] = [{'sha': PR_HEAD}]
        with self.assertRaisesRegex(ValueError, 'merge checkout'):
            release.require_ci(self.gh, self.root, 13, 1, SHA, pr())

    def test_existing_candidate_survives_source_evidence_expiry(self):
        self.gh.responses['actions/runs/20/artifacts?per_page=100']['artifacts'] = [
            {'name': 'magrathea-release-candidate', 'expired': False}]
        with patch.object(self.gh, 'evidence', side_effect=ValueError('expired')) as read:
            self.assertTrue(self.validate())
            read.assert_not_called()

    def test_fresh_candidate_exports_original_tested_commit_and_tree(self):
        self.pr_verification()
        with patch.object(release.subprocess, 'check_output', return_value=SHA + '\n'):
            self.assertFalse(release.validate(self.gh, self.root, VERSION, SHA, 7, 13, 1, 20))
        self.assertIn(f'ci_commit={PR_MERGE}\nci_tree={TREE}\n', self.output.read_text())

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


class CheckoutEvidenceTests(unittest.TestCase):
    def test_record_uses_checked_out_tree_and_preserves_version_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-c', 'user.name=CI Fixture', '-c',
                    'user.email=ci@example.invalid', '-c', 'commit.gpgsign=false',
                    '-c', 'core.hooksPath=/dev/null', *args], cwd=root, text=True).strip()
            git('init', '-q')
            (root / 'version').write_text('alpha.12\n')
            git('add', 'version')
            git('commit', '-q', '-m', 'release version')
            tested = git('rev-parse', 'HEAD')
            tested_tree = git('rev-parse', 'HEAD^{tree}')
            # A squash-like commit has different ancestry/metadata but identical contents.
            git('commit', '-q', '--allow-empty', '-m', 'merged release')
            merged = git('rev-parse', 'HEAD')
            self.assertNotEqual(tested, merged)
            self.assertEqual(git('rev-parse', 'HEAD^{tree}'), tested_tree)
            event = root / 'event.json'
            event.write_text('{"pull_request":{"number":7}}')
            with patch.dict(os.environ, {'GITHUB_EVENT_PATH': str(event), 'GITHUB_REPOSITORY': 'senseFy/magrathea',
                    'GITHUB_RUN_ID': '13', 'GITHUB_RUN_ATTEMPT': '2', 'GITHUB_SHA': merged}):
                release.ci_evidence.record(root, root / 'verification.json')
                record = json.loads((root / 'verification.json').read_text())
                self.assertEqual((record['commit'], record['tree'], record['pull_request']),
                                 (merged, tested_tree, 7))
                (root / 'version').write_text('alpha.13\n')
                git('add', 'version')
                git('commit', '-q', '-m', 'next release')
                self.assertNotEqual(git('rev-parse', 'HEAD^{tree}'), tested_tree)
                with self.assertRaisesRegex(ValueError, 'checkout differs'):
                    release.ci_evidence.record(root, root / 'verification.json')

    def test_artifact_reader_requires_one_unexpired_attempt_record(self):
        gh = release.GitHub('senseFy/magrathea')
        artifacts = [{'id': 99, 'name': 'magrathea-ci-2', 'expired': False}]
        def archive(files):
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as zipped:
                for name, contents in files:
                    zipped.writestr(name, contents)
            return subprocess.CompletedProcess([], 0, stdout=data.getvalue())
        with patch.object(gh, 'pages', return_value=artifacts), patch.object(release.subprocess, 'run') as download:
            download.return_value = archive([('verification.json', '{"run_attempt":2}')])
            self.assertEqual(gh.evidence(13, 2), {'run_attempt': 2})
            download.assert_called_once()
            for files in ([('../verification.json', '{}')], [('verification.json', '[]')],
                          [('verification.json', 'x' * 16385)],
                          [('verification.json', '{}'), ('extra.json', '{}')]):
                download.return_value = archive(files)
                with self.subTest(files=[f[0] for f in files]), self.assertRaises(ValueError):
                    gh.evidence(13, 2)
            artifacts[0]['expired'] = True
            with self.assertRaisesRegex(ValueError, 'missing or expired'):
                gh.evidence(13, 2)


if __name__ == '__main__':
    unittest.main()
