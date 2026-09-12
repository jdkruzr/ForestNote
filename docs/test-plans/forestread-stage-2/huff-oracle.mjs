// Independent oracle for BENIGN authored vectors only. Never feed adversarial dictionaries to
// Foliate's unbounded decoder. This does not change or vendor any renderer source.
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
const [vectorPath, reportPath] = process.argv.slice(2);
if (!vectorPath || !reportPath) throw new Error('Usage: huff-oracle.mjs vectors.json report.json');
const root = new URL('../../../', import.meta.url);
const lock = JSON.parse(await readFile(new URL('app/readerlab/foliate-lock.json', root), 'utf8'));
let source;
try { source = await readFile(new URL('app/readerlab/build/generated/readerAssets/readerlab/vendor/foliate/mobi.js', root)); }
catch (error) {
  if (error.code !== 'ENOENT') throw error;
  const response = await fetch(`https://raw.githubusercontent.com/johnfactotum/foliate-js/${lock.commit}/mobi.js`, { signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw new Error(`Pinned Foliate source unavailable: ${response.status}`);
  source = Buffer.from(await response.arrayBuffer());
}
const digest = createHash('sha256').update(source).digest('hex');
if (digest !== lock.files['mobi.js']) throw new Error('Foliate oracle source checksum mismatch');
const { huffcdic } = await import(`data:text/javascript;base64,${Buffer.from(source.toString() + '\nexport { huffcdic };\n').toString('base64')}`);
const vectors = JSON.parse(await readFile(vectorPath, 'utf8'));
if (vectors.length !== 13) throw new Error('HUFF oracle vector coverage changed');
const cases = [];
for (const vector of vectors) {
  const records = vector.records.map(x => Uint8Array.from(Buffer.from(x, 'base64')).buffer);
  const decode = await huffcdic({ huffcdic: 0, numHuffcdic: records.length }, async i => records[i]);
  const output = decode(Uint8Array.from(Buffer.from(vector.encoded, 'base64')));
  const expected = Buffer.from(vector.text);
  if (!Buffer.from(output).equals(expected) || output.length !== vector.expandedLength)
    throw new Error(`HUFF oracle mismatch: ${vector.name}`);
  cases.push({ name: vector.name, bytes: output.length, sha256: createHash('sha256').update(output).digest('hex') });
}
await writeFile(reportPath, JSON.stringify({ status: 'passed', oracle: 'Reader Lab pinned Foliate', commit: lock.commit, sourceSha256: digest, cases }, null, 2) + '\n');
console.log(`HUFF/CDIC independent oracle: ${cases.length} vectors passed.`);
