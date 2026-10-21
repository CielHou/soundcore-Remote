'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

/** 判断是否运行在打包后的单文件 exe 里（Node SEA）。 */
function isPackaged() {
    try {
        const sea = require('node:sea');
        if (sea && typeof sea.isSea === 'function' && sea.isSea()) return true;
    } catch (error) {
        // 老版本 Node 没有 node:sea，按源码运行处理
    }
    return Boolean(process.pkg);
}

// 打包后 __dirname 指向临时目录，配置与状态文件要跟随 exe 所在目录。
const PROJECT_ROOT = isPackaged() ? path.dirname(process.execPath) : path.resolve(__dirname, '..');
const STATE_DIR = path.join(PROJECT_ROOT, '.state');
const DEFAULT_CONFIG_FILE = path.join(PROJECT_ROOT, 'gateway.config.json');

function readBoolean(value, fallback) {
    if (value === undefined || value === '') return fallback;
    return ['1', 'true', 'yes', 'on'].includes(String(value).toLowerCase());
}

function readNumber(value, fallback) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : fallback;
}

/**
 * 读取可选的 gateway.config.json（或 AUDCORE_GATEWAY_CONFIG 指定的文件）。
 * 用来固定网关 token / 端口，避免每次重启都重新配置。
 */
function readConfigFile(env = process.env) {
    const file = env.AUDCORE_GATEWAY_CONFIG?.trim() || DEFAULT_CONFIG_FILE;
    try {
        const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
            throw new Error('内容必须是 JSON 对象');
        }
        return parsed;
    } catch (error) {
        if (error.code === 'ENOENT') return {};
        throw new Error(`读取配置文件 ${file} 失败：${error.message}`);
    }
}

/** 配置文件里支持的字段与对应环境变量。环境变量优先。 */
const FILE_KEYS = {
    AUDCORE_GATEWAY_PORT: 'listenPort',
    AUDCORE_UI_PORT: 'uiPort',
    AUDCORE_API_PORT: 'audcorePort',
    AUDCORE_API_HOST: 'audcoreHost',
    AUDCORE_API_URL: 'audcoreUrl',
    AUDCORE_GATEWAY_HOST: 'listenHost',
    AUDCORE_APP_NAME: 'appName',
    AUDCORE_GATEWAY_REQUIRE_CLIENT_TOKEN: 'requireClientToken',
    AUDCORE_GATEWAY_CLIENT_TOKEN: 'clientToken',
    AUDCORE_GATEWAY_STATE_FILE: 'stateFile',
};

function mergeWithConfigFile(env) {
    const fileConfig = readConfigFile(env);
    const merged = { ...env };
    for (const [envKey, fileKey] of Object.entries(FILE_KEYS)) {
        const current = merged[envKey];
        if (current !== undefined && String(current).trim() !== '') continue;
        const fromFile = fileConfig[fileKey];
        if (fromFile === undefined || fromFile === null || String(fromFile).trim() === '') continue;
        merged[envKey] = String(fromFile);
    }
    return merged;
}

function loadConfig(env = process.env) {
    const source = mergeWithConfigFile(env);
    const listenPort = readNumber(source.AUDCORE_GATEWAY_PORT, 18533);
    const uiPort = readNumber(source.AUDCORE_UI_PORT, 18534);
    const audcorePort = readNumber(source.AUDCORE_API_PORT, 38533);
    const listenHost = source.AUDCORE_GATEWAY_HOST?.trim() || '0.0.0.0';
    const audcoreHost = source.AUDCORE_API_HOST?.trim() || '127.0.0.1';

    if (
        !Number.isInteger(listenPort) ||
        !Number.isInteger(uiPort) ||
        !Number.isInteger(audcorePort) ||
        listenPort < 1 || listenPort > 65535 ||
        uiPort < 1 || uiPort > 65535 ||
        audcorePort < 1 || audcorePort > 65535
    ) {
        throw new Error('端口必须是 1 到 65535 之间的整数');
    }

    const stateFile = path.resolve(
        source.AUDCORE_GATEWAY_STATE_FILE || path.join(STATE_DIR, 'gateway-state.json'),
    );

    return {
        listenHost,
        listenPort,
        uiPort,
        audcoreHost,
        audcorePort,
        audcoreUrl:
            source.AUDCORE_API_URL ||
            `ws://${audcoreHost}:${audcorePort}/api/v1`,
        appName: source.AUDCORE_APP_NAME?.trim() || '声核局域网网关',
        requireClientToken: readBoolean(source.AUDCORE_GATEWAY_REQUIRE_CLIENT_TOKEN, true),
        clientToken: source.AUDCORE_GATEWAY_CLIENT_TOKEN?.trim() || null,
        connectTimeoutMs: readNumber(source.AUDCORE_CONNECT_TIMEOUT_MS, 15000),
        reconnectDelayMs: readNumber(source.AUDCORE_RECONNECT_DELAY_MS, 1500),
        maxReconnectDelayMs: readNumber(source.AUDCORE_MAX_RECONNECT_DELAY_MS, 15000),
        logMaxEntries: readNumber(source.AUDCORE_LOG_MAX_ENTRIES, 100),
        stateFile,
        tokenFile: path.join(path.dirname(stateFile), 'audcore-token.txt'),
        clientTokenFile: path.join(path.dirname(stateFile), 'client-token.txt'),
    };
}

function ensureStateDir(config) {
    fs.mkdirSync(path.dirname(config.stateFile), { recursive: true });
}

function isHardwareAdapter(name, address) {
    if (!address || address.startsWith('127.') || address.startsWith('169.254.')) return false;
    const normalized = String(name || '').toLowerCase();
    return !/(vmware|vmnet|virtual|vethernet|hyper-v|wsl|vbox|virtualbox|parallels|tap-windows|tap0|tun|tailscale|zerotier|docker|br-|loopback|bluetooth|wwan|cellular)/.test(normalized);
}

function networkAddresses() {
    const interfaces = os.networkInterfaces();
    const result = [];
    for (const [name, entries] of Object.entries(interfaces)) {
        for (const entry of entries || []) {
            if (entry.internal || entry.family !== 'IPv4') continue;
            if (!isHardwareAdapter(name, entry.address)) continue;
            result.push({ name, address: entry.address });
        }
    }
    return result.sort((a, b) => a.address.localeCompare(b.address));
}

function formatHost(address) {
    return address.includes(':') ? `[${address}]` : address;
}

/**
 * 探测本机真正用来访问局域网的 IPv4 地址。
 * 做法：UDP connect 到公网 DNS 让系统自己选路，再读本地绑定地址（不发实际数据包）。
 * 比按地址段猜可靠：能自动跳过 VMware / Hyper-V / 网络共享这些虚拟网卡。
 * 探测是异步的，结果缓存在模块里，通过 preferredAddress() 同步读取。
 */
let cachedPreferredAddress = null;
let preferredProbe = null;

function probePreferredAddress() {
    if (preferredProbe) return preferredProbe;
    preferredProbe = new Promise((resolve) => {
        const dgram = require('node:dgram');
        const socket = dgram.createSocket('udp4');
        let settled = false;
        const finish = (value) => {
            if (settled) return;
            settled = true;
            cachedPreferredAddress = value;
            try {
                socket.close();
            } catch (error) {
                // 忽略关闭错误
            }
            resolve(value);
        };
        socket.once('error', () => finish(null));
        try {
            socket.connect(53, '223.5.5.5', () => {
                let address = null;
                try {
                    address = socket.address()?.address || null;
                } catch (error) {
                    address = null;
                }
                finish(address && !address.startsWith('127.') ? address : null);
            });
        } catch (error) {
            finish(null);
        }
        setTimeout(() => finish(null), 800).unref?.();
    });
    return preferredProbe;
}

/** 同步读取已探测到的主用局域网地址（尚未探测完成时返回 null）。 */
function preferredAddress() {
    return cachedPreferredAddress;
}

module.exports = {
    loadConfig,
    readConfigFile,
    ensureStateDir,
    networkAddresses,
    isHardwareAdapter,
    preferredAddress,
    probePreferredAddress,
    formatHost,
    PROJECT_ROOT,
    STATE_DIR,
    DEFAULT_CONFIG_FILE,
};
