'use strict';

const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const publicDir = path.join(root, 'public');
const output = path.join(root, 'src', 'embedded-assets.js');
const names = fs.readdirSync(publicDir)
  .filter((name) => fs.statSync(path.join(publicDir, name)).isFile())
  .sort();
const entries = names.map((name) => {
  const content = fs.readFileSync(path.join(publicDir, name), 'utf8');
  return `  ${JSON.stringify(name)}: ${JSON.stringify(content)}`;
});
fs.writeFileSync(output, `'use strict';\n\n// 由 build/embed-assets.js 生成，请勿手工编辑。\nmodule.exports = {\n${entries.join(',\n')}\n};\n`, 'utf8');
console.log(`已生成 ${output}（${names.length} 个文件：${names.join(', ')}）`);
