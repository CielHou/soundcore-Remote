'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const args = process.argv.slice(2);
const installerPath = args[0];
if (!installerPath) {
  console.error('Usage: node run-single-file-setup.js <path-to-installer.exe>');
  process.exit(2);
}

const run = spawnSync(installerPath, [], { stdio: 'inherit', windowsHide: false });
console.log(JSON.stringify({
  installerPath,
  status: run.status,
  signal: run.signal,
  error: run.error ? String(run.error) : null,
  stdoutBytes: run.stdout ? run.stdout.length : 0,
  stderrBytes: run.stderr ? run.stderr.length : 0,
}, null, 2));
