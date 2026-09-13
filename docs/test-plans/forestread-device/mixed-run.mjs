// Explicit new disposable-fixture mode; never changes the ordinary HTTPS enrollment suite.
import {run,options} from './https-run.mjs';
try {await run({...options(process.argv.slice(2)),mixed:true});}
catch(error) {console.error(error.message);process.exitCode=1;}
