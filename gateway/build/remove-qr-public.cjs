'use strict';

const fs = require('node:fs');
const path = require('node:path');

const publicDir = path.join(__dirname, '..', 'public');
for (const name of ['qr.js', 'qr.bundle.js']) {
  const file = path.join(publicDir, name);
  if (fs.existsSync(file)) {
    fs.unlinkSync(file);
    console.log(`已删除 ${file}`);
  }
}
