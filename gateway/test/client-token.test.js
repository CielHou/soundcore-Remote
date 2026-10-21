'use strict';

const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { ensureClientToken } = require('../src/client-token');

function tempConfig(overrides = {}) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'gateway-token-'));
    return {
        dir,
        config: {
            requireClientToken: true,
            clientToken: null,
            clientTokenFile: path.join(dir, 'client-token.txt'),
            ...overrides,
        },
    };
}

test('首次运行生成 token 并写入文件', () => {
    const { config } = tempConfig();
    ensureClientToken(config);
    assert.ok(config.clientToken.length >= 8);
    assert.strictEqual(fs.readFileSync(config.clientTokenFile, 'utf8').trim(), config.clientToken);
});

test('重启后复用同一个 token', () => {
    const { config } = tempConfig();
    ensureClientToken(config);
    const first = config.clientToken;

    const restarted = { ...config, clientToken: null };
    ensureClientToken(restarted);
    assert.strictEqual(restarted.clientToken, first);
});

test('配置文件里的固定 token 优先，并写回文件', () => {
    const { config } = tempConfig({ clientToken: 'fixed-token-2026' });
    ensureClientToken(config);
    assert.strictEqual(config.clientToken, 'fixed-token-2026');
    assert.strictEqual(
        fs.readFileSync(config.clientTokenFile, 'utf8').trim(),
        'fixed-token-2026',
    );
});

test('关闭 token 校验时不生成 token', () => {
    const { config } = tempConfig({ requireClientToken: false });
    ensureClientToken(config);
    assert.strictEqual(config.clientToken, null);
    assert.strictEqual(fs.existsSync(config.clientTokenFile), false);
});

test('过短的固定 token 会被拒绝', () => {
    const { config } = tempConfig({ clientToken: 'short' });
    assert.throws(() => ensureClientToken(config), /至少需要/);
});
