// Narrow disposable-fixture boundary. Never expose assetlab's public admin directly.
import http from 'node:http';
import {timingSafeEqual} from 'node:crypto';

export const fixtureAdmin='Basic '+Buffer.from('assetlab:assetlab').toString('base64');
export const labAccount='forestread-disposable';
const equal=(a,b)=>typeof a==='string' && Buffer.byteLength(a)===Buffer.byteLength(b) && timingSafeEqual(Buffer.from(a),Buffer.from(b));

export function createEnrollmentProxy({prefix,password,upstream,suppressFirst=true}) {
    if(!/^\/[a-f0-9]{64}$/.test(prefix) || !/^[a-f0-9]{64}$/.test(password)) throw Error('Random test authority required');
    const approval='Basic '+Buffer.from(`${labAccount}:${password}`).toString('base64');
    const evidence={enrollments:[],capabilities:[],suppressed:0,rejected:0};
    let requests=0;
    const server=http.createServer({maxHeaderSize:8192,requestTimeout:5000,headersTimeout:5000},async(req,res)=>{
        res.setHeader('Cache-Control','no-store');
        res.setHeader('X-ForestRead-Qualification','enrollment-only');
        const end=code=>{res.writeHead(code);res.end();};
        try {
            if(++requests>100) return end(429);
            const enroll=req.method==='POST' && req.url===prefix+'/sync/devices/v1/enroll';
            const caps=req.method==='GET' && req.url===prefix+'/sync/capabilities';
            if(!enroll && !caps) {evidence.rejected++;return end(404);}
            const admin=equal(req.headers.authorization,approval);
            if(enroll && !admin) {evidence.rejected++;return end(401);}
            if(caps && !admin && !/^Bearer (fn-device-v1_[a-f0-9]{64}|[a-f0-9]{64})$/.test(req.headers.authorization??'')) return end(401);
            let body='';
            for await(const chunk of req) {
                body+=chunk.toString('utf8');
                if(Buffer.byteLength(body)>2048) {end(413);req.destroy();return;}
            }
            let value;
            if(enroll) {
                try {value=JSON.parse(body);} catch {return end(400);}
                if(!value || Object.keys(value).sort().join(',')!=='adopt_legacy,site_id,token_hash' ||
                    !/^[0-9A-HJKMNP-TV-Z]{26}$/.test(value.site_id) || !/^[a-f0-9]{64}$/.test(value.token_hash) || value.adopt_legacy!==false) return end(400);
            } else if(body) return end(400);
            const base=upstream();
            if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw Error('Loopback fixture required');
            const response=await fetch(base+(enroll?'/sync/devices/v1/enroll':'/sync/capabilities'),{
                method:req.method,redirect:'error',signal:AbortSignal.timeout(8000),
                headers:{Authorization:admin?fixtureAdmin:req.headers.authorization,'Content-Type':'application/json'},
                ...(enroll?{body}:{}),
            });
            await response.body?.cancel(); // No raw upstream body, header or secret is reflected.
            if(enroll) {
                evidence.enrollments.push({site:value.site_id,hash:value.token_hash,status:response.status});
                if(response.status===204 && suppressFirst && evidence.suppressed===0) {
                    evidence.suppressed++;return end(503); // Commit is real; success delivery is deliberately lost.
                }
            } else evidence.capabilities.push({kind:admin?'admin':req.headers.authorization.startsWith('Bearer fn-')?'token':'hash',status:response.status});
            end(response.status);
        } catch {if(!res.headersSent) end(502);else res.destroy();}
    });
    server.setTimeout(10000,socket=>socket.destroy());
    return {server,evidence};
}

export async function listen(server) {
    await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
    return server.address().port;
}
export async function close(server) {
    server.closeAllConnections();
    await new Promise(resolve=>server.close(resolve));
}
