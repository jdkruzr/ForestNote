// Headless, disposable-library verification. Never installs, publishes or deploys.
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { delimiter, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runSharedLibrary } from './shared-library-e2e.mjs';

const fn = fileURLToPath(new URL('../../../', import.meta.url));
let rhizome = resolve(fn, '../rhizome'), ub = resolve(fn, '../ultrabridge');
const suppliedBooks = [];
const suppliedImports = [];
let importDirectory;
for (let i = 2; i < process.argv.length; i++) {
  const option = process.argv[i], value = process.argv[++i];
  if (!value) throw new Error(`Missing value for ${option}`);
  if (option === '--rhizome') rhizome = resolve(value);
  else if (option === '--ub') ub = resolve(value);
  else if (option === '--book') suppliedBooks.push(resolve(value));
  else if (option === '--import-dir') importDirectory = resolve(value);
  else if (option === '--import-book') suppliedImports.push(resolve(value));
  else throw new Error(`Unknown option: ${option}`);
}
const output = await mkdtemp(join(tmpdir(), 'forestread-stage-2-'));
const report = { version: 1, started: new Date().toISOString(), status: 'running', commands: [], sources: {}, books: [], imports: [], suites: [],
  pendingCatalogBehaviorExecuted: 0,
  scope: 'Asset, bounded-row, scheduler, offline authorship, candidate reader storage/projection/ingress/worker/search regressions, Kotlin/Go parity and actual reader HTTP interoperability; NOT production activation or the full Stage 1 acceptance adapter.' };
const sha = bytes => createHash('sha256').update(bytes).digest('hex');

async function streamedIdentity(path) {
  const hash = createHash('sha256'); let bytes = 0;
  for await (const chunk of createReadStream(path)) { hash.update(chunk); bytes += chunk.length; }
  return { path, bytes, sha256: hash.digest('hex') };
}

async function bookPaths(directory) {
  const paths = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) paths.push(...await bookPaths(path));
    else if (entry.isFile() && /\.(epub|mobi|azw3)$/i.test(entry.name)) paths.push(path);
  }
  return paths.sort(); // Do not follow directory symlinks out of the requested corpus.
}

async function run(cwd, command, args, extraEnv = {}, capture = false) {
  console.log(`\n${command} ${args.join(' ')} (${cwd})`);
  const entry = { cwd, command, args, exitCode: null };
  report.commands.push(entry);
  let captured = '';
  await new Promise((resolveRun, reject) => {
    const child = spawn(command, args, { cwd, env: { ...process.env, ...extraEnv }, stdio: ['ignore', capture ? 'pipe' : 'inherit', 'inherit'] });
    if (capture) child.stdout.on('data', chunk => { captured += chunk; });
    child.on('error', reject);
    child.on('close', (code, signal) => {
      entry.exitCode = code; entry.signal = signal;
      if (code === 0) resolveRun(); else reject(new Error(`${command} failed: ${code ?? signal}`));
    });
  });
  return captured;
}

try {
  // Pin exact new sources and fixture catalogs, including uncommitted additions.
  for (const [root, paths] of [
    [rhizome, ['server-go/assets/assets.go', 'server-go/assets/sqlite.go', 'server-go/assets/http.go',
      'server-go/registry/registry.go', 'server-go/registry/forestnote.go',
      'client-kotlin/rhizome-core/src/main/kotlin/io/rhizome/core/Registry.kt',
      'client-kotlin/rhizome-core/src/main/kotlin/io/rhizome/core/Op.kt',
      'client-kotlin/rhizome-core/src/main/kotlin/io/rhizome/core/WireCodec.kt',
      'server-go/bounded/rows.go', 'server-go/bounded/http.go', 'server-go/bounded/rows_test.go',
      'server-go/syncstore/bounded.go', 'server-go/syncstore/bounded_test.go', 'server-go/syncstore/store.go',
      'server-go/syncsvc/service.go', 'server-go/synchttp/handler.go',
      'client-kotlin/rhizome-core/src/main/kotlin/io/rhizome/core/BoundedRows.kt',
      'client-kotlin/rhizome-core/src/main/kotlin/io/rhizome/core/SharedLibrarySync.kt',
      'client-kotlin/rhizome-sqlite/src/main/kotlin/io/rhizome/sqlite/SqliteTransferQueue.kt',
      'client-kotlin/rhizome-sqlite/src/main/kotlin/io/rhizome/sqlite/SqliteAssetReferences.kt',
      'client-kotlin/rhizome-sqlite/src/test/kotlin/io/rhizome/sqlite/SharedLibrarySyncTest.kt',
      'client-kotlin/rhizome-sqlite/src/test/kotlin/io/rhizome/sqlite/ScheduledInteropTest.kt',
      'client-kotlin/rhizome-sqlite/src/main/kotlin/io/rhizome/sqlite/SqliteStorageAdapter.kt',
      'client-kotlin/rhizome-sqlite/src/main/kotlin/io/rhizome/sqlite/SqliteColumnUpgrades.kt',
      'client-kotlin/rhizome-sqlite/src/test/kotlin/io/rhizome/sqlite/SqliteColumnUpgradeTest.kt',
      'client-kotlin/rhizome-sqlite/src/main/kotlin/io/rhizome/sqlite/IncomingRowPolicy.kt',
      'client-kotlin/rhizome-sqlite/src/test/kotlin/io/rhizome/sqlite/IncomingPolicyTest.kt',
      'client-kotlin/rhizome-sqlite/src/test/kotlin/io/rhizome/sqlite/SqliteOfflineAuthorTest.kt',
      'client-kotlin/rhizome-sqlite/src/test/kotlin/io/rhizome/sqlite/JdbcSqliteHandle.kt',
      'client-kotlin/rhizome-http/src/main/kotlin/io/rhizome/http/HttpUrlTransport.kt',
      'client-kotlin/rhizome-http/src/test/kotlin/io/rhizome/http/BoundedHttpTest.kt',
      'client-kotlin/rhizome-sqlite/src/test/kotlin/io/rhizome/sqlite/BoundedRowsTest.kt',
      'client-kotlin/rhizome-sqlite/src/test/kotlin/io/rhizome/sqlite/BoundedInteropTest.kt',
      'client-kotlin/rhizome-core/src/main/kotlin/io/rhizome/core/Assets.kt',
      'client-kotlin/rhizome-sqlite/src/main/kotlin/io/rhizome/sqlite/SqliteAssetStore.kt',
      'client-kotlin/rhizome-http/src/main/kotlin/io/rhizome/http/HttpAssetTransport.kt',
      'client-kotlin/rhizome-http/src/test/kotlin/io/rhizome/http/HttpAssetTransportTest.kt',
      'client-kotlin/rhizome-sqlite/src/test/kotlin/io/rhizome/sqlite/SqliteAssetStoreTest.kt',
      'client-kotlin/rhizome-sqlite/src/test/kotlin/io/rhizome/sqlite/AssetInteropTest.kt',
      'conformance/pending/assets-v1.json']],
    [ub, ['internal/syncassets/assets.go', 'internal/syncassets/assets_test.go', 'cmd/assetlab/main.go', 'cmd/assetlab/checkpoint.go', 'cmd/assetlab/inspect.go', 'cmd/assetlab/backup.go', 'cmd/assetlab/backup_test.go',
      'cmd/assetlab/restore.go', 'cmd/assetlab/restore_test.go',
      ...['store.go','http.go','store_test.go','recovery.go'].map(name => `internal/syncidentity/${name}`),
      ...['schema.go', 'store.go', 'snapshot.go', 'store_test.go', 'install_host_test.go', 'mixed_migration_test.go', 'work.go', 'worker.go', 'worker_test.go'].map(name => `internal/readerstore/${name}`),
      ...['handler.go', 'handler_test.go', 'enrollment_test.go', 'projection.go'].map(name => `internal/readerlab/${name}`),
      ...['schema.go', 'targets.go', 'projection.go', 'store.go', 'search.go', 'store_test.go'].map(name => `internal/readersearch/${name}`),
      'internal/syncstore/reader_candidate.go',
      'internal/syncstore/op.go',
      'third_party/rhizome-server-go/registry/registry.go', 'third_party/rhizome-server-go/registry/forestnote.go',
      ...['schema.go', 'validation.go', 'domain.go', 'ink.go', 'projection.go', 'projection_test.go', 'contract_test.go'].map(name => `internal/readercontract/${name}`),
      'internal/syncstore/bounded.go', 'internal/syncstore/store.go', 'internal/syncsvc/service.go',
      'internal/synchttp/handler.go', 'internal/synchttp/bounded_test.go']],
    [fn, ['docs/test-plans/forestread-stage-1/cases.json', 'app/readerlab/scripts/fixtures.mjs', 'app/readerlab/scripts/layout-fixtures.mjs',
      'docs/test-plans/forestread-stage-2/run.mjs', 'docs/test-plans/forestread-stage-2/huff-oracle.mjs', 'docs/test-plans/forestread-stage-2/shared-library-e2e.mjs',
      'docs/test-plans/forestread-stage-2/activation-safety.mjs',
      'docs/test-plans/forestread-stage-2/enrollment-identity.mjs',
      'docs/test-plans/forestread-stage-2/recovery-safety.mjs',
      'core/reader/src/main/kotlin/com/forestnote/core/reader/LibraryRecoveryPolicy.kt',
      'core/reader/src/test/kotlin/com/forestnote/core/reader/RecoveryFiles.kt',
      'core/reader/src/test/kotlin/com/forestnote/core/reader/LibraryRecoveryTest.kt',
      'core/reader/src/test/kotlin/com/forestnote/core/reader/SharedLibraryChild.kt',
      'core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq',
      'app/readerlab/foliate-lock.json', 'core/reader/build.gradle.kts', 'core/reader/settings.gradle.kts',
      'core/reader/writer-schema/build.gradle.kts',
      'core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt',
      'core/format/src/main/kotlin/com/forestnote/core/format/SchemaReconciliation.kt',
      'core/format/src/main/kotlin/com/forestnote/core/format/LegacySyncHistory.kt',
      'core/format/src/main/kotlin/com/forestnote/core/format/KnownWriterUpgrade.kt',
      'core/format/src/test/kotlin/com/forestnote/core/format/KnownWriterUpgradeTest.kt',
      'core/format/src/test/kotlin/com/forestnote/core/format/CursorResetTest.kt',
      'core/format/src/test/kotlin/com/forestnote/core/format/OutboxCaptureTest.kt',
      'app/notes/src/main/kotlin/com/forestnote/app/notes/SyncController.kt',
      'app/notes/src/test/kotlin/com/forestnote/app/notes/SyncControllerTest.kt',
      'core/format/src/main/kotlin/com/forestnote/core/format/PreservingDatabaseCallback.kt',
      'core/format/src/test/kotlin/com/forestnote/core/format/LegacySyncMigrationTest.kt',
      'core/format/src/test/kotlin/com/forestnote/core/format/PreservingDatabaseCallbackTest.kt',
      'app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt',
      'app/notes/src/main/kotlin/com/forestnote/app/notes/MixedSyncCoordinator.kt',
      'app/notes/src/main/kotlin/com/forestnote/app/notes/ForegroundSyncDriver.kt',
      'app/notes/src/main/kotlin/com/forestnote/app/notes/ReaderLibraryAccess.kt',
      'app/notes/src/test/kotlin/com/forestnote/app/notes/ReaderLibraryAccessTest.kt',
      'app/notes/src/main/kotlin/com/forestnote/app/notes/ReaderEditQueue.kt',
      'app/notes/src/test/kotlin/com/forestnote/app/notes/ReaderEditQueueTest.kt',
      'app/notes/src/qualificationTest/kotlin/com/forestnote/app/notes/ReaderEditQueueQualificationTest.kt',
      'app/notes/src/main/kotlin/com/forestnote/app/notes/ReaderHostView.kt',
      'app/notes/src/main/kotlin/com/forestnote/app/notes/ReaderResourcePolicy.kt',
      'app/notes/src/main/kotlin/com/forestnote/app/notes/ReaderInkCodec.kt',
      'app/notes/src/main/kotlin/com/forestnote/app/notes/ReaderAnnotationPresentation.kt',
      'app/notes/src/test/kotlin/com/forestnote/app/notes/ReaderAnnotationPresentationTest.kt',
      'app/notes/src/qualificationTest/kotlin/com/forestnote/app/notes/ReaderAnnotationRenderingTest.kt',
      'app/notes/src/qualification/kotlin/com/forestnote/app/notes/ReaderInkQualificationActivity.kt',
      ...['ReaderInkSurface','ReaderPreviewBackend','ReaderPenParams','ReaderInkJson','InkWorkerGeometry']
        .map(name=>`core/ink/src/main/kotlin/com/forestnote/core/ink/${name}.kt`),
      ...['LabInkViewTest','LabInkWorkerTest','LabPreviewTest']
        .map(name=>`core/ink/src/sharedReaderTest/kotlin/com/forestnote/readerlab/${name}.kt`),
      'app/notes/src/test/kotlin/com/forestnote/app/notes/ReaderResourcePolicyTest.kt',
      'app/readerlab/src/main/assets/readerlab/shared-reader.html',
      'app/readerlab/src/main/assets/readerlab/shared-reader.js',
      'app/readerlab/src/main/assets/readerlab/shared-annotations.js',
      'app/readerlab/tests/shared-reader.spec.js',
      'app/notes/src/test/kotlin/com/forestnote/app/notes/ForegroundSyncDriverTest.kt',
      'core/format/src/test/kotlin/com/forestnote/core/format/LocalCommitNotificationTest.kt',
      'app/notes/src/test/kotlin/com/forestnote/app/notes/MixedSyncStoreTest.kt',
      'app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt',
      'app/notes/src/main/res/values/strings.xml',
      'app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt',
      ...Array.from({length:19},(_,i)=>`core/format/src/main/sqldelight/com/forestnote/core/format/migrations/${i+1}.sqm`),
      ...['ReaderSchema', 'ReaderRecords', 'ReaderStorage', 'ReaderSyncRows', 'ReaderRepository', 'ReaderEditRepository',
        'ReaderStateRepository', 'ReferenceRepository', 'ReaderInk', 'ReaderValidation', 'ReaderProjection',
        'ReaderProjectionRepository', 'ReaderIngress', 'ReaderDomainRules', 'ReaderIncomingPolicy', 'ReaderImportRepository', 'EpubImportValidator', 'MobiImportValidator', 'HuffCdic'].map(name => `core/reader/src/main/kotlin/com/forestnote/core/reader/${name}.kt`),
      'core/ink/src/main/kotlin/com/forestnote/core/ink/BrushKind.kt',
      'core/format/src/main/kotlin/com/forestnote/core/format/ForestNoteRegistry.kt',
      ...['ReaderStorageTest', 'ReaderProjectionTest', 'ReaderIngressTest', 'ReaderOfflineTest', 'ReaderResponseCommitTest', 'ReaderImportTest', 'MobiImportTest', 'HuffCdicTest', 'ReaderContractTest', 'ReaderReducerParityTest', 'ReaderHttpInteropTest', 'MixedLibraryMigrationTest', 'SchemaReconciliationTest', 'WriterUpgradeRecoveryTest', 'LegacyHistoryUpgradeTest'].map(name => `core/reader/src/test/kotlin/com/forestnote/core/reader/${name}.kt`)]],
  ]) for (const path of paths) report.sources[join(root, path)] = sha(await readFile(join(root, path)));

  for (const file of ['assets/assets.go', 'assets/http.go', 'assets/sqlite.go', 'bounded/rows.go', 'bounded/http.go',
    'syncstore/bounded.go', 'syncstore/store.go', 'syncsvc/service.go', 'synchttp/handler.go', 'registry/registry.go']) {
    const source = await readFile(join(rhizome, 'server-go', file));
    const vendor = await readFile(join(ub, 'third_party/rhizome-server-go', file));
    if (!source.equals(vendor)) throw new Error(`UB vendored ${file} differs from Rhizome`);
  }

  let books = suppliedBooks;
  if (!books.length) {
    // Reuse authored Reader Lab fixtures; no downloads or Android build required.
    const require = createRequire(join(fn, 'app/readerlab/package.json'));
    const { zipSync } = require('fflate');
    const { generateFixtures } = await import(pathToFileURL(join(fn, 'app/readerlab/scripts/fixtures.mjs')));
    const fixtureDir = join(output, 'fixtures');
    await generateFixtures(pathToFileURL(fixtureDir + '/'), zipSync);
    books = ['unpleasant.epub', 'unpleasant.mobi', 'unpleasant-compressed.mobi'].map(name => join(fixtureDir, name));
  }
  for (const book of books) {
    const bytes = await readFile(book);
    report.books.push({ path: book, byteLength: bytes.length, sha256: sha(bytes) });
  }

  await run(fn, 'node', ['docs/test-plans/forestread-stage-1/validate.mjs', '--rhizome', rhizome, '--self-test']);
  await run(join(rhizome, 'server-go'), 'go', ['test', './...']);
  await run(ub, 'go', ['test', '-race', './internal/syncassets', './internal/syncstore', './internal/synchttp', './internal/syncsvc', './internal/syncidentity', './cmd/assetlab']);
  await run(ub, 'go', ['vet', './internal/syncassets', './internal/syncstore', './internal/synchttp', './internal/syncsvc', './internal/syncidentity', './internal/readercontract', './internal/readerstore', './internal/readerlab', './internal/readersearch', './cmd/assetlab']);
  const binary = join(output, 'assetlab');
  await run(ub, 'go', ['build', '-o', binary, './cmd/assetlab']);
  report.binarySha256 = sha(await readFile(binary));
  await run(join(rhizome, 'client-kotlin'), './gradlew', ['test', '--rerun-tasks'], {
    RHIZOME_ASSET_TEST_SERVER: binary, RHIZOME_ASSET_TEST_BOOKS: books.join(delimiter),
  });
  // Import corpus is separate from the small HTTP fixtures; never readFile a large book here.
  const importBooks = [...new Set([...(importDirectory ? await bookPaths(importDirectory) : []), ...suppliedImports])];
  if (importDirectory && !importBooks.length) throw new Error('No EPUB/MOBI/AZW3 books in requested import directory');
  if (importBooks.some(path => path.includes(delimiter))) throw new Error('Import corpus path contains platform list delimiter');
  const beforeImports = await Promise.all(importBooks.map(streamedIdentity));
  const importReport = join(output, 'imports.json');
  const huffVectors = join(output, 'huff-vectors.json');
  const contractVectors = join(output, 'reader-contract.json');
  const projectionVectors = join(output, 'reader-projections.json');
  await run(fn, join(rhizome, 'client-kotlin/gradlew'), ['-p', join(fn, 'core/reader'),
    `-PrhizomeCheckout=${rhizome}`, 'test', 'e2eClasspath', '--rerun-tasks'], {
      FORESTREAD_IMPORT_BOOKS: importBooks.join(delimiter), FORESTREAD_IMPORT_REPORT: importReport,
      FORESTREAD_HUFF_VECTORS: huffVectors,
      FORESTREAD_CONTRACT_VECTORS: contractVectors,
      FORESTREAD_PROJECTION_VECTORS: projectionVectors,
      FORESTREAD_TEST_SERVER: binary,
    });
  const shared = await runSharedLibrary({binary, classpath:(await readFile(join(fn,'core/reader/build/e2e-classpath.txt'),'utf8')).trim(),
    output:join(output,'shared-library'), corpus:importBooks, repeats:3});
  if(shared.status!=='passed' || shared.scenarios.length!==53+importBooks.length || shared.scenarios.some(s=>s.status!=='passed') ||
    shared.activation?.status!=='passed' || shared.activation.scenarios.length!==4 ||
    shared.enrollment?.status!=='passed' || shared.enrollment.scenarios.length!==4)
    throw Error('Shared-library/crash qualification incomplete');
  report.sharedLibrary = {report:join(output,'shared-library/report.json'), scenarios:shared.scenarios.length, crashRepetitions:3, corpus:shared.corpus.length};
  if(shared.recovery?.status!=='passed'||shared.recovery.scenarios.length!==13) throw Error('D21 recovery scenarios incomplete');
  report.recoverySafety=shared.recovery;
  report.activationSafety=shared.activation;
  report.enrollmentIdentity=shared.enrollment;
  const contractBytes = await readFile(contractVectors);
  const contract = JSON.parse(contractBytes);
  // Enforce execution with freshly generated vectors, never a cached/optional Go-only run.
  if (contract.version !== 1 || contract.registry.Tables.length !== 15 || contract.wire.length < 200 ||
      contract.domain.length < 28 || contract.fingerprints.length < 23) throw new Error('Incomplete reader contract vectors');
  const contractGoLog = await run(ub, 'go', ['test', '-race', '-count=1', '-json', './internal/readercontract', './internal/readerstore', './internal/readerlab', './internal/readersearch', './internal/syncidentity'],
    { FORESTREAD_CONTRACT_VECTORS: contractVectors, FORESTREAD_PROJECTION_VECTORS: projectionVectors }, true);
  const contractGoLogPath = join(output, 'reader-contract-go.jsonl');
  await writeFile(contractGoLogPath, contractGoLog);
  const contractEvents = contractGoLog.trim().split('\n').map(line => JSON.parse(line));
  if (!contractEvents.some(e => e.Test === 'TestKotlinContractVectors' && e.Action === 'pass') ||
      !contractEvents.some(e => e.Test === 'TestKotlinProjectionVectors' && e.Action === 'pass') ||
      !contractEvents.some(e => e.Test === 'TestKotlinRowsThroughSQLite' && e.Action === 'pass') ||
      !contractEvents.some(e => e.Test === 'TestMixedGapRejectedReceiptRestartAndLosslessRelay' && e.Action === 'pass') ||
      !contractEvents.some(e => e.Test === 'TestWorkerStartupLostWakeDependenciesAndIdle' && e.Action === 'pass') ||
      !contractEvents.some(e => e.Test === 'TestCurrentRecognitionAlternativesAndStaleSuppression' && e.Action === 'pass') ||
      !contractEvents.some(e => e.Test === 'TestEnrolledIdentityProtectsMixedRowsAssetsSearchAndManagement' && e.Action === 'pass') ||
      !contractEvents.some(e => e.Test === 'TestConcurrentEnrollmentCannotReplaceBinding' && e.Action === 'pass') ||
      !contractEvents.some(e => e.Test === 'TestUnknownIdentitySchemaFailsClosedWithoutRepair' && e.Action === 'pass') ||
      !contractEvents.some(e => e.Test === 'TestPopulatedHostUpgradeFailurePreservesRelayAssetsAndRevocation' && e.Action === 'pass') ||
      contractEvents.some(e => e.Action === 'skip' || e.Action === 'fail')) throw new Error('Go contract parity did not execute successfully');
  const projectionBytes = await readFile(projectionVectors);
  const projections = JSON.parse(projectionBytes);
  if (projections.version !== 1 || projections.permutations !== 12 || projections.cases.length < 60) throw new Error('Incomplete reader projection vectors');
  report.readerProjections = { path: projectionVectors, sha256: sha(projectionBytes), cases: projections.cases.length,
    permutationsPerCase: projections.permutations, statuses: [...new Set(projections.cases.map(c => c.expected.status))].sort() };
  report.readerStorage = { tests: contractEvents.filter(e => e.Package?.endsWith('/readerstore') && e.Test && e.Action === 'pass').map(e => e.Test),
    kotlinTables: 15, shuffledOrders: 4, scope: 'Explicit disposable notedb install, durable receipt/drain/mirrors and bounded snapshots; no production activation' };
  report.readerHttp = { tests: contractEvents.filter(e => e.Package?.endsWith('/readerlab') && e.Test && e.Action === 'pass').map(e => e.Test),
    scope: 'Candidate mixed reader/writer HTTP, A-UB-B repositories, background materialization and opt-in persistent device enrollment; no production enrollment or activation' };
  report.deviceIdentity = { tests:contractEvents.filter(e=>e.Package?.endsWith('/syncidentity') && e.Test && e.Action==='pass').map(e=>e.Test),
    scope:'Candidate persistent hash-to-site binding, explicit legacy adoption, retry/conflict/revocation and schema guards; Android vault/UI and recovery/rotation not wired' };
  report.readerWorker = { tests: contractEvents.filter(e => e.Test?.startsWith('TestWorker') && e.Action === 'pass').map(e => e.Test),
    scope: 'Restart-safe bounded materialization, durable at-least-once change delivery, independent consumer loop; candidate search consumer wired, no production activation' };
  report.readerSearch = { tests: contractEvents.filter(e => e.Package?.endsWith('/readersearch') && e.Test && e.Action === 'pass').map(e => e.Test),
    scope: 'Durable paged jobs, fingerprint-matched recognition/quote/title FTS, stale-result suppression, authenticated fixture HTTP; not production search/UI/embeddings' };
  report.readerContract = { path: contractVectors, sha256: sha(contractBytes),
    goLog: contractGoLogPath, goLogSha256: sha(Buffer.from(contractGoLog)),
    readerTables: contract.registry.Tables.length, writerTables: contract.production.Tables.length,
    readerHash: contract.schemaHash, productionHash: contract.productionHash, candidateCombinedHash: contract.combinedHash,
    wire: contract.wire.length, domain: contract.domain.length, authors: contract.authors.length,
    fingerprints: contract.fingerprints.length, composites: contract.composites.length };
  report.imports = JSON.parse(await readFile(importReport, 'utf8'));
  const huffReport = join(output, 'huff-oracle.json');
  await run(fn, 'node', ['docs/test-plans/forestread-stage-2/huff-oracle.mjs', huffVectors, huffReport]);
  report.huffOracle = JSON.parse(await readFile(huffReport, 'utf8'));
  if (importDirectory) {
    if (report.imports.length !== beforeImports.length) throw new Error('Import corpus incompletely exercised');
    const afterImports = await Promise.all(importBooks.map(streamedIdentity));
    for (const [i, before] of beforeImports.entries()) {
      const imported = report.imports[i];
      if (JSON.stringify(before) !== JSON.stringify(afterImports[i]) || imported.path !== before.path ||
          imported.bytes !== before.bytes || imported.sha256 !== before.sha256 || !imported.roundTrip || !imported.originalUnchanged)
        throw new Error(`Import or original-byte preservation mismatch: ${before.path}`);
    }
  }
  const resultDirs = ['rhizome-core', 'rhizome-http', 'rhizome-sqlite'].map(module =>
    [module, join(rhizome, 'client-kotlin', module, 'build/test-results/test')]);
  resultDirs.push(['forestread-storage', join(fn, 'core/reader/build/test-results/test')]);
  for (const [module, results] of resultDirs) {
    for (const file of await readdir(results)) {
      if (!file.startsWith('TEST-') || !file.endsWith('.xml')) continue;
      const xml = await readFile(join(results, file), 'utf8');
      const header = xml.match(/<testsuite\b[^>]*>/)?.[0];
      if (!header) throw new Error(`Missing test result: ${file}`);
      const attributes = Object.fromEntries([...header.matchAll(/(\w+)="([^"]*)"/g)].map(m => [m[1], m[2]]));
      report.suites.push({ module, name: attributes.name, tests: +attributes.tests, failures: +attributes.failures,
        errors: +attributes.errors, skipped: +attributes.skipped,
        cases: [...xml.matchAll(/<testcase\s+name="([^"]+)"/g)].map(m => m[1]), xmlSha256: sha(Buffer.from(xml)) });
    }
  }
  const interop = report.suites.find(s => s.name === 'io.rhizome.sqlite.AssetInteropTest');
  if (!interop || interop.tests !== 1 || interop.skipped || interop.failures || interop.errors) throw new Error('Interoperability did not execute successfully');
  const boundedInterop = report.suites.find(s => s.name === 'io.rhizome.sqlite.BoundedInteropTest');
  if (!boundedInterop || boundedInterop.tests !== 3 || boundedInterop.skipped || boundedInterop.failures || boundedInterop.errors) throw new Error('Bounded/offline-author/policy interoperability did not execute successfully');
  const scheduledInterop = report.suites.find(s => s.name === 'io.rhizome.sqlite.ScheduledInteropTest');
  if (!scheduledInterop || scheduledInterop.tests !== 1 || scheduledInterop.skipped || scheduledInterop.failures || scheduledInterop.errors) throw new Error('Scheduled interoperability did not execute successfully');
  const reader = report.suites.find(s => s.name === 'com.forestnote.core.reader.ReaderStorageTest');
  const migrations=report.suites.find(s=>s.name==='com.forestnote.core.reader.MixedLibraryMigrationTest');
  if(!migrations || migrations.tests!==6 || migrations.skipped || migrations.failures || migrations.errors) throw Error('Mixed-library migration qualification incomplete');
  report.mixedLibraryMigrations={tests:migrations.cases,writerSchema:'Actual SQLDelight .sq/.sqm generation, v19/v20 substrate',
    productionActivated:false,scope:'Additive candidate install, late failure/process-death rollback, same-file retry, preserved queues/provenance and old writer queries; Android safety tests run separately'};
  const readerHttp = report.suites.find(s => s.name === 'com.forestnote.core.reader.ReaderHttpInteropTest');
  if (!readerHttp || readerHttp.tests !== 6 || readerHttp.skipped || readerHttp.failures || readerHttp.errors) throw new Error('Actual reader HTTP interoperability did not execute successfully');
  const columnUpgrades=report.suites.find(s=>s.name==='io.rhizome.sqlite.SqliteColumnUpgradeTest');
  if(!columnUpgrades || columnUpgrades.tests!==7 || columnUpgrades.skipped || columnUpgrades.failures || columnUpgrades.errors) throw Error('Column upgrade qualification incomplete');
  report.columnUpgrades={tests:columnUpgrades.cases,productionActivated:false,
    scope:'Explicit additive-column tickets, equal-version repair without reauthoring, superseding edits, rollback, actual v4-hash HTTP and generated v19-to-v20 migration; not installed old APK or pre-Rhizome recovery'};
  const upgradeRecovery=report.suites.find(s=>s.name==='com.forestnote.core.reader.WriterUpgradeRecoveryTest');
  if(!upgradeRecovery || upgradeRecovery.tests!==2 || upgradeRecovery.skipped || upgradeRecovery.failures || upgradeRecovery.errors) throw Error('Writer upgrade recovery qualification incomplete');
  report.columnUpgrades.recoveryTests=upgradeRecovery.cases;
  const legacyHistory=report.suites.find(s=>s.name==='com.forestnote.core.reader.LegacyHistoryUpgradeTest');
  const recoveryPolicy=report.suites.find(s=>s.name==='com.forestnote.core.reader.LibraryRecoveryTest');
  if(!recoveryPolicy || recoveryPolicy.tests!==4 || recoveryPolicy.skipped || recoveryPolicy.failures || recoveryPolicy.errors) throw Error('Recovery policy qualification incomplete');
  if(!legacyHistory || legacyHistory.tests!==5 || legacyHistory.skipped || legacyHistory.failures || legacyHistory.errors) throw Error('Legacy sync history qualification incomplete');
  report.legacySyncHistory={tests:legacyHistory.cases,androidCallbackWired:true,deployed:false,
    scope:'Transfer before historical log drop, local archives, exact payload/provenance/counter verification, clock reseeding, generated v14/v18 upgrades and process-death rollback; missing historical data needs explicit backup recovery'};
  const reconciliation=report.suites.find(s=>s.name==='com.forestnote.core.reader.SchemaReconciliationTest');
  if(!reconciliation || reconciliation.tests!==4 || reconciliation.skipped || reconciliation.failures || reconciliation.errors) throw Error('Schema reconciliation qualification incomplete');
  report.schemaReconciliation={tests:reconciliation.cases,productionReaderActivated:false,
    scope:'Atomic replay scheduling, process-death retry, capability rejection, mixed pending payload preservation, HTTP additive-table recovery and v4-shaped writer payload admission; not old APK or arbitrary added-column qualification'};
  if (!reader || reader.tests < 9 || reader.skipped || reader.failures || reader.errors) throw new Error('Reader schema/repository regressions did not execute successfully');
  for (const [name, minimum] of [['ReaderProjectionTest', 8], ['ReaderIngressTest', 4], ['ReaderOfflineTest', 5], ['ReaderResponseCommitTest', 3], ['ReaderImportTest', 12], ['MobiImportTest', 6], ['HuffCdicTest', 7], ['ReaderContractTest', 2], ['ReaderReducerParityTest', 1]]) {
    const suite = report.suites.find(s => s.name === `com.forestnote.core.reader.${name}`);
    if (!suite || suite.tests < minimum || suite.skipped || suite.failures || suite.errors) throw new Error(`${name} did not execute successfully`);
  }
  const offline = report.suites.find(s => s.name === 'io.rhizome.sqlite.SqliteOfflineAuthorTest');
  if (!offline || offline.tests < 3 || offline.skipped || offline.failures || offline.errors) throw new Error('Offline authoring regressions did not execute successfully');
  const policy = report.suites.find(s => s.name === 'io.rhizome.sqlite.IncomingPolicyTest');
  if (!policy || policy.tests < 4 || policy.skipped || policy.failures || policy.errors) throw new Error('Shared incoming-policy regressions did not execute successfully');
  report.status = 'passed';
} catch (error) {
  report.status = 'failed'; report.error = String(error); process.exitCode = 1;
} finally {
  report.finished = new Date().toISOString();
  const path = join(output, 'report.json');
  await writeFile(path, JSON.stringify(report, null, 2) + '\n');
  console.log(`\nStage 2 asset/bounded-row/scheduler/reader-storage slices: ${report.status}. Evidence: ${path}`);
  if (report.error) console.error(report.error);
}
