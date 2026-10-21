'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { loadConfig, ensureStateDir, probePreferredAddress } = require('./config');
const { ensureClientToken } = require('./client-token');
const { Logger } = require('./logger');
const { AudcoreConnection } = require('./audcore-connection');
const { MobileGateway } = require('./gateway');
const { createUiServer } = require('./ui-server');

async function main() {
    let config = loadConfig();
    ensureStateDir(config);
    // 异步探测主用局域网地址，供状态页 / 控制面板默认选中正确的网卡
    probePreferredAddress();
    const logger = new Logger(config.logMaxEntries);
    config = ensureClientToken(config, logger);
    const audcore = new AudcoreConnection(config, logger);
    const gateway = new MobileGateway(config, logger, audcore);
    const uiServer = createUiServer(config, logger, gateway);

    gateway.on('listening', () => {
        logger.info('手机局域网 WebSocket 网关已启动', {
            host: config.listenHost,
            port: config.listenPort,
            requireClientToken: config.requireClientToken,
        });
        if (config.clientTokenFile) {
            logger.info('局域网客户端 token 已就绪（重启不变）', { file: config.clientTokenFile });
        }
    });
    gateway.on('error', (error) => {
        logger.error('手机网关启动失败', { error: error.message });
        // 端口被占用等致命错误：退出，让托盘外壳在端口释放后自动重启
        setTimeout(() => process.exit(2), 50);
    });
    uiServer.on('error', (error) => {
        logger.error('状态页面启动失败', { error: error.message });
        setTimeout(() => process.exit(2), 50);
    });

    uiServer.listen(config.uiPort, config.listenHost, () => {
        logger.info('网关状态页面已启动', {
            url: `http://127.0.0.1:${config.uiPort}/`,
        });
    });
    audcore.start();

    const shutdown = async () => {
        logger.info('网关正在停止');
        audcore.stop();
        await gateway.close();
        await new Promise((resolve) => uiServer.close(resolve));
        process.exit(0);
    };
    process.on('SIGINT', shutdown);
    process.on('SIGTERM', shutdown);
}

main().catch((error) => {
    console.error(`网关启动失败：${error.stack || error.message}`);
    process.exit(1);
});
