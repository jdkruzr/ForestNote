// Test-only TCP carrier for networks whose DNS cannot resolve a new tunnel hostname.
// CONNECT does not terminate TLS. Only one exact disposable hostname:443 is reachable.
import http from 'node:http';
import net from 'node:net';
import {close} from './https-proxy.mjs';

export function createConnectProxy(hostname,dial=()=>net.connect(443,hostname)) {
    if(!/^[a-z0-9-]+\.trycloudflare\.com$/.test(hostname)) throw Error('Disposable tunnel hostname required');
    const sockets=new Set();const evidence={connections:0,rejected:0};
    const server=http.createServer({maxHeaderSize:8192,requestTimeout:5000,headersTimeout:5000},(req,res)=>{
        res.writeHead(404);res.end();
    });
    const track=socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));socket.on('error',()=>socket.destroy());};
    server.on('connection',socket=>{track(socket);socket.setTimeout(20000,()=>socket.destroy());});
    server.on('connect',(req,client,head)=>{
        if(req.url!==hostname+':443' || ++evidence.connections>50 || req.headers.authorization || req.headers['proxy-authorization']) {
            evidence.rejected++;client.end('HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');return;
        }
        const upstream=dial();track(upstream);
        upstream.setTimeout(20000,()=>upstream.destroy());
        upstream.on('error',()=>client.destroy());client.on('error',()=>upstream.destroy());
        upstream.on('close',()=>client.destroy());client.on('close',()=>upstream.destroy());
        upstream.once('connect',()=>{
            client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
            if(head.length) upstream.write(head);
            client.pipe(upstream);upstream.pipe(client);
        });
    });
    return {server,evidence,async stop() {for(const socket of sockets) socket.destroy();await close(server);}};
}
