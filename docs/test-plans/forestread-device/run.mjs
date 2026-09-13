import {spawn} from 'node:child_process';
import {mkdtemp, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';

export const target = 'com.forestnote.qualification';
const runner = `${target}.test/androidx.test.runner.AndroidJUnitRunner`;
const testClass = 'com.forestnote.app.notes.ReaderDeviceQualificationTest';
const crashVerifiers = new Map([
    ['crash-install','verify-crash'],
    ['recovery-kill-snapshot','recovery-verify-snapshot'],
    ['recovery-kill-fresh','recovery-verify-fresh'],
    ['selection-kill-before','selection-verify-before'],
    ['selection-kill-after','selection-verify-after'],
]);
const quote = value => `'${value.replaceAll("'", "'\\''")}'`;

export function parseOptions(args) {
    const options = {};
    for (let i=0; i<args.length; i+=2) {
        const key=args[i]; const value=args[i+1];
        if (!['--serial','--ssh','--port','--run-id','--phase-set'].includes(key) || !value || key in options)
            throw new Error('Use --serial SERIAL or --ssh USER@HOST [--port 8022], optional --run-id ID and --phase-set standard|upgrade');
        options[key]=value;
    }
    if (Boolean(options['--serial']) === Boolean(options['--ssh'])) throw new Error('Select exactly one device transport');
    if (options['--ssh'] && !/^[A-Za-z0-9_][A-Za-z0-9_.@-]*$/.test(options['--ssh'])) throw new Error('Invalid SSH target');
    if (options['--port'] && (!options['--ssh'] || !/^\d{1,5}$/.test(options['--port']) || +options['--port']<1 || +options['--port']>65535))
        throw new Error('Invalid SSH port');
    options['--run-id'] ??= `device_${Date.now()}`;
    if (!/^[A-Za-z0-9_-]{1,40}$/.test(options['--run-id'])) throw new Error('Invalid run ID');
    if(options['--phase-set'] && !['standard','upgrade'].includes(options['--phase-set'])) throw new Error('Invalid phase set');
    return options;
}

export function deviceCommand(options,args) {
    if (options['--serial']) return ['adb',['-s',options['--serial'],'shell',...args]];
    // Android commands run through Termux's root shell; no arbitrary user-supplied command string.
    return ['ssh',['-o','BatchMode=yes','-o','ConnectTimeout=10','-p',options['--port'] ?? '8022',
        '--',options['--ssh'],`su -c ${quote(args.map(quote).join(' '))}`]];
}

export function passed(result) {
    return result.code===0 && /\bOK \(1 test\)/.test(result.output) &&
        /INSTRUMENTATION_CODE: -1\b/.test(result.output) &&
        !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(result.output);
}

function execute(command,args) {
    return new Promise((resolve,reject) => {
        const child=spawn(command,args,{stdio:['ignore','pipe','pipe']});
        let output='';
        let timedOut=false;
        const timer=setTimeout(()=>{timedOut=true;child.kill('SIGTERM');},60_000);
        const collect=chunk=>{output+=chunk.toString();if(output.length>2_000_000) child.kill('SIGTERM');};
        child.stdout.on('data',collect);child.stderr.on('data',collect);
        child.on('error',error=>{clearTimeout(timer);reject(error);});
        child.on('close',(code,signal)=>{clearTimeout(timer);resolve({code,signal,timedOut,output});});
    });
}

export async function run(options) {
    const dir=await mkdtemp(join(tmpdir(),'forestread-device-'));
    const report={version:1,target,runId:options['--run-id'],invocation:randomUUID(),status:'running',phases:[],started:new Date().toISOString()};
    const call=async args=>execute(...deviceCommand(options,args));
    try {
        for(const name of [target,`${target}.test`]) {
            const installed=await call(['cmd','package','path',name]);
            if(installed.code!==0 || !/^package:/m.test(installed.output)) throw new Error(`Install ${name} first; runner never installs or replaces applications`);
        }
        report.fingerprint=(await call(['getprop','ro.build.fingerprint'])).output.trim();
        const phases=options['--phase-set']==='upgrade' ? ['upgrade-verify'] :
            ['smoke','sleep-wake','handoff','enrollment-seed','enrollment-verify',
                'recovery','recovery-kill-snapshot','recovery-verify-snapshot','recovery-kill-fresh','recovery-verify-fresh',
                'setup-ui','selection-kill-before','selection-verify-before','selection-kill-after','selection-verify-after',
                'seed','verify','crash-install','verify-crash'];
        for(const phase of phases) {
            const stopped=await call(['am','force-stop',target]);
            if(stopped.code!==0 || stopped.timedOut) throw new Error('Could not stop the isolated test process');
            console.log(`Device qualification: ${phase}`);
            const result=await call(['am','instrument','-w','-r','-e','class',testClass,
                '-e','runId',report.runId,'-e','invocation',report.invocation,'-e','phase',phase,runner]);
            await writeFile(join(dir,`${phase}.log`),result.output,{flag:'wx'});
            report.phases.push({phase,code:result.code,signal:result.signal,timedOut:result.timedOut,
                status:crashVerifiers.has(phase) ? 'awaiting-restart-verification' : passed(result) ? 'passed' : 'failed'});
            if(result.timedOut || (!crashVerifiers.has(phase) && !passed(result))) throw new Error(`${phase} failed; see ${dir}`);
            // An arbitrary crash is never enough: verify-crash checks the durable
            // armed marker, a different process, rollback, preserved ink and retry.
        }
        for(const [crashPhase,verifyPhase] of crashVerifiers) {
            const crash=report.phases.find(p=>p.phase===crashPhase);
            if(crash) {
                if(!report.phases.some(p=>p.phase===verifyPhase && p.status==='passed')) throw new Error(`${crashPhase} lacks restart proof`);
                crash.status='verified-by-restart';
            }
        }
        report.status='passed';
    } catch(error) {
        report.status='failed';report.error=error.message;throw error;
    } finally {
        report.finished=new Date().toISOString();
        await writeFile(join(dir,'report.json'),JSON.stringify(report,null,2),{flag:'wx'});
        console.log(`Evidence: ${join(dir,'report.json')}`);
    }
}

if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
    try {await run(parseOptions(process.argv.slice(2)));}
    catch(error) {console.error(error.message);process.exitCode=1;}
}
