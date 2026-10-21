'use strict';

const { spawnSync } = require('node:child_process');

const exe = process.argv[2];
if (!exe) {
  console.error('Usage: node run-installer.js <exe>');
  process.exit(2);
}

const result = spawnSync(exe, [], {
  windowsHide: false,
  stdio: 'inherit',
  timeout: 20000,
});

console.log(JSON.stringify({
  exe,
  status: result.status,
  signal: result.signal,
  error: result.error ? result.error.message : null,
}, null, 2));
