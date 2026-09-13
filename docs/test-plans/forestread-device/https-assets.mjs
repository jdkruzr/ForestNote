// Binary-safe, token-only subset for one explicitly enabled disposable asset fixture.
export async function forwardAsset(req,res,{prefix,upstream,evidence}) {
    if(!req.url.startsWith(prefix+'/sync/assets/v1/')) return false;
    const end=code=>{res.writeHead(code);res.end();return true;};
    const path=req.url.slice(prefix.length);
    const match=/^\/sync\/assets\/v1\/([a-f0-9]{64})(?:(\/chunks\/)(0|[1-9][0-9]{0,9})|\/chunks\?start=(0|[1-9][0-9]{0,9})&limit=([1-9][0-9]{0,2})|(\/complete))?$/.exec(path);
    if(!match) return end(404);
    const chunk=!!match[2],manifest=match[4]!==undefined,complete=!!match[6];
    if(!(complete?req.method==='POST':manifest?req.method==='GET' && Number(match[5])<=256:
        ['GET','PUT'].includes(req.method))) return end(404);
    if(!/^Bearer fn-device-v1_[a-f0-9]{64}$/.test(req.headers.authorization??'')) return end(401);
    const digest=req.headers['x-rhizome-chunk-sha256'];
    if(chunk && req.method==='PUT' && !/^[a-f0-9]{64}$/.test(digest??'')) return end(400);
    const cap=chunk && req.method==='PUT'?262144:2048;
    if(Number(req.headers['content-length']??0)>cap) return end(413);
    const parts=[];let size=0;
    for await(const part of req) {size+=part.length;if(size>cap) return end(413);parts.push(part);}
    if((req.method==='GET' || complete) && size!==0) return end(400);
    const base=upstream();if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw Error('Loopback fixture required');
    const response=await fetch(base+path,{method:req.method,redirect:'error',signal:AbortSignal.timeout(8000),
        headers:{Authorization:req.headers.authorization,'Content-Type':chunk?'application/octet-stream':'application/json',
            ...(digest?{'X-Rhizome-Chunk-SHA256':digest}:{})},
        ...(req.method!=='GET'?{body:Buffer.concat(parts)}:{})});
    const result=[];let length=0;const responseCap=chunk && req.method==='GET' && response.status===200?262144:65536;
    if(response.body) for await(const part of response.body) {
        length+=part.length;if(length>responseCap) return end(502);result.push(part);
    }
    evidence.push({method:req.method,asset:match[1],index:chunk?Number(match[3]):null,
        kind:chunk?'chunk':manifest?'manifest':complete?'complete':'descriptor',status:response.status,bytes:length});
    res.writeHead(response.status,{'Content-Type':chunk && req.method==='GET'?'application/octet-stream':'application/json',
        ...(response.status!==204?{'Content-Length':length}:{}),
        ...(response.headers.has('x-rhizome-chunk-sha256')?{'X-Rhizome-Chunk-SHA256':response.headers.get('x-rhizome-chunk-sha256')}:{})});
    res.end(Buffer.concat(result));return true;
}
