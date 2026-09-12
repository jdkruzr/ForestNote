// Real enrollment-backed Go admission + independently restartable Kotlin clients.
// No installed library, administrator secret or production endpoint is used.
import assert from 'node:assert/strict';
import {readFile,stat} from 'node:fs/promises';
import {join} from 'node:path';
import {createHash} from 'node:crypto';

const site=label=>`0000000000000000000000000${label}`;
const preserved=s=>({cursor:s.cursor,outgoing:s.outgoing,rows:s.rows,versions:s.versions});
const digest=s=>createHash('sha256').update(s).digest('hex');
async function privateKey(w,label) {
  const path=join(w.dir,`${label}.forestnote.device-key`);
  assert.equal((await stat(path)).mode & 0o777,0o600);
  const key=await readFile(path,'utf8'); assert.match(key,/^fn-device-v1_[0-9a-f]{64}$/); return key;
}
async function enroll(w,label,adoptLegacy=false) {
  assert.equal(await w[label].call('enroll',{url:w.url,adoptLegacy}),204);
  await privateKey(w,label);
}
async function revoke(w,label) {
  const response=await fetch(w.url+'/sync/devices/v1/revoke',{method:'POST',
    headers:{Authorization:'Basic '+Buffer.from('assetlab:assetlab').toString('base64'),'Content-Type':'application/json'},
    body:JSON.stringify({site_id:site(label)}),signal:AbortSignal.timeout(5000)});
  assert.equal(response.status,204);
}

export async function runEnrollmentIdentity({scenario,seed,epubPath,waitSearch}) {
  const scenarios=[];
  async function run(name,action) {await scenario(name,action);scenarios.push(name)}
  await run('enrolled-roundtrip-revocation',async w=>{
    const book=await seed(w,epubPath);const before=preserved(await w.snap('A'));
    await w.server(undefined,'enrolled');await enroll(w,'A');await enroll(w,'B');
    assert.deepEqual(preserved(await w.snap('A')),before,'Enrollment changed offline authorship/outbox');
    await w.connect();await w.check([epubPath]);await waitSearch(w,'marmalade',1);
    // Credentials survive server restart, and are never copied with the shared DB.
    await w.S.kill();await w.server(undefined,'enrolled');await w.connect();await w.check([epubPath]);
    const db=await readFile(join(w.dir,'A.forestnote'));
    const key=await privateKey(w,'A');assert.equal(db.includes(Buffer.from(key)),false);
    await revoke(w,'A');await w.A.call('writer',{key:'A5',text:'Still a local notebook'});
    await w.A.call('rename',{book,command:'revoked-title',text:'Still a local book'});
    const queued=preserved(await w.snap('A'));assert.ok(queued.outgoing.length>=2);
    for(let i=0;i<4;i++) await w.turn('A');
    assert.deepEqual(preserved(await w.snap('A')),queued,'Unauthorized exchange discarded or reauthored local work');
    assert.equal(await w.A.call('enroll',{url:w.url}),409,'Enrollment retry resurrected revocation');
    const backup=join(w.dir,'revoked-backup.db');
    await w.tool(['--reader-backup',backup]);
    assert.equal((await readFile(backup)).includes(Buffer.from(key)),false,'Server snapshot contains raw credential');
    await w.S.kill();await w.A.kill();await w.server(undefined,'enrolled',backup);await w.client('A');await w.connect();
    await w.turn('A');assert.deepEqual(preserved(await w.snap('A')),queued);
    for(const path of ['/sync/v1','/sync/capabilities','/reader/search?q=marmalade',`/sync/assets/v1/${book}`,`/sync/assets/v1/${book}/chunks/0`]) {
      const res=await fetch(w.url+path,{headers:{Authorization:'Bearer '+key},signal:AbortSignal.timeout(5000)});assert.equal(res.status,401);
    }
    // A revoked writer doesn't revoke the library for its other devices.
    const res=await fetch(w.url+'/reader/search?q=marmalade',{headers:{Authorization:'Bearer '+await privateKey(w,'B')},signal:AbortSignal.timeout(5000)});assert.equal(res.status,200);
    w.row.identity={site:site('A'),credentialHash:digest(key),revoked:true,revokedBackupRestored:true,outboxPreserved:true};
  });
  await run('enrollment-private-save-crash',async w=>{
    await seed(w,epubPath);await w.server(undefined,'enrolled');const before=preserved(await w.snap('A'));
    await w.A.call('arm',{name:'enrollment_saved'});w.A.send('enroll',{url:w.url});
    assert.equal((await w.A.next()).checkpoint,'enrollment_saved');const key=await privateKey(w,'A');
    await w.A.kill();await w.client('A');await enroll(w,'A');await enroll(w,'B');
    assert.equal(await privateKey(w,'A'),key);assert.deepEqual(preserved(await w.snap('A')),before);
    await w.connect();await w.check([epubPath]);w.row.identity={credentialHash:digest(key),retryPreserved:true};
  });
  await run('enrollment-committed-response-loss',async w=>{
    await seed(w,epubPath);const before=preserved(await w.snap('A'));
    await w.server('POST:/sync/devices/v1/enroll','enrolled');
    w.A.send('enroll',{url:w.url});assert.equal((await w.S.next()).checkpoint,'server_response');
    const key=await privateKey(w,'A');await w.A.kill();await w.S.kill();
    await w.server(undefined,'enrolled');await w.client('A');await enroll(w,'A');await enroll(w,'A');await enroll(w,'B');
    assert.equal(await privateKey(w,'A'),key);assert.deepEqual(preserved(await w.snap('A')),before);
    await w.connect();await w.check([epubPath]);w.row.identity={credentialHash:digest(key),committedRetryPreserved:true};
  });
  await run('enrollment-explicit-legacy-adoption',async w=>{
    await seed(w,epubPath);await w.server();await w.connect();await w.check([epubPath]);
    await w.A.call('writer',{key:'A6',text:'Pending through adoption'});
    const before=preserved(await w.snap('A'));await w.S.kill();await w.server(undefined,'enrolled');
    assert.equal(await w.A.call('enroll',{url:w.url}),409);
    assert.equal(await w.B.call('enroll',{url:w.url}),409);
    await enroll(w,'A',true);await enroll(w,'B',true);
    assert.deepEqual(preserved(await w.snap('A')),before);await w.connect();await w.check([epubPath]);
    w.row.identity={explicitAdoption:true,provenancePreserved:true};
  });
  return {status:'passed',scenarios,catalogAdaptersExecuted:0,productionActivated:false};
}
