import http from 'node:http';
import { readFile } from 'node:fs/promises';
const roots = [new URL('../src/main/assets/', import.meta.url), new URL('../build/generated/readerAssets/', import.meta.url)];
const types = { html: 'text/html', js: 'text/javascript', css: 'text/css', json: 'application/json', svg: 'image/svg+xml', epub: 'application/epub+zip' };
http.createServer(async (req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://localhost').pathname).slice(1) || 'readerlab/index.html';
  if (path.split('/').includes('..')) { res.writeHead(400).end(); return; }
  for (const root of roots) {
    try {
      const data = await readFile(new URL(path, root));
      res.writeHead(200, { 'Content-Type': types[path.split('.').pop()] ?? 'application/octet-stream', 'Cache-Control': 'no-store' }).end(data);
      return;
    } catch { /* next asset root */ }
  }
  res.writeHead(404).end('Not found');
}).listen(4173, '127.0.0.1', () => console.log('Reader Lab: http://127.0.0.1:4173/readerlab/index.html'));
