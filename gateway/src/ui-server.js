'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { networkAddresses, preferredAddress, formatHost } = require('./config');

const PUBLIC_DIR = path.join(__dirname, '..', 'public');

// 打包成单文件 exe 时，页面被打进脚本（build/embed-assets.js 生成），
// 源码运行没有该文件，因此这里容错加载。
let embeddedAssets = null;
try {
    embeddedAssets = require('./embedded-assets.js');
} catch (error) {
    embeddedAssets = null;
}

function createUiServer(config, logger, gateway) {
    const server = http.createServer((request, response) => {
        if (request.method !== 'GET') {
            sendJson(response, 405, { error: 'method_not_allowed' });
            return;
        }
        const url = new URL(request.url || '/', `http://${request.headers.host || 'localhost'}`);
        const network = networkAddresses();
        const connectionUrls = network.map(({ address, name }) => ({
            name,
            address,
            url: `ws://${formatHost(address)}:${config.listenPort}/api/v1?token=${encodeURIComponent(config.clientToken || '')}`,
        }));
        if (url.pathname === '/api/status') {
            sendJson(response, 200, {
                ...gateway.status(),
                channels: gateway.channelView(),
                solo: gateway.soloView(),
                network,
                gateway: {
                    host: config.listenHost,
                    port: config.listenPort,
                    clientToken: config.requireClientToken ? 'configured' : 'disabled',
                    token: config.requireClientToken ? config.clientToken : null,
                    preferredAddress: preferredAddress(),
                    connectionUrls,
                },
                recentLogs: logger.recent(config.logMaxEntries),
            });
            return;
        }
        if (url.pathname === '/api/config') {
            sendJson(response, 200, {
                audcoreUrl: config.audcoreUrl,
                listenHost: config.listenHost,
                listenPort: config.listenPort,
                requireClientToken: config.requireClientToken,
            });
            return;
        }
        const relative = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
        const target = path.resolve(PUBLIC_DIR, relative);
        if (!target.startsWith(PUBLIC_DIR)) {
            sendJson(response, 404, { error: 'not_found' });
            return;
        }
        fs.readFile(target, (error, data) => {
            if (error) {
                sendEmbedded(response, relative);
                return;
            }
            sendStatic(response, target, data);
        });
    });
    return server;
}

function contentTypeFor(name) {
    return name.endsWith('.html') ? 'text/html; charset=utf-8'
        : name.endsWith('.css') ? 'text/css; charset=utf-8'
            : name.endsWith('.js') ? 'application/javascript; charset=utf-8'
                : 'application/octet-stream';
}

function sendStatic(response, target, data) {
    response.writeHead(200, { 'Content-Type': contentTypeFor(target), 'Cache-Control': 'no-store' });
    response.end(data);
}

function sendEmbedded(response, relative) {
    const key = relative.replace(/\\/g, '/');
    const text = embeddedAssets && embeddedAssets[key];
    if (typeof text !== 'string') {
        sendJson(response, 404, { error: 'not_found' });
        return;
    }
    response.writeHead(200, { 'Content-Type': contentTypeFor(key), 'Cache-Control': 'no-store' });
    response.end(text);
}

function sendJson(response, status, data) {
    const body = JSON.stringify(data, null, 2);
    response.writeHead(status, {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store',
    });
    response.end(body);
}

module.exports = { createUiServer };
