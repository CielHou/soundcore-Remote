'use strict';

// 把 public/ 下的静态页面打成 JS 模块，供 esbuild 内联进单文件 exe。
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const publicDir = path.join(root, 'public');
const outFile = path.join(root, 'src', 'embedded-assets.js');

const files = fs.readdirSync(publicDir).filter((name) => fs.statSync(path.join(publicDir, name)).isFile());
const entries = files.map((name) => {
    const text = fs.readFileSync(path.join(publicDir, name), 'utf8');
    return `  ${JSON.stringify(name)}: ${JSON.stringify(text)}`;
});

const content = `'use strict';\n\n// 由 build/embed-assets.js 生成，请勿手工编辑。\nmodule.exports = {\n${entries.join(',\n')}\n};\n`;
fs.writeFileSync(outFile, content, 'utf8');
console.log(`已生成 ${outFile}（${files.length} 个文件）`);
