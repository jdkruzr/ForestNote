// Independently killable real Kotlin clients + Go host. Disposable databases only.
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { readFile, writeFile, mkdir, mkdtemp } from 'node:fs/promises';
import { createReadStream, createWriteStream } from 'node:fs';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import assert from 'node:assert/strict';
import { runActivationSafety } from './activation-safety.mjs';
import { runEnrollmentIdentity } from './enrollment-identity.mjs';
import { runRecoverySafety } from './recovery-safety.mjs';

const fn = fileURLToPath(new URL('../../../', import.meta.url));
const chunk = 262144;
async function identity(path) {
  const hash = createHash('sha256'); let bytes = 0;
  for await (const data of createReadStream(path)) { bytes += data.length; hash.update(data); }
  return { bytes, sha256: hash.digest('hex') };
}
class Process {
  constructor(command, args, log, evidence, label) {
    this.lines = []; this.waiters = []; this.evidence = evidence;
    this.p = spawn(command, args, { stdio: ['pipe', 'pipe', 'pipe'] });
    this.log = createWriteStream(log); this.p.stderr.pipe(this.log);
    this.exited = new Promise(resolveExit => this.p.once('close', (code, signal) => {
      this.dead = true; evidence.push({ event: 'exit', code, signal });
      for (const w of this.waiters.splice(0)) w.reject(Error(`Child exited: ${code ?? signal}`)); resolveExit();
    }));
    this.p.once('error', error => { this.dead = true; for (const w of this.waiters.splice(0)) w.reject(error); });
    createInterface({ input: this.p.stdout }).on('line', line => {
      let data; try { data = JSON.parse(line); } catch { data = line; }
      if(data?.trace) { evidence.push({client:this.label ?? label,event:data.trace}); return; }
      if (data?.checkpoint) evidence.push({ event: 'checkpoint', name: data.checkpoint });
      if (this.waiters.length) this.waiters.shift().resolve(data); else this.lines.push(data);
    });
    this.seq = 0;
  }
  next() {
    if (this.lines.length) return Promise.resolve(this.lines.shift());
    if (this.dead) return Promise.reject(Error('Child already exited'));
    return new Promise((resolveNext, rejectNext) => {
      const timer = setTimeout(() => { this.p.kill('SIGKILL'); rejectNext(Error('Child/checkpoint deadline exceeded (30s)')); }, 30000);
      this.waiters.push({ resolve: x => { clearTimeout(timer); resolveNext(x); }, reject: e => { clearTimeout(timer); rejectNext(e); } });
    });
  }
  send(op, args = {}) { const id = ++this.seq; this.p.stdin.write(JSON.stringify({ id, op, ...args }) + '\n'); return id; }
  async call(op, args = {}) {
    const id = this.send(op, args), response = await this.next();
    assert.equal(response.id, id, `Unexpected event for ${op}: ${JSON.stringify(response)}`);
    if (response.error) throw Error(response.error);
    return response.result;
  }
  async kill() { this.expectedKill=true; if (!this.dead) this.p.kill('SIGKILL'); await this.exited; assert.equal(this.p.signalCode,'SIGKILL'); }
  async close() {
    if (!this.dead) this.p.stdin.end();
    const timer = setTimeout(() => this.p.kill('SIGKILL'), 5000);
    await this.exited; clearTimeout(timer); this.log.end();
    if(!this.expectedKill) assert.equal(this.p.exitCode,0,'Unexpected child exit (including forced shutdown)');
  }
}

export async function runSharedLibrary({ binary, classpath, output, corpus = [], repeats = 3 }) {
  await mkdir(output, { recursive: true });
  const report = { version: 1, status: 'running', scenarios: [], corpus: [], repeats };
  const require = createRequire(join(fn, 'app/readerlab/package.json'));
  const { zipSync, unzipSync } = require('fflate');
  const { generateFixtures } = await import(pathToFileURL(join(fn,'app/readerlab/scripts/fixtures.mjs')));
  const fixtures = join(output,'fixtures'); await generateFixtures(pathToFileURL(fixtures+'/'),zipSync);
  // Both books exceed three chunks without depending on compression ratios.
  const epub = unzipSync(await readFile(join(fixtures,'unpleasant.epub')));
  epub['stress.bin'] = new Uint8Array(chunk*4+19).map((_,i) => (i*31+i/255)&255);
  const epubPath=join(fixtures,'multi.epub'); await writeFile(epubPath,zipSync(epub,{ level:0 }));
  const mobiPath=join(fixtures,'multi.mobi');
  // Trailing resource payload is part of the last PalmDB resource; text length
  // remains header-declared. Import validates it and preserves every source byte.
  const mobi=await readFile(join(fixtures,'unpleasant.mobi'));
  const count=mobi.readUInt16BE(76), end=78+count*8+2;
  const pdb=Buffer.alloc(end+8); mobi.copy(pdb,0,0,end-2); pdb.writeUInt16BE(count+1,76);
  for(let i=0;i<count;i++) pdb.writeUInt32BE(mobi.readUInt32BE(78+i*8)+8,78+i*8);
  pdb.writeUInt32BE(mobi.length+8,78+count*8);
  await writeFile(mobiPath,Buffer.concat([pdb,mobi.subarray(end),Buffer.alloc(chunk*4+23,43)]));
  const defaults=[epubPath,mobiPath];
  let serial=0;
  async function scenario(name, action) {
    const dir=join(output,`${++serial}-${name}`); await mkdir(dir);
    const row={name,status:'running',events:[]}; report.scenarios.push(row);
    console.log(`Shared-library: ${name}`);
    const processes=[]; let generation=0;
    const w={dir,row};
    w.client=async (label,file=join(dir,`${label}.forestnote`),actor=`0000000000000000000000000${label}`) => {
      const p=new Process('java',['-Xmx96m','-cp',classpath,'com.forestnote.core.reader.SharedLibraryChild',file,actor,join(fn,'core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq')],join(dir,`${label}-${++generation}.log`),row.events);
      processes.push(p); p.label=label; assert.deepEqual(await p.next(),{ready:true}); w[label]=p; return p;
    };
    w.server=async (target,profile='combined',file=join(dir,'ub.db')) => {
      w.serverFile=file; w.profile=profile;
      const args=['--db',file]; if(profile!=='legacy') args.push('--reader'); if(['combined','enrolled'].includes(profile)) args.push('--reader-assets');
      if(profile==='enrolled') args.push('--reader-enrollment');
      if(target) args.push('--checkpoint',target);
      const p=new Process(binary,args,join(dir,`ub-${++generation}.log`),row.events); processes.push(p);
      const url=await p.next(); assert.match(url,/^http:\/\/127\.0\.0\.1:/); w.S=p; w.url=url; return p;
    };
    w.connect=async () => { for(const label of ['A','B']) await w[label].call('connect',{url:w.url,authProfile:w.profile==='legacy'?'legacy':w.profile==='enrolled'?'enrolled':'reader'}); };
    w.tool=async (flags,file=w.serverFile) => {
      const p=new Process(binary,['--reader','--reader-assets','--db',file,...flags],join(dir,`tool-${++generation}.log`),row.events);
      processes.push(p); const result=await p.next(); await p.close(); return result;
    };
    w.startTool=(flags,file=w.serverFile) => {
      const p=new Process(binary,['--reader','--reader-assets','--db',file,...flags],join(dir,`tool-${++generation}.log`),row.events);
      processes.push(p);return p;
    };
    w.recoveryTask=request => {
      const p=new Process('java',['-Xmx96m','-cp',classpath,'com.forestnote.core.reader.RecoveryChild',JSON.stringify(request)],join(dir,`recovery-${++generation}.log`),row.events);
      processes.push(p);return p;
    };
    w.recovery=async request => {const p=w.recoveryTask(request);const result=await p.next();await p.close();assert.ok(!result.error,result.error);return result};
    w.snap=async label => {
      const s=await w[label].call('inspect');
      for(const event of s.events) row.events.push({client:label,event});
      assert.equal(s.integrity,'ok'); return s;
    };
    w.turn=async label => { await w[label].call('step'); await w[label].call('drain'); };
    w.settle=async (labels=['A','B']) => {
      for(let i=0;i<2000;i++) {
        for(const label of labels) await w.turn(label);
        const states=await Promise.all(labels.map(w.snap));
        if(states.every(s=>s.books.length>0 && s.books.every(b=>b.ready) && s.jobs.length===s.books.length && s.jobs.every(j=>j.phase==='READY') && !s.outbox && !s.pending && !s.quarantined) &&
           (states.length===1 || JSON.stringify(states[0].rows)===JSON.stringify(states[1].rows))) return states;
      }
      throw Error('Combined clients did not settle');
    };
    w.check=async paths => {
      const [a,b]=await w.settle();
      assert.deepEqual(a.rows,b.rows); assert.deepEqual(a.versions,b.versions);
      for(const original of w.sourceVersions ?? []) assert.deepEqual(a.versions.find(v=>v.tbl===original.tbl&&v.pk===original.pk),original,'Received provenance changed');
      for(const label of ['A','B']) {
        await w[label].call('backfill'); assert.equal((await w.snap(label)).outbox,0);
        for(const path of paths) { const original=await identity(path); assert.deepEqual(await w[label].call('export',{book:original.sha256}),original); }
      }
      const counts={};
      for(const e of row.events) if(e.client && /^(up|down):/.test(e.event)) {
        const key=`${e.client}:${e.event}`; counts[key]=(counts[key]??0)+1;
        assert.ok(counts[key]<=(w.retries?.[key]??1),`Persisted chunk was transferred again: ${key}`);
      }
      row.transferCounts=counts;
      const inspection=new Process(binary,['--reader','--reader-assets','--reader-inspect','--db',w.serverFile],join(dir,`inspect-${++generation}.log`),row.events);
      processes.push(inspection);
      const host=await inspection.next(); await inspection.close();
      assert.equal(host.integrity,'ok'); assert.equal(host.pending,0); assert.equal(host.quarantined,0);
      assert.equal(host.duplicate_ops,0); assert.equal(host.local_table_ops,0);
      assert.equal(host.assets,a.books.length); assert.equal(host.notebooks,a.rows.notebook.length);
      assert.equal(host.pages,a.rows.page.length); assert.equal(host.strokes,a.rows.stroke.length);
      const projection=await w.A.call('projection',{key:'n'});
      assert.deepEqual(projection,await w.B.call('projection',{key:'n'}));
      assert.equal(host.projection.inputHash,projection.hash); assert.equal(host.projection.status,projection.status);
      assert.equal(host.projection.anchor,projection.anchor); assert.equal(host.projection.effectiveHeight,projection.height);
      row.host=host;
      row.final={books:a.books.length,rows:Object.fromEntries(Object.entries(a.rows).map(([t,rs])=>[t,rs.length])),versions:a.versions};
    };
    const timer=setTimeout(()=>{ row.timeout=true; for(const p of processes) p.p.kill('SIGKILL'); },120000);
    try {
      await w.client('A'); await w.client('B'); await action(w); assert.ok(!row.timeout); row.status='passed';
    } catch(error) { row.status='failed'; row.error=error.stack; throw error; }
    finally { clearTimeout(timer); await Promise.all(processes.map(p=>p.close())); await writeFile(join(output,'report.json'),JSON.stringify(report,null,2)); }
  }
  async function seed(w,path=epubPath) {
    const book=await w.A.call('import',{path,command:'import-A'});
    await w.A.call('writer',{key:'A1',text:'Ordinary offline note'});
    await w.A.call('annotation',{key:'n',book});
    await w.A.call('recognize',{key:'n',text:'electric marmalade'});
    await w.A.call('preferences');
    const offline=await w.snap('A'); assert.equal(offline.outbox,0);
    w.sourceVersions=offline.versions;
    await w.A.call('enable'); await w.B.call('enable');
    const authored=(await w.snap('A')).outgoing;
    assert.deepEqual(authored.map(op=>op.seq),authored.map((_,i)=>i+1));
    assert.ok(authored.some(op=>op.table==='reader_book')&&authored.some(op=>op.table==='stroke'));
    w.row.authored=authored;
    return book;
  }
  async function search(w,query) {
    const credential=w.profile==='enrolled'?'Bearer '+await readFile(join(w.dir,'A.forestnote.device-key'),'utf8'):'Basic '+Buffer.from('reader-a:readerlab').toString('base64');
    const response=await fetch(`${w.url}/reader/search?q=${query}`,{headers:{Authorization:credential},signal:AbortSignal.timeout(5000)});
    assert.equal(response.status,200); return response.json();
  }
  async function waitSearch(w,query,count) {
    for(let i=0;i<250;i++) { const hits=await search(w,query); if(hits.length===count) return hits; await new Promise(r=>setTimeout(r,20)); }
    throw Error(`Search did not reach ${count} hits`);
  }
  async function baseline(w,path,other) {
    const book=await seed(w,path);
    if(other) await w.B.call('import',{path:other,command:'import-B'});
    await w.server(); await w.connect();
    for(const route of ['/sync/v1','/sync/capabilities',`/sync/assets/v1/${book}`,'/reader/search?q=x']) {
      assert.equal((await fetch(w.url+route,{signal:AbortSignal.timeout(5000)})).status,401);
    }
    // Book metadata must be useful before content is transferable.
    await w.turn('A'); await w.turn('B');
    const b=await w.snap('B'); assert.equal(b.books.find(x=>x.id===book)?.ready,false);
    await assert.rejects(w.B.call('export',{book}),/Content pending|not_found/);
    await w.A.call('writer',{key:'A2',text:'Notes overtake books'});
    await w.turn('A'); await w.turn('B'); await w.turn('B');
    assert.ok((await w.snap('B')).rows.notebook.some(n=>n.name==='Notes overtake books'));
    assert.ok((await w.snap('A')).jobs.every(j=>!j.server));
    await w.check(other?[path,other]:[path]);
    assert.equal((await w.snap('B')).preferences,null);
    const old=await w.A.call('projection',{key:'n'}); assert.deepEqual(old,await w.B.call('projection',{key:'n'}));
    const hit=(await waitSearch(w,'marmalade',1))[0]; assert.equal(hit.input_hash,old.hash);
    await w.B.call('annotation',{key:'n',book}); await w.check(other?[path,other]:[path]);
    await waitSearch(w,'marmalade',0);
    const fresh=await w.B.call('recognize',{key:'n',text:'orbital custard'}); await w.check(other?[path,other]:[path]);
    assert.equal((await waitSearch(w,'custard',1))[0].input_hash,fresh);
    await w.A.call('rename',{book,command:'rename',text:'My title'});
    await w.A.call('trash',{book,command:'trash',deleted:true});
    assert.equal(await w.A.call('import',{path,command:'reimport'}),book);
    const retained=(await w.snap('A')).books.find(x=>x.id===book); assert.equal(retained.title,'My title'); assert.equal(retained.deleted,true);
    await w.check(other?[path,other]:[path]);
    // Fair scheduling: every pair of data transfers has a row opportunity.
    for(const label of ['A','B']) {
      const es=w.row.events.filter(e=>e.client===label).map(e=>e.event); let last=-1;
      for(let i=0;i<es.length;i++) if(/^(up|down):/.test(es[i])) { if(last>=0) assert.ok(es.slice(last+1,i).includes('rows')); last=i; }
    }
  }
  async function driveCheckpoint(w,label,name) {
    await w[label].call('arm',{name});
    for(let i=0;i<100;i++) {
      const id=w[label].send('step'), response=await w[label].next();
      if(response.checkpoint) { assert.equal(response.checkpoint,name); return; }
      assert.equal(response.id,id); assert.ok(!response.error,response.error); await w[label].call('drain');
    }
    throw Error(`Checkpoint not reached: ${name}`);
  }
  try {
    await scenario('combined-baseline',w=>baseline(w,...defaults));
    for(let repeat=0;repeat<repeats;repeat++) {
      for(const name of ['upload_checkpoint','upload_complete','mixed_commit','inbox_commit','download_checkpoint','download_complete']) {
        await scenario(`${name}-${repeat}`,async w=>{
          const book=await seed(w); await w.server(); await w.connect();
          const label=name.startsWith('upload')?'A':'B';
          if(label==='B') await w.settle(['A']);
          if(name==='mixed_commit') await w.B.call('writer',{key:'B4',text:'Local outbox must survive rollback'});
          const before=await w.snap(label);
          await driveCheckpoint(w,label,name); await w[label].kill(); await w.client(label); await w.connect();
          const after=await w.snap(label);
          if(name==='mixed_commit') { assert.equal(after.cursor,before.cursor); assert.deepEqual(after.rows,before.rows); assert.equal(after.pending,before.pending); assert.deepEqual(after.outgoing,before.outgoing); assert.ok(after.outgoing.length>0); }
          if(name==='inbox_commit') { assert.ok(after.cursor>before.cursor); assert.ok(after.pending>0); assert.equal(after.books.length,0); }
          if(name==='download_checkpoint') { assert.equal(after.chunks.filter(c=>c.id===book).length,1); assert.equal(after.jobs.find(j=>j.id===book).bytes,0); }
          await w.check([epubPath]);
          assert.equal((await w.B.call('export',{book})).sha256,book);
        });
      }
      for(const kind of ['rows','chunk','complete']) {
        await scenario(`server-${kind}-${repeat}`,async w=>{
          const book=await seed(w);
          const target=kind==='rows'?'POST:/sync/v1':kind==='chunk'?`PUT:/sync/assets/v1/${book}/chunks/0`:`POST:/sync/assets/v1/${book}/complete`;
          await w.server(target); await w.connect();
          const signal=w.S.next(); let reached=false;
          for(let i=0;i<100;i++) {
            const response=w.A.call('step');
            const event=await Promise.race([signal.then(x=>({gate:x})),response.then(x=>({result:x}))]);
            if(event.gate) {
              assert.equal(event.gate.checkpoint,'server_response'); await w.S.kill(); await response;
              reached=true; break;
            }
          }
          assert.ok(reached,'Server checkpoint not reached'); await w.server(); await w.connect(); await w.check([epubPath]);
        });
      }
      await scenario(`restart-all-${repeat}`,async w=>{
        const book=await seed(w); await w.server(); await w.connect();
        for(let i=0;i<3;i++) await w.turn('A'); await w.turn('B');
        assert.equal((await w.snap('B')).books.find(x=>x.id===book).ready,false);
        await Promise.all([w.A.kill(),w.B.kill(),w.S.kill()]);
        await w.client('A'); await w.client('B'); await w.server(); await w.connect(); await w.check([epubPath]);
      });
    }
    await scenario('corrupt-download',async w=>{
      const book=await seed(w); await w.server(); await w.connect(); await w.settle(['A']); await w.B.call('corrupt');
      let failed=false;
      for(let i=0;i<20;i++) { await w.turn('B'); const s=await w.snap('B'); if(s.jobs.some(j=>j.phase==='FAILED')) { assert.ok(!s.books.find(b=>b.id===book).ready); failed=true; break; } }
      assert.ok(failed,'Corruption did not fail closed'); await assert.rejects(w.B.call('export',{book}));
      await w.A.call('writer',{key:'A3',text:'Notes survive corrupt books'}); await w.turn('A'); await w.turn('B');
      assert.ok((await w.snap('B')).rows.notebook.some(n=>n.name==='Notes survive corrupt books'));
      assert.equal((await w.snap('B')).chunks.filter(c=>c.id===book).length,0);
      w.retries={[`B:down:${book}:0`]:2};
      await w.B.call('retry',{book}); await w.check([epubPath]);
    });
    report.activation = await runActivationSafety({scenario,seed,epubPath,waitSearch});
    report.enrollment = await runEnrollmentIdentity({scenario,seed,epubPath,waitSearch});
    report.recovery = await runRecoverySafety({scenario,seed,epubPath,waitSearch});
    for(const path of corpus) {
      const original=await identity(path);
      await scenario(`corpus-${report.corpus.length}`,w=>baseline(w,path));
      assert.deepEqual(await identity(path),original); report.corpus.push({path,...original,unchanged:true});
    }
    report.status='passed';
  } catch(error) { report.status='failed'; report.error=error.stack; throw error; }
  finally { await writeFile(join(output,'report.json'),JSON.stringify(report,null,2)); }
  return report;
}

if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const [binary,cpFile,...corpus]=process.argv.slice(2);
  assert.ok(binary && cpFile,'Usage: shared-library-e2e.mjs ASSETLAB CLASSPATH_FILE [BOOK...]');
  const output=await mkdtemp(join(tmpdir(),'forestread-shared-'));
  console.log(`Shared-library evidence: ${output}`);
  await runSharedLibrary({binary,classpath:(await readFile(cpFile,'utf8')).trim(),output,corpus});
  console.log('Shared-library suite passed');
}
