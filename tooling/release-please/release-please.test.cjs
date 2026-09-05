const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {Manifest, setLogger} = require('release-please');
const root = path.resolve(__dirname, '../..');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const current = JSON.parse(read('.release-please-manifest.json'))['.'];
const silent = {info() {}, debug() {}, warn() {}, error() {}};
setLogger(silent);

function github(message, options = {}) {
  return {
    repository: {owner: 'senseFy', repo: 'magrathea'},
    getFileJson: async name => JSON.parse(read(name)),
    getFileContentsOnBranch: async name => ({parsedContent: read(name)}),
    async *releaseIterator() {
      yield {tagName: `v${current}`, sha: 'previous', name: current};
    },
    async *mergeCommitIterator() {
      yield {sha: 'a'.repeat(40), message, files: ['magrathea-core/src/example.kt']};
      yield {sha: 'previous', message: 'previous release', files: []};
    },
    async *pullRequestIterator() {
      if (options.pending) yield options.pending;
    },
  };
}

async function candidate(message) {
  const gh = github(message);
  const manifest = await Manifest.fromManifest(gh, 'main', undefined, undefined, {logger: silent});
  const prs = await manifest.buildPullRequests();
  assert.equal(prs.length, 1);
  return prs[0];
}

function apply(pr) {
  return Object.fromEntries(pr.updates.map(update => [update.path, update.updater.updateContent(read(update.path), silent)]));
}

test('real Release Please config advances one SDK alpha and all controlled references', async () => {
  const pr = await candidate('fix: preserve managed session recovery');
  const next = current.replace(/\d+$/, n => String(Number(n) + 1));
  assert.equal(pr.version.toString(), next);
  assert.equal(pr.headRefName, 'release-please--branches--main');
  assert.equal(pr.group, undefined);
  const files = apply(pr);
  assert.equal(JSON.parse(files['.release-please-manifest.json'])['.'], next);
  for (const entry of JSON.parse(read('release-please-config.json')).packages['.']['extra-files']) {
    assert.equal(files[entry.path], read(entry.path).replaceAll(current, next), entry.path);
  }
  assert.match(files['CHANGELOG.md'], new RegExp(`^## \\[${next.replaceAll('.', '\\.')}\\]`, 'm'));
  assert.ok(!JSON.stringify(files).includes('SNAPSHOT'));
});

test('Release-As selects a higher alpha without a persistent config override', async () => {
  const pr = await candidate('fix: recover an unpublished candidate\n\nRelease-As: 0.1.0-alpha.999');
  assert.equal(pr.version.toString(), '0.1.0-alpha.999');
  assert.equal(JSON.parse(apply(pr)['.release-please-manifest.json'])['.'], '0.1.0-alpha.999');
});

test('release publication uses vVERSION without a module prefix', async () => {
  const pr = await candidate('feat: support a new provider');
  const gh = github('');
  const manifest = await Manifest.fromManifest(gh, 'main', undefined, undefined, {logger: silent});
  gh.pullRequestIterator = async function* () {
    yield {number: 1, title: pr.title.toString(), body: pr.body.toString(),
      labels: ['autorelease: pending'], sha: 'b'.repeat(40), files: [], headBranchName: pr.headRefName, baseBranchName: 'main'};
  };
  const releases = await manifest.buildReleases();
  assert.equal(releases.length, 1);
  assert.equal(releases[0].tag.toString(), `v${pr.version}`);
  assert.equal(releases[0].prerelease, true);
});

test('pending merged release blocks the next PR until publication completes', async () => {
  const pr = await candidate('fix: preserve previous notes');
  const pending = {number: 1, title: pr.title.toString(), body: pr.body.toString(), labels: ['autorelease: pending']};
  const manifest = await Manifest.fromManifest(github('fix: next change', {pending}), 'main', undefined, undefined, {logger: silent});
  assert.deepEqual(await manifest.createPullRequests(), []);
});
