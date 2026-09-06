"""Bind successful platform verification to the files actually checked out."""

import json
import os
from pathlib import Path
import re
import subprocess


WORKFLOW = '.github/workflows/verify.yml'
GATES = {'linux-gate', 'web-gate', 'apple-gate (published-consumer)',
         'apple-gate (simulator-tests)'}


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, text=True).strip()


def latest_pr_run(gh, pr):
    runs = gh.pages(f'actions/workflows/verify.yml/runs?event=pull_request&head_sha={pr["head"]["sha"]}',
                    'workflow_runs')
    # GitHub can omit PR links after merge/branch deletion. The checkout record must
    # still bind the exact PR number, merge parents and file tree before any reuse.
    matches = [r for r in runs if r['head_branch'] == pr['head']['ref']
               and (not r.get('pull_requests')
                    or any(p['number'] == pr['number'] for p in r['pull_requests']))]
    return max(matches, key=lambda r: r['id'], default=None)


def record(root, output):
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    evidence = {
        'format': 1,
        'repository': os.environ['GITHUB_REPOSITORY'],
        'run_id': int(os.environ['GITHUB_RUN_ID']),
        'run_attempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
        'pull_request': event.get('pull_request', {}).get('number', 0),
        'commit': git(root, 'rev-parse', 'HEAD'),
        'tree': git(root, 'rev-parse', 'HEAD^{tree}'),
    }
    if evidence['commit'] != os.environ['GITHUB_SHA']:
        raise ValueError('verification checkout differs from the workflow commit')
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, indent=2) + '\n')


def successful_gates(gh, run, attempt):
    """Resolve successful jobs, including those retained by a partial retry."""
    if (run['path'] != WORKFLOW or run['status'] != 'completed'
            or run['conclusion'] != 'success' or run['run_attempt'] != attempt
            or run['repository']['full_name'].lower() != gh.repo.lower()
            or run['head_repository']['full_name'].lower() != gh.repo.lower()):
        raise ValueError('CI attempt is not successful verification in this repository')
    jobs = gh.pages(f'actions/runs/{run["id"]}/jobs?filter=all', 'jobs')
    gates = {}
    for job in jobs:
        if job['name'] not in GATES or job['run_attempt'] > attempt:
            continue
        previous = gates.get(job['name'])
        if previous and previous['run_attempt'] == job['run_attempt']:
            raise ValueError('CI contains ambiguous platform jobs')
        if previous is None or previous['run_attempt'] < job['run_attempt']:
            gates[job['name']] = job
    # Re-run-failed-jobs preserves successful jobs from earlier attempts of this same run.
    if set(gates) != GATES or any(job['conclusion'] != 'success' for job in gates.values()):
        raise ValueError('CI attempt did not execute every platform gate successfully')
    return gates


def verify(gh, root, run, attempt, commit, pr):
    """Accept only a complete, directly executed attempt; never a skipped CI chain."""
    successful_gates(gh, run, attempt)
    if run['event'] == 'push':
        if run['head_branch'] != 'main' or run['head_sha'] != commit:
            raise ValueError('main CI belongs to another commit')
    elif run['event'] == 'pull_request':
        if (run['head_branch'] != pr['head']['ref']
                or run['head_sha'] != pr['head']['sha']
                or (run.get('pull_requests')
                    and not any(p['number'] == pr['number'] for p in run['pull_requests']))):
            raise ValueError('CI belongs to another PR revision')
    else:
        raise ValueError('CI event cannot authorize publication')

    evidence = gh.evidence(run['id'], attempt)
    expected = {'format': 1, 'repository': gh.repo, 'run_id': run['id'],
                'pull_request': pr['number'] if run['event'] == 'pull_request' else 0}
    recorded_attempt = evidence.get('run_attempt')
    if (any(evidence.get(key) != value for key, value in expected.items())
            or type(recorded_attempt) is not int or not 1 <= recorded_attempt <= attempt):
        raise ValueError('CI evidence belongs to another run, attempt or PR')
    if not all(re.fullmatch(r'[0-9a-f]{40}', evidence.get(key, ''))
               for key in ('commit', 'tree')):
        raise ValueError('CI evidence has invalid Git identifiers')
    tested = gh.api(f'git/commits/{evidence["commit"]}')
    if (tested['tree']['sha'] != evidence['tree']
            or evidence['tree'] != git(root, 'rev-parse', f'{commit}^{{tree}}')):
        raise ValueError('published files differ from the verified file tree')
    if run['event'] == 'push':
        if evidence['commit'] != commit:
            raise ValueError('CI evidence did not test the main commit')
    elif (len(tested['parents']) != 2 or tested['parents'][1]['sha'] != run['head_sha']):
        raise ValueError('CI evidence did not test the Release PR merge checkout')
    # GitHub reruns keep the checkout SHA. A retry of only a downstream job can retain
    # an earlier checkout artifact, while the API above verifies the pinned attempt's results.
    return dict(evidence, run_attempt=attempt)
