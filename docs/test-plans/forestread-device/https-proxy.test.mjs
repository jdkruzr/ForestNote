import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import {readFile} from 'node:fs/promises';
import {options} from './https-run.mjs';
import {createConnectProxy} from './https-connect.mjs';
import {createEnrollmentProxy,fixtureAdmin,labAccount,listen,close} from './https-proxy.mjs';
const prefix='/'+ 'a'.repeat(64),password='b'.repeat(64);
const auth='Basic '+Buffer.from(`${labAccount}:${password}`).toString('base64');
const enrollment={site_id:'0'.repeat(25)+'1',token_hash:'c'.repeat(64),adopt_legacy:false};

test('mixed route is opt-in, bounded and device-token-only; capabilities and rows retain real bodies',async()=>{
    const calls=[];
    const upstream=http.createServer((req,res)=>{
        calls.push({path:req.url,auth:req.headers.authorization});req.resume();
        res.writeHead(200,{'Content-Type':'application/json'});
        res.end(JSON.stringify({fixture:req.url}));
    });
    const port=await listen(upstream);
    const p=createEnrollmentProxy({prefix,password,upstream:()=>`http://127.0.0.1:${port}`,mixed:true,suppressFirst:false});
    const url=`http://127.0.0.1:${await listen(p.server)}`;
    const token='Bearer fn-device-v1_'+'d'.repeat(64);
    const post=(authorization,ops=[],bounded='1')=>fetch(url+prefix+'/sync/v1',{
        method:'POST',headers:{Authorization:authorization,'X-Rhizome-Bounded-Rows':bounded},
        body:JSON.stringify({site_id:enrollment.site_id,ops})});
    try {
        for(const key of [fixtureAdmin,auth,'Bearer '+'c'.repeat(64)]) assert.equal((await post(key)).status,401);
        assert.equal((await post(token,[],'0')).status,401);
        assert.equal((await post(token,[{},{},{}])).status,413);assert.equal(calls.length,0);
        assert.deepEqual(await (await post(token)).json(),{fixture:'/sync/v1'});
        assert.deepEqual(await (await fetch(url+prefix+'/sync/capabilities',{headers:{Authorization:token}})).json(),{fixture:'/sync/capabilities'});
        assert.deepEqual(calls.map(x=>x.auth),[token,token]);assert.equal(p.evidence.rows.length,1);
        assert.equal((await fetch(url+prefix+'/sync/assets/v1',{headers:{Authorization:token}})).status,404);
    } finally {await close(p.server);await close(upstream);}
});

test('network opt-in changes only Internet permission; runner requires explicit ADB and UB repository',async()=>{
    const read=part=>readFile(new URL(`../../../app/notes/src/${part}/AndroidManifest.xml`,import.meta.url),'utf8');
    const offline=await read('qualification');const network=await read('qualificationNetwork');
    assert.equal(network,offline.replace('android.permission.INTERNET" tools:node="remove','android.permission.INTERNET" tools:node="merge'));
    for(const args of [[],['--serial','a'],['--serial',';bad','--ub-repo','/tmp/ub']]) assert.throws(()=>options(args));
    assert.equal(options(['--serial','device','--ub-repo','/tmp/ub']).ub,'/tmp/ub');
    assert.equal(options(['--serial','device','--ub-repo','/tmp/ub','--route','adb-proxy']).route,'adb-proxy');
    assert.throws(()=>options(['--serial','device','--ub-repo','/tmp/ub','--route','anything']));
});

test('ADB carrier forwards opaque bytes only to the exact disposable CONNECT destination',async()=>{
    const echo=net.createServer(socket=>socket.pipe(socket));const echoPort=await listen(echo);let dials=0;
    const carrier=createConnectProxy('only-this.trycloudflare.com',()=>{dials++;return net.connect(echoPort,'127.0.0.1');});
    const port=await listen(carrier.server);
    const connect=(path,headers={})=>new Promise((resolve,reject)=>{
        const request=http.request({host:'127.0.0.1',port,method:'CONNECT',path,headers});
        request.on('error',reject);request.on('connect',(response,socket)=>resolve({response,socket}));request.end();
    });
    try {
        for(const [path,headers] of [['other.trycloudflare.com:443',{}],['127.0.0.1:80',{}],
            ['only-this.trycloudflare.com:443',{'Proxy-Authorization':'secret'}]]) {
            const {response,socket}=await connect(path,headers);assert.equal(response.statusCode,403);socket.destroy();
        }
        assert.equal(dials,0);
        const {response,socket}=await connect('only-this.trycloudflare.com:443');assert.equal(response.statusCode,200);
        const bytes=Buffer.from([0,1,2,255,9]);
        const returned=new Promise((resolve,reject)=>{socket.once('data',resolve);socket.once('error',reject);});
        socket.write(bytes);assert.deepEqual(await returned,bytes);socket.destroy();assert.equal(dials,1);
        assert.equal(carrier.evidence.rejected,3);
    } finally {await carrier.stop();await new Promise(resolve=>echo.close(resolve));}
});

test('public fixture admin, unrelated paths, query strings and malformed/adoption bodies never reach UB',async()=>{
    let calls=0;
    const upstream=http.createServer((req,res)=>{calls++;assert.equal(req.headers.authorization,fixtureAdmin);req.resume();res.writeHead(204);res.end();});
    const port=await listen(upstream);
    const p=createEnrollmentProxy({prefix,password,upstream:()=>`http://127.0.0.1:${port}`});
    const url=`http://127.0.0.1:${await listen(p.server)}`;
    const request=(path,authorization=auth,body=enrollment)=>fetch(url+path,{method:'POST',headers:{Authorization:authorization},body:JSON.stringify(body)});
    try {
        assert.equal((await request(prefix+'/sync/devices/v1/enroll',fixtureAdmin)).status,401);
        for(const path of ['/sync/devices/v1/enroll',prefix+'/sync/v1',prefix+'/sync/devices/v1/revoke',prefix+'/sync/devices/v1/enroll?q=1']) assert.equal((await request(path)).status,404);
        for(const body of [{...enrollment,adopt_legacy:true},{...enrollment,raw_token:'bad'},{...enrollment,site_id:'bad'}]) assert.equal((await request(prefix+'/sync/devices/v1/enroll',auth,body)).status,400);
        assert.equal(calls,0);
        assert.equal((await request(prefix+'/sync/devices/v1/enroll')).status,503);
        assert.equal((await request(prefix+'/sync/devices/v1/enroll')).status,204);
        assert.equal(calls,2);assert.equal(p.evidence.suppressed,1);
        assert.deepEqual(p.evidence.enrollments.map(x=>x.hash),[enrollment.token_hash,enrollment.token_hash]);
        assert.ok(!JSON.stringify(p.evidence).includes(password));
    } finally {await close(p.server);await close(upstream);}
});

test('capability authority reaches UB unchanged except the explicit synthetic admin mapping',async()=>{
    const token='fn-device-v1_'+'e'.repeat(64);const seen=[];
    const upstream=http.createServer((req,res)=>{seen.push(req.headers.authorization);res.writeHead(req.headers.authorization==='Bearer '+token?200:401);res.end();});
    const port=await listen(upstream);
    const p=createEnrollmentProxy({prefix,password,upstream:()=>`http://127.0.0.1:${port}`});
    const url=`http://127.0.0.1:${await listen(p.server)}${prefix}/sync/capabilities`;
    try {
        for(const [authorization,status] of [[auth,401],['Bearer '+enrollment.token_hash,401],['Bearer '+token,200]]) assert.equal((await fetch(url,{headers:{Authorization:authorization}})).status,status);
        assert.deepEqual(seen,[fixtureAdmin,'Bearer '+enrollment.token_hash,'Bearer '+token]);
        assert.ok(!JSON.stringify(p.evidence).includes(token));
    } finally {await close(p.server);await close(upstream);}
});
