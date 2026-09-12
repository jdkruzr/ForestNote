import assert from 'node:assert/strict';
import { copyFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { join } from 'node:path';

// Activation-safety regressions using D14's actual client/host processes.
// Related to COMP-02/UB-01/UB-02; NOT literal catalog adapters or migration approval.
export async function runActivationSafety({scenario,seed,epubPath,waitSearch}) {
  const completed=[];
  for(const profile of ['legacy','rows']) {
    const name=`rollback-${profile}`;
    await scenario(name,async w=>{
      const book=await seed(w); await w.server(); await w.connect(); await w.check([epubPath]);
      await w.B.call('writer',{key:'B9',text:'Foreign history establishes a real cursor'}); await w.check([epubPath]);
      // Start from a nonzero durable cursor, then queue interleaved writer/reader edits.
      await w.A.call('writer',{key:'A1',text:'Before unsupported host'});
      await w.A.call('rename',{book,command:'offline-rename',text:'Reader edit remains queued'});
      await w.A.call('writer',{key:'A2',text:'After reader edit'});
      const before=await w.snap('A'); assert.ok(before.cursor>0); assert.ok(before.outgoing.length>=3);
      await w.S.close(); await w.server(undefined,profile); await w.connect();
      const eventStart=w.row.events.length;
      const stopped=await w.A.call('step');
      assert.match(stopped,profile==='legacy'?/SchemaMismatch/:/lacks required sync capabilities/);
      await w.A.call('step');
      const after=await w.snap('A');
      assert.deepEqual(after.outgoing,before.outgoing); assert.equal(after.cursor,before.cursor);
      assert.deepEqual(after.rows,before.rows); assert.deepEqual(after.versions,before.versions);
      assert.ok(!w.row.events.slice(eventStart).some(e=>e.event==='row-post'),'Filtered or unadmitted POST reached host');
      assert.equal((await w.A.call('export',{book})).sha256,book);
      await w.A.call('writer',{key:'A3',text:'Offline editing remains available'});
      assert.ok((await w.snap('A')).outgoing.length>before.outgoing.length);
      await w.S.close(); await w.server(); await w.connect(); await w.A.call('resume'); await w.B.call('resume');
      // A1 was intentionally re-authored; other original rows must keep provenance.
      w.sourceVersions=w.sourceVersions.filter(v=>!['notebook','page','stroke'].includes(v.tbl));
      await w.check([epubPath]);
      assert.equal((await w.snap('B')).books.find(b=>b.id===book).title,'Reader edit remains queued');
      w.row.activation={cursorBefore:before.cursor,cursorAfterRejection:after.cursor,preservedOperations:before.outgoing.length,postedFilteredBatch:false};
    });
    completed.push(name);
  }
  for(const metadataOnly of [false,true]) {
    const name=metadataOnly?'restore-metadata-only':'restore-full';
    await scenario(name,async w=>{
      const book=await seed(w); await w.A.call('recoveryGraph');
      await w.server(); await w.connect(); await w.check([epubPath]); await waitSearch(w,'marmalade',1);
      const before=await w.snap('A');
      assert.equal(before.rows.reader_edit_session.find(s=>s.id==='cancelled-session').state,'cancelled');
      assert.ok(before.rows.content_reference.some(r=>r.id==='r'));
      const snapshot=join(w.dir,'snapshot.db');
      const backup=await w.tool(['--reader-backup',snapshot,...(metadataOnly?['--metadata-only']:[])]);
      assert.equal(backup.integrity,'ok'); assert.equal(backup.books,1);
      assert.equal(backup.backup_complete,!metadataOnly); assert.equal(backup.verified_books,metadataOnly?0:1);
      // Copy only the closed, consistent snapshot, never the running source DB/WAL.
      const restored=join(w.dir,'restored.db'); await copyFile(snapshot,restored,constants.COPYFILE_EXCL);
      const inventory=await w.tool(['--reader-inventory'],restored);
      assert.deepEqual(inventory,backup,'Restore changed persisted snapshot contents');
      const originalInventory=await w.tool(['--reader-inventory']);
      assert.equal(originalInventory.backup_complete,true,'Snapshot creation damaged source assets');
      for(const [table,hash] of Object.entries(backup.tables)) {
        if(table.startsWith('fn_')||table==='sync_ops'||table==='sync_cursors') assert.equal(originalInventory.tables[table],hash,`Source/backup ${table} mismatch`);
      }
      await w.S.close(); await w.B.close();
      await w.client('B',join(w.dir,'fresh-B.forestnote')); await w.B.call('enable');
      await w.server(undefined,'combined',restored); await w.connect();
      // This receiver has no cached content or rows. Its new download is required,
      // not retransmission of a persisted chunk in an interrupted local library.
      w.retries=Object.fromEntries(Object.keys(w.row.transferCounts).map(k=>[k,k.startsWith('B:down:') || (metadataOnly&&k.startsWith('A:up:'))?2:1]));
      if(metadataOnly) {
        const start=w.row.events.length;
        await w.A.call('step'); await w.A.call('step');
        const state=await w.snap('A'),job=state.jobs.find(j=>j.id===book);
        assert.equal(job.server,false); assert.ok(job.local); assert.notEqual(job.phase,'READY');
        assert.ok(w.row.events.slice(start).some(e=>e.event===`up:${book}:0`));
        // The entire server asset is genuinely missing, unlike D14's interrupted transfers.
      } else {
        await w.settle(['B']); // Original sender need not upload anything after full restore.
        assert.equal((await w.B.call('export',{book})).sha256,book);
      }
      await w.check([epubPath]);
      const after=await w.snap('A'); assert.deepEqual(after.rows,before.rows); assert.deepEqual(after.versions,before.versions);
      assert.equal((await w.tool(['--reader-inventory'])).backup_complete,true);
      await waitSearch(w,'marmalade',1);
      w.row.activation={backup,restoredBeforeSync:inventory,metadataPreserved:true,sourceAssetsPreserved:true,freshReceiver:true};
    });
    completed.push(name);
  }
  return {status:'passed',scenarios:completed,catalogAdaptersExecuted:0,productionActivated:false};
}
