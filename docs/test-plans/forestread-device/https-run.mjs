// Opt-in Go/ADB TLS qualification, using only a new empty UB fixture and lab-private data.
// Cloudflare terminates public TLS; the encrypted tunnel ends at a narrow loopback proxy.
import assert from 'node:assert/strict';
import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdtemp,writeFile,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve,dirname} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {randomBytes,randomUUID,createHash} from 'node:crypto';
import https from 'node:https';
import {setTimeout as pause} from 'node:timers/promises';
import {createEnrollmentProxy,fixtureAdmin,listen,close} from './https-proxy.mjs';
import {passed,target} from './run.mjs';
import {createConnectProxy} from './https-connect.mjs';
import {createReadStream} from 'node:fs';
import {pipeline} from 'node:stream/promises';

export function options(args) {
    assert.ok(args.length===4 || args.length===6,'Use --serial SERIAL --ub-repo /path/to/ultrabridge [--route direct|adb-proxy]');
    assert.equal(args[0],'--serial');assert.match(args[1],/^[A-Za-z0-9_.:-]+$/);
    assert.equal(args[2],'--ub-repo');
    if(args.length===6) {assert.equal(args[4],'--route');assert.ok(['direct','adb-proxy'].includes(args[5]));}
    return {serial:args[1],ub:resolve(args[3]),route:args[5]??'direct'};
}

export async function run({serial,ub,route='direct',mixed=false,book=null,foreground=false,library=false,renderer=false,annotations=false}) {
    assert.ok(!book || mixed,'Assets require explicit mixed mode');
    assert.ok(!foreground || book,'Foreground qualification requires a real book');
    assert.ok(!library || foreground,'Reader library qualification requires foreground mode');
    assert.ok(!renderer || library,'Renderer qualification requires shared library mode');
    assert.ok(!annotations || renderer,'Annotation qualification requires renderer mode');
    const dir=await mkdtemp(join(tmpdir(),'forestread-https-'));
    const abort=new AbortController();const children=new Set();const servers=[];const cleanups=[];
    const interrupt=()=>abort.abort();
    process.once('SIGINT',interrupt);process.once('SIGTERM',interrupt);
    const watchdog=setTimeout(interrupt,10*60_000);
    const report={version:1,status:'running',runId:`tls_${Date.now()}`,invocation:randomUUID(),phases:[],
        tls:'Android default trust to Cloudflare edge; encrypted tunnel; narrow loopback proxy to disposable UB',
        route,mixed,assets:!!book,foreground,library,renderer,annotations,productionActivated:false,started:new Date().toISOString()};
    const reversePorts=[];
    const redactions=[];
    const command=async(cmd,args,cwd,timeout=60_000)=>{
        try {return (await promisify(execFile)(cmd,args,{cwd,timeout,maxBuffer:2_000_000,signal:abort.signal})).stdout;}
        catch(e) {
            let diagnostic=String(e.stdout??'')+String(e.stderr??'');
            for(const secret of redactions) diagnostic=diagnostic.replaceAll(secret,'<redacted>');
            const file=`command-error-${randomUUID()}.log`;
            await writeFile(join(dir,file),diagnostic,{flag:'wx',mode:0o600});
            throw Error(`${cmd} failed (${e.code??'interrupted'}); see ${file}`); // Never echo secret-bearing argv.
        }
    };
    const adb=args=>command('adb',['-s',serial,...args],undefined,book?240_000:60_000);
    const start=(cmd,args,cwd,env=process.env)=>{
        const child=spawn(cmd,args,{cwd,env,stdio:['pipe','pipe','pipe']});children.add(child);
        let output='';let ended=false;
        const collect=v=>{if(output.length+v.length>2_000_000) child.kill('SIGTERM');else output+=v;};
        child.stdout.on('data',collect);child.stderr.on('data',collect);
        child.on('error',()=>{ended=true;});child.on('close',()=>{ended=true;children.delete(child);});
        return {child,output:()=>output,async until(pattern) {
            for(let i=0;i<240;i++) {
                abort.signal.throwIfAborted();const match=output.match(pattern);if(match) return match;
                if(ended || output.length>2_000_000) throw Error(`${cmd} stopped before readiness`);
                await pause(250,undefined,{signal:abort.signal});
            }
            throw Error(`${cmd} readiness timeout`);
        },async stop() {
            child.stdin.end();child.kill('SIGTERM');
            for(let i=0;i<40 && !ended;i++) await pause(50);
            if(!ended) {child.kill('SIGKILL');for(let i=0;i<20 && !ended;i++) await pause(50);}
            assert.ok(ended,'Fixture did not stop');
        }};
    };
    try {
        const root=resolve(dirname(fileURLToPath(import.meta.url)),'../../..');
        report.apks={};
        for(const [name,artifact] of [[target,'qualification/notes-qualification.apk'],
            [target+'.test','androidTest/qualification/notes-qualification-androidTest.apk']]) {
            const installed=(await adb(['shell','cmd','package','path',name])).trim();
            assert.match(installed,/^package:\/data\/app\/[A-Za-z0-9_~=/.-]+\/base\.apk$/);
            const hash=(await adb(['shell','sha256sum',installed.slice(8)])).split(/\s+/)[0];
            assert.match(hash,/^[a-f0-9]{64}$/);
            const candidate=createHash('sha256').update(await readFile(join(root,'app/notes/build/outputs/apk',artifact))).digest('hex');
            assert.equal(hash,candidate,'Installed lab APK must match the current local artifact');
            report.apks[name]=hash;
        }
        const permissions=await adb(['shell','dumpsys','package',target]);
        assert.match(permissions,/android.permission.INTERNET: granted=true/,'Install the opt-in network lab APK first');
        assert.doesNotMatch(permissions,/android.permission.(?:MANAGE|READ|WRITE)_EXTERNAL_STORAGE: granted=true/);
        report.device=(await adb(['shell','getprop','ro.build.fingerprint'])).trim();
        if(book) {
            const fingerprint=async()=>{
                const digest=createHash('sha256');let bytes=0;
                for await(const chunk of createReadStream(book)) {digest.update(chunk);bytes+=chunk.length;}
                return {sha256:digest.digest('hex'),bytes};
            };
            report.book=await fingerprint();
            assert.ok(report.book.bytes>2*262144 && report.book.bytes<=16*1024*1024,'Use a 3+ chunk EPUB, at most 16 MiB');
            const destination=`cache/asset-${report.runId}.epub`;
            cleanups.push(()=>adb(['shell','run-as',target,'rm',destination]));
            // Shell v2 without a PTY preserves bytes and waits for the remote exit status.
            const copy=spawn('adb',['-s',serial,'shell','-T','run-as',target,'dd',`of=${destination}`],{stdio:['pipe','ignore','pipe']});
            copy.stderr.resume();children.add(copy);
            const completed=new Promise((resolve,reject)=>{copy.on('error',reject);copy.on('close',code=>{
                children.delete(copy);code===0?resolve():reject(Error('Lab book copy failed'));
            });});
            await Promise.all([pipeline(createReadStream(book),copy.stdin),completed]);
            assert.deepEqual(await fingerprint(),report.book,'Source changed during copy');
            assert.equal((await adb(['shell','run-as',target,'sha256sum',destination])).split(/\s+/)[0],report.book.sha256);
        }
        report.ubRevision=(await command('git',['rev-parse','HEAD'],ub)).trim();
        report.fnRevision=(await command('git',['rev-parse','HEAD'],root)).trim();
        report.sources={};
        for(const file of ['app/notes/build.gradle.kts','app/notes/src/qualification/AndroidManifest.xml','app/notes/src/qualificationNetwork/AndroidManifest.xml',
            ...(mixed?['app/notes/src/qualificationTest/kotlin/com/forestnote/app/notes/MixedTransportQualification.kt',
                'app/notes/src/main/kotlin/com/forestnote/app/notes/MixedSyncCoordinator.kt',
                'app/notes/src/main/kotlin/com/forestnote/app/notes/ForegroundSyncDriver.kt',
                'app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt',
                'core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt',
                'gradle/rhizome-integration-revision.txt','docs/test-plans/forestread-device/mixed-run.mjs']:[]),
            ...(foreground?['docs/test-plans/forestread-device/foreground-run.mjs',
                'app/notes/src/qualification/kotlin/com/forestnote/app/notes/StorageQualificationActivity.kt']:[]),
            ...(library?['docs/test-plans/forestread-device/library-run.mjs',
                'app/notes/src/main/kotlin/com/forestnote/app/notes/ReaderLibraryAccess.kt']:[]),
            ...(renderer?['docs/test-plans/forestread-device/renderer-run.mjs',
                'app/notes/src/main/kotlin/com/forestnote/app/notes/ReaderHostView.kt',
                'app/notes/src/main/kotlin/com/forestnote/app/notes/ReaderResourcePolicy.kt',
                'app/notes/src/qualification/kotlin/com/forestnote/app/notes/ReaderHostQualificationActivity.kt',
                'app/notes/src/qualificationTest/kotlin/com/forestnote/app/notes/ReaderHostQualification.kt',
                'app/readerlab/scripts/prepare.mjs','app/readerlab/foliate-lock.json',
                'app/readerlab/src/main/assets/readerlab/shared-reader.html',
                'app/readerlab/src/main/assets/readerlab/shared-reader.js',
                'app/readerlab/src/main/assets/readerlab/reader.js']:[]),
            ...(annotations?['docs/test-plans/forestread-device/annotation-run.mjs',
                'app/notes/src/qualificationTest/kotlin/com/forestnote/app/notes/ReaderAnnotationQualification.kt',
                'core/reader/src/main/kotlin/com/forestnote/core/reader/ReaderEditRepository.kt',
                'core/reader/src/main/kotlin/com/forestnote/core/reader/ReaderProjectionRepository.kt']:[]),
            'app/notes/src/qualificationTest/kotlin/com/forestnote/app/notes/ReaderDeviceQualificationTest.kt',
            'app/notes/src/main/kotlin/com/forestnote/app/notes/enrollment/HttpsEnrollmentTransport.kt',
            'app/notes/src/main/kotlin/com/forestnote/app/notes/enrollment/ReplicaEnrollmentCoordinator.kt',
            ...(book?['docs/test-plans/forestread-device/https-assets.mjs','docs/test-plans/forestread-device/assets-run.mjs',
                'core/reader/src/main/kotlin/com/forestnote/core/reader/ReaderStorage.kt',
                'core/reader/src/main/kotlin/com/forestnote/core/reader/EpubImportValidator.kt']:[]),
            'docs/test-plans/forestread-device/https-proxy.mjs','docs/test-plans/forestread-device/https-connect.mjs','docs/test-plans/forestread-device/https-run.mjs'])
            report.sources[file]=createHash('sha256').update(await readFile(join(root,file))).digest('hex');
        console.log('Building disposable UB fixture');
        const binary=join(dir,'assetlab');const db=join(dir,'disposable.db');
        await command('go',['build','-o',binary,'./cmd/assetlab'],ub,120_000);
        let origin;
        const fixture=async()=>{
            const f=start(binary,['--db',db,'--reader','--reader-assets','--reader-enrollment'],ub);
            origin=(await f.until(/http:\/\/127\.0\.0\.1:\d+/))[0];return f;
        };
        let host=await fixture();
        const prefix='/'+randomBytes(32).toString('hex');const password=randomBytes(32).toString('hex');
        redactions.push(prefix,password);
        const proxy=createEnrollmentProxy({prefix,password,upstream:()=>origin,mixed,assets:!!book,suppressFirst:!mixed});
        const proxyPort=await listen(proxy.server);servers.push(proxy.server);report.proxy=proxy.evidence;
        // A valid-for-IP but untrusted one-day certificate; never installed in Android's trust store.
        await command('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=ForestRead Disposable',
            '-addext','subjectAltName=IP:127.0.0.1','-keyout',join(dir,'untrusted.key'),'-out',join(dir,'untrusted.crt')]);
        let untrustedHttpRequests=0;
        const untrusted=https.createServer({key:await readFile(join(dir,'untrusted.key')),cert:await readFile(join(dir,'untrusted.crt'))},(req,res)=>{
            untrustedHttpRequests++;res.writeHead(204);res.end();
        });
        const badPort=await listen(untrusted);servers.push(untrusted);
        // Explicit, non-rebinding ports also avoid adb 36's reverse allowlist collision
        // when two mappings are keyed by tcp:0. Occupied device ports fail safely.
        const reversePort=String(badPort);
        await adb(['reverse','--no-rebind',`tcp:${reversePort}`,`tcp:${badPort}`]);reversePorts.push(reversePort);
        await writeFile(join(dir,'tunnel.yml'),'{}\n',{flag:'wx',mode:0o600});
        const env=Object.fromEntries(Object.entries(process.env).filter(([key])=>!key.startsWith('TUNNEL_')));
        const tunnel=start('cloudflared',['tunnel','--config',join(dir,'tunnel.yml'),'--no-autoupdate',
            '--metrics','127.0.0.1:0','--protocol','http2','--url',`http://127.0.0.1:${proxyPort}`],dir,env);
        const publicUrl=(await tunnel.until(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/))[0];
        report.publicHostname=new URL(publicUrl).hostname;
        await tunnel.until(/Registered tunnel connection/);
        // Wait for DNS/edge readiness without sending any authority. Every exposed root is denied.
        let ready=false;
        for(let i=0;i<30 && !ready;i++) {
            try {
                const response=await fetch(publicUrl,{redirect:'error',signal:AbortSignal.timeout(3000)});
                ready=response.status===404 && response.headers.get('X-ForestRead-Qualification')==='enrollment-only';
                await response.body?.cancel();
            } catch {}
            if(!ready) await pause(1000,undefined,{signal:abort.signal});
        }
        assert.ok(ready,'Public TLS route not ready');
        console.log(`HTTPS route ready: ${report.publicHostname}`); // Hostname only, no private path or password.
        let proxyPortArg='';
        if(route==='adb-proxy') {
            const carrier=createConnectProxy(report.publicHostname);cleanups.push(()=>carrier.stop());report.carrier=carrier.evidence;
            const port=await listen(carrier.server);
            proxyPortArg=String(port);
            await adb(['reverse','--no-rebind',`tcp:${proxyPortArg}`,`tcp:${port}`]);reversePorts.push(proxyPortArg);
        }
        const registry=async()=>JSON.parse(await command('sqlite3',['-readonly','-json',db,
            'SELECT site_id,token_hash,revoked FROM sync_device_identity ORDER BY site_id']));
        for(const phase of (book?[foreground?'foreground-seed':'assets-seed','assets-pull','assets-reopen','assets-revoked']:mixed?['mixed-seed','mixed-pull','mixed-reopen','mixed-revoked']:
            ['https-refusal','https-seed','https-verify','https-confirmed','https-revoked'])) {
            abort.signal.throwIfAborted();
            if(phase==='https-verify' || phase==='mixed-pull' || phase==='assets-pull' || phase==='assets-reopen') {
                report.committedBeforeRetry=await registry();assert.equal(report.committedBeforeRetry.length,phase==='assets-reopen'?2:1);
                await host.stop();host=await fixture();assert.deepEqual(await registry(),report.committedBeforeRetry);
                report.serverRestartPreservedBinding=true;
            }
            if(phase==='https-revoked' || phase==='mixed-revoked' || phase==='assets-revoked') {
                const rows=await registry();assert.equal(rows.length,mixed?2:1);
                const revokedSite=mixed?proxy.evidence.enrollments.at(-1).site:rows[0].site_id;
                const r=await fetch(origin+'/sync/devices/v1/revoke',{method:'POST',signal:AbortSignal.timeout(5000),
                    headers:{Authorization:fixtureAdmin,'Content-Type':'application/json'},body:JSON.stringify({site_id:revokedSite})});
                assert.equal(r.status,204);await r.body?.cancel();
            }
            await adb(['shell','am','force-stop',target]);
            console.log(`HTTPS device qualification: ${phase}`);
            const output=await adb(['shell','am','instrument','-w','-r','-e','class','com.forestnote.app.notes.ReaderDeviceQualificationTest',
                '-e','runId',report.runId,'-e','invocation',report.invocation,'-e','phase',phase,
                '-e','httpsServer',publicUrl+prefix,'-e','httpsPassword',password,
                ...(book?['-e','bookHash',report.book.sha256]:[]),
                ...(library?['-e','readerLibrary','true']:[]),
                ...(renderer?['-e','readerRenderer','true']:[]),
                ...(annotations?['-e','readerAnnotations','true']:[]),
                ...(proxyPortArg?['-e','httpsProxyPort',proxyPortArg]:[]),
                '-e','httpsUntrusted',`https://127.0.0.1:${reversePort}`,target+'.test/androidx.test.runner.AndroidJUnitRunner']);
            await writeFile(join(dir,phase+'.log'),output.replaceAll(password,'<redacted>').replaceAll(prefix,'/<redacted>'),{flag:'wx',mode:0o600});
            const status=passed({code:0,output})?'passed':'failed';report.phases.push({phase,status});
            assert.equal(status,'passed',`${phase} failed; see local evidence`);
            if(book) {
                const chunks=proxy.evidence.assets.filter(x=>x.kind==='chunk');
                report.phases.at(-1).uploads=chunks.filter(x=>x.method==='PUT' && x.status===204).map(x=>x.index);
                report.phases.at(-1).downloads=chunks.filter(x=>x.method==='GET' && x.status===200).map(x=>x.index);
                if(phase==='assets-pull') assert.deepEqual(report.phases.at(-1).downloads,[0]);
                if(phase==='assets-revoked') assert.deepEqual(report.phases.at(-1).downloads,report.phases.at(-2).downloads);
            }
        }
        assert.equal(untrustedHttpRequests,0,'Untrusted endpoint received HTTP authority');
        report.untrustedHttpRequests=untrustedHttpRequests;
        if(mixed) {
            if(book) {
                const expected=Array.from({length:Math.ceil(report.book.bytes/262144)},(_,i)=>i);
                assert.deepEqual(report.phases.at(-1).uploads,expected);
                assert.deepEqual(report.phases.at(-1).downloads,expected);
            }
            assert.equal(proxy.evidence.suppressed,0);
            assert.deepEqual(proxy.evidence.enrollments.map(x=>x.status),[204,204]);
            assert.equal(new Set(proxy.evidence.enrollments.map(x=>x.site)).size,2);
            assert.ok(proxy.evidence.rows.length>=4);
            assert.ok(proxy.evidence.rows.every(x=>x.status===200 && x.count<=2));
            assert.equal(proxy.evidence.capabilities.at(-1).status,401);
            report.finalRegistry=await registry();assert.equal(report.finalRegistry.filter(x=>x.revoked===1).length,1);
        } else {
        assert.equal(proxy.evidence.suppressed,1);
        assert.deepEqual(proxy.evidence.enrollments.map(x=>x.status),[204,204,409]);
        assert.equal(new Set(proxy.evidence.enrollments.map(x=>x.site+':'+x.hash)).size,1);
        report.finalRegistry=await registry();assert.equal(report.finalRegistry[0].revoked,1);
        assert.equal(report.finalRegistry[0].token_hash,proxy.evidence.enrollments[0].hash);
        assert.deepEqual(proxy.evidence.capabilities.map(x=>x.status),[200,401,401,200,401,401,401]);
        }
        await host.stop();await tunnel.stop();
        report.status='passed';
    } catch(error) {report.status='failed';report.error=error.message;throw error;}
    finally {
        clearTimeout(watchdog);process.removeListener('SIGINT',interrupt);process.removeListener('SIGTERM',interrupt);
        for(const child of children) {child.stdin.end();child.kill('SIGTERM');}
        for(const cleanup of cleanups) await cleanup();
        for(const server of servers) await close(server);
        for(let i=0;i<40 && children.size;i++) await pause(50);
        for(const child of children) child.kill('SIGKILL');
        for(const reversePort of reversePorts) await promisify(execFile)('adb',['-s',serial,'reverse','--remove',`tcp:${reversePort}`],{timeout:10000}).catch(()=>{
            report.reverseCleanupFailed=true;report.status='failed';
        });
        report.finished=new Date().toISOString();
        await writeFile(join(dir,'report.json'),JSON.stringify(report,null,2),{flag:'wx',mode:0o600});
        console.log(`HTTPS evidence: ${join(dir,'report.json')}`);
        if(report.reverseCleanupFailed && !report.error) throw Error('Could not remove the qualification ADB reverse mapping');
    }
}
if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
    try {await run(options(process.argv.slice(2)));} catch(error) {console.error(error.message);process.exitCode=1;}
}
