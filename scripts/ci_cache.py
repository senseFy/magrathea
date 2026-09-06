"""Transfer optional Gradle task outputs between verified CI runs."""

import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

import ci_evidence


GATE_KEYS = {
    'linux-gate': 'linux',
    'web-gate': 'web',
    'apple-gate (published-consumer)': 'apple-consumer',
    'apple-gate (simulator-tests)': 'apple-tests',
}
ENTRY = re.compile(r'build-cache-[0-9]+/[0-9a-f]{32}')
MAX_BYTES = 256 * 1024 * 1024


def source(gh, root, commit):
    """Use only the successful PR whose merged contents are this base commit."""
    if not re.fullmatch(r'[0-9a-f]{40}', commit or ''):
        return None
    try:
        matches = [p for p in gh.pages(f'commits/{commit}/pulls', 'items')
                   if p.get('merge_commit_sha') == commit]
        if len(matches) != 1:
            return None
        pr = gh.api(f'pulls/{matches[0]["number"]}')
        if (not pr['merged'] or pr['merge_commit_sha'] != commit or pr['base']['ref'] != 'main'
                or pr['base']['repo']['full_name'].lower() != gh.repo.lower()
                or pr['head']['repo']['full_name'].lower() != gh.repo.lower()):
            return None
        latest = ci_evidence.latest_pr_run(gh, pr)
        if latest is None:
            return None
        run = gh.api(f'actions/runs/{latest["id"]}/attempts/{latest["run_attempt"]}')
        return ci_evidence.verify(gh, root, run, latest['run_attempt'], commit, pr)
    except (ValueError, KeyError, TypeError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f'No verified base cache: {error}')
        return None


def artifact_name(platform, gate, attempt):
    return f'magrathea-gradle-{platform}-{GATE_KEYS[gate]}-{attempt}'


def select(gh, run_id, attempt, platform):
    run = gh.api(f'actions/runs/{run_id}/attempts/{attempt}')
    gates = ci_evidence.successful_gates(gh, run, attempt)
    artifacts = gh.pages(f'actions/runs/{run_id}/artifacts', 'artifacts')
    selected = []
    for gate, job in sorted(gates.items()):
        name = artifact_name(platform, gate, job['run_attempt'])
        matches = [a for a in artifacts if a['name'] == name and not a['expired']]
        if len(matches) > 1:
            raise ValueError('ambiguous task cache artifact')
        if matches:
            selected.append({'id': matches[0]['id'], 'name': name, 'repository': gh.repo,
                             'run_id': run_id, 'run_attempt': job['run_attempt'],
                             'gate': gate, 'platform': platform})
    return selected


def digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as source_file:
        for block in iter(lambda: source_file.read(1024 * 1024), b''):
            checksum.update(block)
    return checksum.hexdigest()


def export(home, output, metadata):
    """Bound snapshot size; exclude dependency caches, configuration state and locks."""
    if output.exists():
        raise ValueError('task cache export directory already exists')
    output.mkdir(parents=True)
    cache = home / 'caches'
    candidates = [p for p in cache.glob('build-cache-*/*')
                  if ENTRY.fullmatch(p.relative_to(cache).as_posix())
                  and p.is_file() and not p.is_symlink() and not p.parent.is_symlink()]
    files, size = {}, 0
    for entry in sorted(candidates, key=lambda p: p.stat().st_mtime, reverse=True):
        length = entry.stat().st_size
        if length == 0 or size + length > MAX_BYTES:
            continue
        name = entry.relative_to(cache).as_posix()
        target = output / name
        target.parent.mkdir(exist_ok=True)
        shutil.copyfile(entry, target)
        files[name] = digest(target)
        size += length
    (output / 'manifest.json').write_text(json.dumps(dict(metadata, format=1, files=files)) + '\n')
    print(f'Exported {len(files)} Gradle task entries ({size // 1048576} MiB).')
    return bool(files)


def restore(home, directory, selected):
    """Validate a complete snapshot before installing any of its entries."""
    restored = 0
    for expected in selected:
        snapshot = directory / expected['name']
        try:
            manifest_path = snapshot / 'manifest.json'
            if manifest_path.is_symlink() or manifest_path.stat().st_size > 4 * 1024 * 1024:
                raise ValueError('invalid task cache manifest')
            manifest = json.loads(manifest_path.read_text())
            identity = {k: v for k, v in expected.items() if k not in ('id', 'name')}
            if (not isinstance(manifest, dict) or manifest.get('format') != 1
                    or any(manifest.get(k) != v for k, v in identity.items())):
                raise ValueError('task cache provenance differs from the selected artifact')
            files = manifest['files']
            if not isinstance(files, dict):
                raise ValueError('invalid task cache entries')
            size = 0
            for name, checksum in files.items():
                if not ENTRY.fullmatch(name):
                    raise ValueError('unexpected task cache path')
                path = snapshot / name
                if path.is_symlink() or path.parent.is_symlink() or not path.is_file():
                    raise ValueError('invalid task cache file')
                size += path.stat().st_size
                if size > MAX_BYTES or digest(path) != checksum:
                    raise ValueError('task cache checksum or size mismatch')
            for name in files:
                target = home / 'caches' / name
                target.parent.mkdir(parents=True, exist_ok=True)
                if target.is_symlink() or target.parent.is_symlink():
                    raise ValueError('invalid local task cache path')
                temporary = None
                try:
                    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as output:
                        temporary = Path(output.name)
                        with (snapshot / name).open('rb') as source_file:
                            shutil.copyfileobj(source_file, output)
                    temporary.replace(target)
                finally:
                    if temporary is not None:
                        temporary.unlink(missing_ok=True)
                restored += 1
        except (OSError, ValueError, KeyError, TypeError) as error:
            print(f'Ignoring {expected["name"]}: {error}')
    print(f'Restored {restored} Gradle task entries; Gradle determines cache hits from task inputs.')
    return restored
