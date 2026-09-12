// D21: one human, multiple replica IDs. No customer data or production endpoints.
import assert from 'node:assert/strict';
import {readFile,copyFile} from 'node:fs/promises';
import {constants} from 'node:fs';
import {join} from 'node:path';

const site=label=>`0000000000000000000000000${label}`;
const preserved=s=>({cursor:s.cursor,outgoing:s.outgoing,rows:s.rows,versions:s.versions});
const admin={'Authorization':'Basic '+Buffer.from('assetlab:assetlab').toString('base64'),'Content-Type':'application/json'};
const key=async file=>readFile(file+'.device-key','utf8');
async function authorize(w,label) {assert.equal(await w[label].call('enroll',{url:w.url}),204)}
async function fresh(w,dir,label='C') {
  const p=await w.recovery({op:'prepare',dir});
  await w.client(label,p.path,p.replica);await w[label].call('enable');
  assert.equal((await w.snap(label)).outbox,0);
  await authorize(w,label);await w[label].call('connect',{url:w.url,authProfile:'enrolled'});
  await w.settle([label]);
  assert.equal((await w.snap(label)).outbox,0);
  return p;
}

export async function runRecoverySafety({scenario,seed,epubPath,waitSearch}) {
  const scenarios=[];
  async function run(name,body){await scenario(name,body);scenarios.push(name)}
  for(const reason of ['COPY','HISTORICAL_RESTORE','RETAINED_DATA_RESET','CREDENTIAL_LOSS']) {
    await run(`recovery-${reason.toLowerCase()}`,async w=>{
      const book=await seed(w);await w.A.call('recoveryGraph');
      await w.server(undefined,'enrolled');await authorize(w,'A');await authorize(w,'B');await w.connect();await w.check([epubPath]);
      const baseline=await w.snap('B');
      await w.A.call('writer',{key:'A7',text:'Unreconciled offline notebook'});
      await w.A.call('rename',{book,command:'uncertain-title',text:'Unreconciled book title'});
      const before=preserved(await w.snap('A'));
      const dir=join(w.dir,'recovery'),source=join(w.dir,'A.forestnote');
      const request={op:'snapshot',source,dir,reason,attempt:'same-attempt'};
      const saved=await w.recovery(request);assert.ok(saved.pending>=2);assert.ok(saved.books.every(b=>b.complete));
      assert.equal(saved.replica,site('A'));assert.equal(saved.reconciliation,'not_reconciled');
      assert.deepEqual(await w.recovery(request),saved);
      // Neither database-only copy nor reset carries its previous secret.
      const archive=join(dir,'archive.forestnote');
      assert.equal((await readFile(archive)).includes(Buffer.from(await key(source))),false);
      const inspector=w.recoveryTask({op:'write',source:archive,reason});
      assert.match((await inspector.next()).error,/no edit/);await inspector.close();
      if(reason==='HISTORICAL_RESTORE') {
        await w.A.call('writer',{key:'A8',text:'Beyond backup counter'});await w.settle(['A','B']);
        assert.equal((await w.snap('A')).outbox,0);
        assert.ok((await w.snap('A')).versions.some(v=>!before.versions.some(old=>JSON.stringify(old)===JSON.stringify(v))));
      }
      const serverBaseline=await w.snap('B');
      const newReplica=await fresh(w,dir);
      assert.notEqual(newReplica.replica,site('A'));
      const received=await w.snap('C');assert.deepEqual(received.rows,serverBaseline.rows);assert.deepEqual(received.versions,serverBaseline.versions);
      assert.deepEqual(await w.C.call('export',{book}),await w.B.call('export',{book}));
      await w.C.call('backfill');assert.equal((await w.snap('C')).outbox,0);
      // A new replica contributes to YOUR same annotation in a NEW session.
      await w.C.call('annotation',{key:'n',book});
      const edited=await w.snap('C');
      assert.ok(edited.rows.reader_edit_session.some(s=>s.id===`n-${newReplica.replica}`&&s.owner_site===newReplica.replica));
      for(const row of baseline.rows.reader_edit_session) assert.deepEqual(edited.rows.reader_edit_session.find(x=>x.id===row.id),row);
      assert.ok(edited.outgoing.length>0);assert.equal(edited.outgoing[0].seq,1);
      await w.settle(['C','B']);
      assert.deepEqual(await w.recovery({op:'inspect',source:archive,reason}),saved);
      if(reason!=='HISTORICAL_RESTORE') assert.deepEqual(preserved(await w.snap('A')),before);
      w.row.recovery={reason,archivePreserved:true,pending:saved.pending,freshReplica:newReplica.replica,mergedHistoricalEdits:false};
    });
  }
  for(const point of ['recovery_reserved','recovery_snapshot','recovery_replica','recovery_prepared']) {
    await run(`recovery-crash-${point}`,async w=>{
      await seed(w);const before=preserved(await w.snap('A'));
      const dir=join(w.dir,'recovery'),source=join(w.dir,'A.forestnote');
      const request={op:'snapshot',dir,source,reason:'COPY',attempt:'retry'};
      if(['recovery_replica','recovery_prepared'].includes(point)) await w.recovery(request);
      const op=['recovery_replica','recovery_prepared'].includes(point)?{op:'prepare',dir}:request;
      const p=w.recoveryTask({...op,checkpoint:point});assert.equal((await p.next()).checkpoint,point);await p.kill();
      const reserved=JSON.parse(await readFile(join(dir,'request.json'),'utf8')).replica;
      await w.recovery(request);const prepared=await w.recovery({op:'prepare',dir});assert.equal(prepared.replica,reserved);
      const credential=await key(prepared.path);assert.deepEqual(await w.recovery({op:'prepare',dir}),prepared);assert.equal(await key(prepared.path),credential);
      assert.deepEqual(preserved(await w.snap('A')),before);
      // Existing enrollment crash matrix covers private-save and lost-response
      // retry; this adds its fresh-recovery path through committed enrollment.
      await w.server(undefined,'enrolled');await w.client('C',prepared.path,prepared.replica);await w.C.call('enable');
      await w.C.call('arm',{name:'enrollment_accepted'});w.C.send('enroll',{url:w.url});
      assert.equal((await w.C.next()).checkpoint,'enrollment_accepted');await w.C.kill();
      await w.client('C',prepared.path,prepared.replica);await authorize(w,'C');assert.equal(await key(prepared.path),credential);
      w.row.recovery={checkpoint:point,replicaPreserved:true,sourcePreserved:true,enrollmentRetry:true};
    });
  }
  for(const point of ['restore_reserved','restore_snapshot','restore_fenced','restore_committed']) {
    await run(`recovery-server-${point}`,async w=>{
      const book=await seed(w);await w.A.call('recoveryGraph');
      await w.server(undefined,'enrolled');await authorize(w,'A');await authorize(w,'B');await w.connect();await w.check([epubPath]);
      const baseline=await w.snap('A');const snapshot=join(w.dir,'old-server.db');const inventory=await w.tool(['--reader-backup',snapshot]);
      // Later accepted work and revocation do not exist in that snapshot.
      await w.A.call('writer',{key:'A9',text:'Acknowledged after old server backup'});await w.settle(['A','B']);
      const revoke=await fetch(w.url+'/sync/devices/v1/revoke',{method:'POST',headers:admin,body:JSON.stringify({site_id:site('A')})});assert.equal(revoke.status,204);
      await w.A.call('writer',{key:'A6',text:'Offline after revocation'});
      const original=preserved(await w.snap('A'));
      await w.turn('A');assert.deepEqual(preserved(await w.snap('A')),original);
      await w.S.close();
      const dir=join(w.dir,'restored');const flags=['--reader-enrollment','--reader-restore',dir,'--restore-id','one'];
      const tool=w.startTool([...flags,'--checkpoint',point],snapshot);
      assert.equal((await tool.next()).checkpoint,point);await tool.kill();
      const reserved=JSON.parse(await readFile(join(dir,'restore-request.json'),'utf8')).site;
      const result=await w.tool(flags,snapshot);assert.equal(result.replica,reserved);assert.deepEqual(await w.tool(flags,snapshot),result);
      assert.deepEqual(await w.tool(['--reader-inventory'],snapshot),inventory);
      await w.server(undefined,'enrolled',result.path);
      const credential=await key(join(w.dir,'A.forestnote'));
      for(const route of ['/sync/v1','/sync/capabilities','/reader/search?q=marmalade',`/sync/assets/v1/${book}`,`/sync/assets/v1/${book}/chunks/0`]) {
        assert.equal((await fetch(w.url+route,{headers:{Authorization:'Bearer '+credential}})).status,401);
      }
      assert.equal(await w.A.call('enroll',{url:w.url,adoptLegacy:true}),409);
      assert.equal(await w.B.call('enroll',{url:w.url,adoptLegacy:true}),409);
      const recoveryDir=join(w.dir,'client-recovery');
      const saved=await w.recovery({op:'snapshot',source:join(w.dir,'A.forestnote'),dir:recoveryDir,reason:'HISTORICAL_RESTORE',attempt:'restore'});
      await fresh(w,recoveryDir);
      const recovered=await w.snap('C');assert.deepEqual(recovered.rows,baseline.rows);assert.deepEqual(recovered.versions,baseline.versions);
      assert.deepEqual(await w.C.call('export',{book}),await w.A.call('export',{book}));
      assert.ok(!recovered.rows.notebook.some(n=>n.name==='Acknowledged after old server backup'));
      assert.ok(original.rows.notebook.some(n=>n.name==='Acknowledged after old server backup'));
      assert.deepEqual(preserved(await w.snap('A')),original);assert.ok(saved.pending>0);
      w.row.recovery={checkpoint:point,oldCredentialsRejected:true,sourcePreserved:true,baselineRecovered:true,laterAcknowledgedWork:'preserved_not_reconciled'};
    });
  }
  await run('recovery-full-private-clone-limit-and-normal-restart',async w=>{
    const book=await seed(w);await w.server(undefined,'enrolled');await authorize(w,'A');await authorize(w,'B');await w.connect();await w.check([epubPath]);
    const before=preserved(await w.snap('A'));
    await w.A.kill();await w.client('A');await w.connect();assert.deepEqual(preserved(await w.snap('A')),before);
    const dir=join(w.dir,'archive');await w.recovery({op:'snapshot',source:join(w.dir,'A.forestnote'),dir,reason:'COPY',attempt:'clone'});
    const clone=join(w.dir,'private-clone.forestnote');await copyFile(join(dir,'archive.forestnote'),clone,constants.COPYFILE_EXCL);
    await copyFile(join(w.dir,'A.forestnote.device-key'),clone+'.device-key',constants.COPYFILE_EXCL);
    await w.client('C',clone,site('A'));await authorize(w,'C'); // Deliberately indistinguishable, not a hardware identity claim.
    assert.equal((await fetch(w.url+'/sync/capabilities',{headers:{Authorization:'Bearer '+await key(clone)}})).status,200);
    await w.S.close();await w.A.call('writer',{key:'A4',text:'Still offline-capable'});const queued=preserved(await w.snap('A'));
    await w.turn('A');assert.deepEqual(preserved(await w.snap('A')),queued);
    await w.server(undefined,'enrolled');await w.connect();await w.A.call('resume');await w.settle(['A','B']);
    assert.equal((await w.A.call('export',{book})).sha256,book);
    w.row.recovery={fullPrivateCloneDetected:false,normalRestartPreserved:true,networkFailurePreserved:true};
  });
  return {status:'passed',scenarios,singleHuman:true,automaticMerge:false,automaticFullCloneDetection:false,productionActivated:false,catalogAdaptersExecuted:0};
}
