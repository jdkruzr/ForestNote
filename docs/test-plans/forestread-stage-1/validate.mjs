#!/usr/bin/env node
// Contract integrity only. Deliberately NOT a fake reader/asset implementation or behavior runner.
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../../..');
const args = process.argv.slice(2);
let rhizome = resolve(root, '../rhizome');
let selfTest = false;
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--self-test') selfTest = true;
  else if (args[i] === '--rhizome' && args[i + 1] && !args[i + 1].startsWith('--')) rhizome = resolve(args[++i]);
  else throw Error(`Unknown/incomplete argument: ${args[i]}`);
}
const plain = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const nonempty = value => plain(value) && Object.keys(value).length > 0;
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const validHash = value => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);

function validate(catalog, path) {
  assert.equal(catalog.contract_version, 1, 'Unsupported fixture version');
  assert.equal(catalog.implementation_status, 'pending', 'Cannot declare behavior implemented through a fixture label');
  assert.equal(typeof catalog.suite, 'string');
  assert.ok(catalog.suite.length > 0);
  assert.equal(typeof catalog.spec, 'string');
  assert.ok(existsSync(resolve(dirname(path), catalog.spec)), `Missing spec: ${catalog.spec}`);
  assert.ok(nonempty(catalog.action_definitions), 'Missing action vocabulary');
  for (const [action, fields] of Object.entries(catalog.action_definitions)) {
    assert.match(action, /^[a-z_]+$/);
    assert.ok(Array.isArray(fields) && fields.every(f => typeof f === 'string'));
    assert.equal(new Set(fields).size, fields.length, `Repeated required field: ${action}`);
  }
  assert.ok(Array.isArray(catalog.cases) && catalog.cases.length > 0);
  const ids = new Set();
  for (const test of catalog.cases) {
    assert.match(test.id, /^[A-Z]+-\d{2}$/);
    assert.ok(!ids.has(test.id), `Duplicate case: ${test.id}`); ids.add(test.id);
    assert.ok(typeof test.requirement === 'string' && test.requirement.length > 10, `${test.id}: requirement`);
    assert.ok(plain(test.initial), `${test.id}: initial state`);
    assert.ok(nonempty(test.expected), `${test.id}: missing expected outcome`);
    assert.ok(Array.isArray(test.steps) && test.steps.length > 0, `${test.id}: no steps`);
    for (const step of test.steps) {
      assert.ok(plain(step), `${test.id}: malformed step`);
      const fields = catalog.action_definitions[step.action];
      assert.ok(fields, `${test.id}: unknown action ${step.action}`);
      for (const key of fields) assert.ok(Object.hasOwn(step, key), `${test.id}/${step.action}: missing ${key}`);
      if (Object.hasOwn(step, 'expect')) assert.ok(nonempty(step.expect), `${test.id}: empty intermediate expectation`);
      if (Object.hasOwn(step, 'version')) {
        assert.ok(Array.isArray(step.version) && step.version.length === 3);
        assert.ok(step.version.slice(0, 2).every(Number.isSafeInteger));
        assert.ok(catalog.symbols?.sites[step.version[2]], `${test.id}: unknown version site`);
      }
      if (Object.hasOwn(step, 'site')) assert.ok(catalog.symbols?.sites[step.site], `${test.id}: unknown site`);
      if (Object.hasOwn(step, 'points')) {
        assert.ok(Array.isArray(step.points) && step.points.length > 0);
        assert.ok(step.points.every(p => Array.isArray(p) && p.length === 4 && p.every(Number.isSafeInteger)));
      }
      if (Object.hasOwn(step, 'asset')) assert.ok(catalog.payloads?.[step.asset], `${test.id}: unknown asset`);
    }
  }
  if (catalog.payloads) {
    assert.equal(catalog.chunk_bytes, 262144, 'assets-v1 chunk size changed');
    for (const [name, payload] of Object.entries(catalog.payloads)) {
      const generator = payload.generator;
      assert.equal(generator.kind, 'repeat_byte');
      assert.ok(Number.isInteger(generator.byte) && generator.byte >= 0 && generator.byte <= 255);
      assert.ok(Number.isSafeInteger(generator.length) && generator.length >= 0 && generator.length <= 134217728);
      assert.ok(validHash(payload.sha256), `${name}: invalid SHA-256`);
      const rootHash = createHash('sha256');
      const hashes = [];
      for (let offset = 0; offset < generator.length; offset += catalog.chunk_bytes) {
        const bytes = Buffer.alloc(Math.min(catalog.chunk_bytes, generator.length - offset), generator.byte);
        hashes.push(sha(bytes)); rootHash.update(bytes);
      }
      assert.deepEqual(hashes, payload.chunk_hashes, `${name}: chunk hashes`);
      assert.equal(rootHash.digest('hex'), payload.sha256, `${name}: whole-object hash`);
    }
  }
  return ids;
}

const files = [resolve(here, 'cases.json'), resolve(rhizome, 'conformance/pending/assets-v1.json')];
const catalogs = files.map(path => JSON.parse(readFileSync(path, 'utf8')));
const allIds = new Set();
catalogs.forEach((catalog, i) => {
  for (const id of validate(catalog, files[i])) {
    assert.ok(!allIds.has(id), `Cross-suite duplicate ${id}`); allIds.add(id);
  }
});
const expectedGroups = {BOOK:2, INK:9, DELETE:2, STATE:1, OCR:1, REF:10, IMPORT:2, COMP:2, UB:2, ASSET:16};
for (const [prefix, count] of Object.entries(expectedGroups)) {
  for (let i = 1; i <= count; i++) assert.ok(allIds.has(`${prefix}-${String(i).padStart(2, '0')}`), `Missing approved coverage: ${prefix}-${i}`);
}
assert.equal(allIds.size, Object.values(expectedGroups).reduce((a, b) => a + b, 0), 'Update coverage map for added cases');

// Check local Markdown file links in the new spec set. URI/code examples and fragments are not files.
const documents = [
  resolve(root, 'docs/design-plans/2026-09-07-forestread-stage-1.md'),
  resolve(here, 'README.md'),
  resolve(root, '../rhizome/spec/assets-v1.md'),
  resolve(root, '../rhizome/conformance/pending/README.md'),
  resolve(root, '../ultrabridge/docs/sync/forestread-stage-1-rollout.md'),
];
const logicalRhizome = resolve(root, '../rhizome');
const actualPath = path => path === logicalRhizome || path.startsWith(`${logicalRhizome}/`)
  ? resolve(rhizome, path.slice(logicalRhizome.length + 1)) : path;
for (const path of documents) {
  const markdown = readFileSync(actualPath(path), 'utf8');
  for (const match of markdown.matchAll(/\[[^\]\n]+\]\(([^)\n]+)\)/g)) {
    const target = match[1].split('#')[0];
    if (!target || /^[a-z]+:/i.test(target)) continue;
    // Resolve documented sibling links through the explicit checkout override, if provided.
    assert.ok(existsSync(actualPath(resolve(dirname(path), target))), `Broken link in ${path}: ${target}`);
  }
}

if (selfTest) {
  const reject = (index, change) => {
    const altered = structuredClone(catalogs[index]); change(altered);
    assert.throws(() => validate(altered, files[index]));
  };
  reject(0, c => { c.implementation_status = 'passed'; });
  reject(0, c => { c.cases.push(c.cases[0]); });
  reject(0, c => { c.cases[0].steps[0].action = 'invented_operation'; });
  reject(0, c => { delete c.cases[0].steps[0].book; });
  reject(0, c => { c.cases[0].expected = {}; });
  reject(1, c => { c.payloads.two_chunks.sha256 = '0'.repeat(64); });
  reject(1, c => { c.chunk_bytes = 524288; });
  console.log('Validator self-tests: 7 rejection checks passed.');
}
console.log(`Fixture integrity: ${allIds.size} pending cases across ${catalogs.length} catalogs; links and generated payload hashes valid.`);
console.log(`Behavioral tests executed: 0. Behavioral cases pending implementation: ${allIds.size}.`);
