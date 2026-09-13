import test from 'node:test';
import assert from 'node:assert/strict';
import {parseOptions,deviceCommand,passed,target,phaseSelection} from './run.mjs';

test('requires an explicit single transport and safe run identity',()=>{
    for(const args of [[],['--serial','a','--ssh','b'],['--ssh','-oProxyCommand=bad'],
        ['--serial','a','--run-id','../default'],['--serial','a','--port','8022'],['--ssh','host','--port','0']])
        assert.throws(()=>parseOptions(args));
    assert.equal(parseOptions(['--serial','USB','--run-id','safe'])['--run-id'],'safe');
});
test('device commands select only the named transport and isolated package',()=>{
    assert.notEqual(target,'com.forestnote');
    const adb=deviceCommand(parseOptions(['--serial','USB']),['am','force-stop',target]);
    assert.deepEqual(adb,['adb',['-s','USB','shell','am','force-stop',target]]);
    const ssh=deviceCommand(parseOptions(['--ssh','sysop@tablet']),['am','force-stop',target]);
    assert.equal(ssh[0],'ssh');assert.ok(ssh[1].includes('8022'));
    assert.match(ssh[1].at(-1),/^su -c /);assert.ok(ssh[1].at(-1).includes(target));
});
test('upgrade verification is an explicit restricted phase set',()=>{
    assert.equal(parseOptions(['--serial','USB','--phase-set','upgrade'])['--phase-set'],'upgrade');
    assert.equal(parseOptions(['--serial','USB','--phase-set','standard'])['--phase-set'],'standard');
    assert.throws(()=>parseOptions(['--serial','USB','--phase-set','reset']));
});
test('awake-only mode records sleep/wake as deferred, never silently passed',()=>{
    assert.equal(parseOptions(['--serial','USB','--phase-set','awake'])['--phase-set'],'awake');
    const all=phaseSelection(),awake=phaseSelection('awake');
    assert.equal(all.phases.length,22);assert.equal(awake.phases.length,21);
    assert.deepEqual(all.deferred,[]);assert.deepEqual(awake.deferred,['sleep-wake']);
    assert.deepEqual(awake.phases,all.phases.filter(x=>x!=='sleep-wake'));
    assert.deepEqual(phaseSelection('upgrade'),{phases:['upgrade-verify'],deferred:[]});
    assert.throws(()=>phaseSelection('arbitrary'));
});
test('requires one actual passing test, not a shell exit or an expected crash alone',()=>{
    assert.equal(passed({code:0,output:'OK (1 test)\nINSTRUMENTATION_CODE: -1'}),true);
    for(const output of ['','OK (0 tests)\nINSTRUMENTATION_CODE: -1','OK (1 test)',
        'OK (1 test)\nINSTRUMENTATION_CODE: -1\nFAILURES!!!',
        'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0'])
        assert.equal(passed({code:0,output}),false);
});
