'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const MIN_TOKEN_LENGTH = 8;
const GENERATED_TOKEN_BYTES = 24;

function writeTokenFile(file, token) {
    fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
    fs.writeFileSync(file, `${token}\n`, { mode: 0o600 });
}

function readTokenFile(file) {
    try {
        return fs.readFileSync(file, 'utf8').trim();
    } catch (error) {
        if (error.code === 'ENOENT') return null;
        throw error;
    }
}

/**
 * 决定手机连接网关使用的 token，顺序：
 * 1. 配置文件 / 环境变量里明确指定的固定 token（会写回文件，状态页显示同一值）
 * 2. 已经存在且合法的 client-token.txt（**重启后保持不变**）
 * 3. 都没有时才随机生成一次并写入文件
 *
 * 这样网关重启不会改变连接地址，手机里保存的地址可以一直用。
 */
function ensureClientToken(config, logger) {
    const file = config.clientTokenFile;
    if (!config.requireClientToken) {
        config.clientToken = null;
        return config;
    }

    if (config.clientToken) {
        if (config.clientToken.length < MIN_TOKEN_LENGTH) {
            throw new Error(`clientToken 至少需要 ${MIN_TOKEN_LENGTH} 个字符`);
        }
        writeTokenFile(file, config.clientToken);
        return config;
    }

    const existing = readTokenFile(file);
    if (existing && existing.length >= MIN_TOKEN_LENGTH) {
        config.clientToken = existing;
        logger?.info?.('复用已保存的局域网客户端 token', { file });
        return config;
    }
    if (existing) {
        logger?.warn?.('已保存的局域网客户端 token 太短，将重新生成', { file });
    }

    const generated = crypto.randomBytes(GENERATED_TOKEN_BYTES).toString('base64url');
    writeTokenFile(file, generated);
    config.clientToken = generated;
    logger?.info?.('已生成新的局域网客户端 token 并保存', { file });
    return config;
}

module.exports = {
    ensureClientToken,
    readTokenFile,
    writeTokenFile,
    MIN_TOKEN_LENGTH,
};
