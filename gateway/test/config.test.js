'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { isHardwareAdapter, loadConfig, formatHost, preferredAddress, probePreferredAddress } = require('../src/config');

test('loadConfig defaults to local AudCore and LAN gateway ports', () => {
    const config = loadConfig({});
    assert.equal(config.audcoreUrl, 'ws://127.0.0.1:38533/api/v1');
    assert.equal(config.listenPort, 18533);
    assert.equal(config.uiPort, 18534);
    assert.equal(config.requireClientToken, true);
});

test('isHardwareAdapter filters common virtual and link-local adapters', () => {
    assert.equal(isHardwareAdapter('以太网', '192.168.1.20'), true);
    assert.equal(isHardwareAdapter('WLAN', '192.168.31.20'), true);
    assert.equal(isHardwareAdapter('VMware Network Adapter VMnet1', '192.168.80.1'), false);
    assert.equal(isHardwareAdapter('vEthernet (Default Switch)', '172.18.0.1'), false);
    assert.equal(isHardwareAdapter('Loopback Pseudo-Interface 1', '169.254.1.1'), false);
    assert.equal(isHardwareAdapter('Tailscale', '100.64.0.1'), false);
});

test('loadConfig accepts explicit local endpoints', () => {
    const config = loadConfig({
        AUDCORE_API_URL: 'ws://127.0.0.1:40000/api/v1',
        AUDCORE_GATEWAY_PORT: '19000',
        AUDCORE_GATEWAY_REQUIRE_CLIENT_TOKEN: 'false',
    });
    assert.equal(config.audcoreUrl, 'ws://127.0.0.1:40000/api/v1');
    assert.equal(config.listenPort, 19000);
    assert.equal(config.requireClientToken, false);
});

test('formatHost supports IPv4 and bracketed IPv6 hosts', () => {
    assert.equal(formatHost('192.168.31.15'), '192.168.31.15');
    assert.equal(formatHost('::1'), '[::1]');
});
test('probePreferredAddress finds the LAN IPv4 address used for routing', async () => {
    const address = await probePreferredAddress();
    if (address === null) return;
    assert.match(address, /^\d+\.\d+\.\d+\.\d+$/);
    assert.ok(!address.startsWith('127.'), '不应返回回环地址');
    assert.equal(preferredAddress(), address, '同步读取应返回缓存结果');
});
