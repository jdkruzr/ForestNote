// Actual gated WebView consuming the already-downloaded shared-library book.
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
import {run,options} from './https-run.mjs';
try {
    const args=process.argv.slice(2);const book=args.pop();assert.equal(args.pop(),'--book');
    await run({...options(args),mixed:true,book:resolve(book),foreground:true,library:true,renderer:true});
} catch(error) {console.error(error.message);process.exitCode=1;}
