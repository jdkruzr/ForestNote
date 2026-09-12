import { AsyncInflate } from './vendor/fflate.js';

// ZIP entries use raw DEFLATE. Some WebViews expose DecompressionStream but
// accept only gzip/zlib, so checking that the constructor exists is insufficient.
// Keep this adapter local to Foliate's ZIP loader, not a global browser polyfill.
export class RawDeflateStream {
  constructor(format) {
    if (format !== 'deflate-raw') throw new TypeError(`Unsupported compression format: ${format}`);
    let output, input, pending, inflater, stopped = false;
    const finishWrite = () => { const task = pending; pending = null; task?.resolve(); };
    const fail = reason => {
      if (stopped) return;
      stopped = true;
      const error = reason instanceof Error ? reason : new Error(String(reason ?? 'Decompression cancelled'));
      inflater?.terminate();
      output.error(error); input.error(error);
      const task = pending; pending = null; task?.reject(error);
    };
    this.readable = new ReadableStream({
      start(controller) { output = controller; },
      pull() { if (pending?.delivered) finishWrite(); },
      cancel: fail,
    });
    const push = (chunk, final) => new Promise((resolve, reject) => {
      pending = { resolve, reject, delivered: false };
      try {
        // AsyncInflate transfers its input buffer. Never detach the ZIP reader's
        // original chunk (which can also be used for checksums or other entries).
        inflater.push(chunk.slice(), final);
      } catch (error) { fail(error); }
    });
    this.writable = new WritableStream({
      start(controller) { input = controller; },
      write: chunk => push(chunk, false),
      close: () => push(new Uint8Array(), true),
      abort: fail,
    });
    inflater = new AsyncInflate((error, chunk, final) => {
      if (stopped) return;
      if (error) { fail(error); return; }
      if (chunk.length) output.enqueue(chunk);
      if (final) {
        stopped = true; output.close(); inflater.terminate(); finishWrite();
      } else {
        pending.delivered = true;
        // One worker chunk in flight, with downstream backpressure. Neither
        // decompression nor whole-book buffering belongs on the WebView thread.
        if (!chunk.length || output.desiredSize > 0) finishWrite();
      }
    });
  }
}

let configured;
export function ensureBookDecompression() {
  return configured ??= (async () => {
    try {
      new DecompressionStream('deflate-raw');
      return 'native';
    } catch {
      const { configure } = await import('./vendor/foliate/vendor/zip.js');
      configure({ DecompressionStream: RawDeflateStream });
      return 'worker';
    }
  })();
}
