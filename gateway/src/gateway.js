'use strict';

const { EventEmitter } = require('node:events');
const crypto = require('node:crypto');
const WebSocket = require('ws');

const MAX_MESSAGE_BYTES = 64 * 1024;

/**
 * 手机侧 WebSocket 网关。手机只使用受控的 /gateway/* 协议，
 * 声核真实 token 和本机连接不会暴露给手机。
 */
class MobileGateway extends EventEmitter {
    constructor(config, logger, audcore) {
        super();
        this.config = config;
        this.logger = logger;
        this.audcore = audcore;
        this.server = new WebSocket.Server({
            host: config.listenHost,
            port: config.listenPort,
            maxPayload: MAX_MESSAGE_BYTES,
        });
        this.clients = new Map();
        this.channelSnapshot = null;
        this.soloSnapshot = null;
        this.coreChannelsSubscription = null;
        this.coreSoloSubscription = null;
        this.nextRequest = 0;
        this.server.on('listening', () => this.emit('listening'));
        this.server.on('error', (error) => this.emit('error', error));
        this.server.on('connection', (socket, request) => this.accept(socket, request));
        this.audcore.on('event', (message) => this.forwardEvent(message));
        this.audcore.on('authorized', () => {
            this.channelSnapshot = null;
            this.soloSnapshot = null;
            this.coreChannelsSubscription = null;
            this.coreSoloSubscription = null;
            this.subscribeCoreResources();
            this.syncAudcoreResources();
        });
        this.audcore.on('status', () => this.broadcastStatus());
    }

    /**
     * 网关自己也订阅通道与独奏状态：这样即使没有手机连接，
     * 状态页与 /api/status 也能看到当前通道列表。
     */
    async subscribeCoreResources() {
        try {
            const response = await this.audcore.request('GET', '/channels');
            if (response.status === 200) {
                this.channelSnapshot = response.data || [];
                if (!this.coreChannelsSubscription) {
                    this.coreChannelsSubscription = await this.audcore.subscribe('/channels', 10, 'gateway');
                }
            }
        } catch (error) {
            this.logger.warn('网关订阅通道状态失败', { error: error.message });
        }

        try {
            const response = await this.audcore.request('GET', '/solo');
            if (response.status === 200) {
                this.soloSnapshot = response.data;
                if (!this.coreSoloSubscription) {
                    this.coreSoloSubscription = await this.audcore.subscribe('/solo', 10, 'gateway');
                }
            }
        } catch (error) {
            this.logger.debug('网关订阅独奏状态失败（旧版声核可能不支持）', { error: error.message });
        }
    }

    accept(socket, request) {
        const id = crypto.randomUUID();
        const remote = request.socket.remoteAddress || 'unknown';
        if (!this.config.clientToken) {
            socket.close(1008, 'gateway client token is not configured');
            return;
        }
        const supplied = this.clientTokenFromRequest(request);
        if (this.config.requireClientToken && supplied !== this.config.clientToken) {
            this.logger.warn('拒绝未通过局域网 token 的手机连接', { remote });
            socket.close(1008, 'invalid gateway token');
            return;
        }
        const client = {
            id,
            socket,
            remote,
            connectedAt: new Date().toISOString(),
            received: 0,
            sent: 0,
            authorized: false,
            channelsSubscription: null,
            soloSubscription: null,
        };
        this.clients.set(id, client);
        socket.on('message', (raw) => this.handleClientMessage(client, raw));
        socket.on('close', () => this.removeClient(client));
        socket.on('error', (error) => this.logger.warn('手机网关连接错误', { remote, error: error.message }));
        this.logger.info('手机已连接网关', { remote, clientId: id });
        this.emit('status');
        socket.send(JSON.stringify({
            event: 'gateway-ready',
            gateway: this.gatewayInfo(),
        }));
        this.sendToClient(client, {
            event: 'gateway-status',
            data: this.status(),
        });
        if (this.config.requireClientToken) {
            this.authorizeClient(client);
        } else {
            client.authorized = true;
            this.authorizeClientResources(client);
        }
    }

    clientTokenFromRequest(request) {
        const url = new URL(request.url || '/', `http://${request.headers.host || 'localhost'}`);
        const query = url.searchParams.get('token');
        const header = request.headers['x-gateway-token'];
        return query || (Array.isArray(header) ? header[0] : header) || null;
    }

    async authorizeClient(client) {
        const challenge = crypto.randomBytes(16).toString('hex');
        this.sendToClient(client, { event: 'auth-required', data: { challenge } });
    }

    authorizeClientResources(client) {
        this.syncClientChannels(client);
        this.syncClientSolo(client);
    }

    async handleClientMessage(client, raw) {
        if (raw.length > MAX_MESSAGE_BYTES) {
            this.closeClient(client, 1009, 'message too large');
            return;
        }
        client.received += 1;
        let message;
        try {
            message = JSON.parse(raw.toString());
        } catch (error) {
            this.sendToClient(client, { event: 'gateway-error', error: { code: 'invalid_json', message: error.message } });
            return;
        }
        try {
            await this.routeClientMessage(client, message);
        } catch (error) {
            this.sendToClient(client, {
                status: error.status || 500,
                event: 'gateway-error',
                id: message.id,
                error: { code: error.code || 'gateway_error', message: error.message },
            });
        }
    }

    async routeClientMessage(client, message) {
        const id = message.id;
        const path = String(message.path || '');
        const method = String(message.method || 'GET').toUpperCase();
        // 旧版 App 在收到网关响应前会先发 POST /connections；先兼容该请求，避免 401。
        if (method === 'POST' && path === '/connections') {
            this.sendToClient(client, {
                id,
                status: 200,
                data: { instance_id: this.audcore.snapshot().instanceId || null },
            });
            return;
        }
        if (method === 'AUTH' && path === '/gateway/authorize') {
            await this.handleGatewayAuth(client, message);
            return;
        }
        if (!client.authorized) throw this.makeError('not_authorized', '请先通过局域网 token 认证', 401);
        if (method === 'GET' && path === '/gateway/status') {
            this.sendToClient(client, { id, status: 200, event: 'gateway-status', data: this.status() });
            return;
        }
        if (method === 'GET' && path === '/gateway/clients') {
            this.sendToClient(client, { id, status: 200, event: 'gateway-clients', data: this.clientList() });
            return;
        }
        if (method === 'GET' && path === '/gateway/network') {
            this.sendToClient(client, { id, status: 200, event: 'gateway-network', data: this.gatewayInfo() });
            return;
        }
        if (method === 'GET' && path === '/info') {
            this.sendToClient(client, { id, status: 200, data: { protocol: 'v1', gateway: true } });
            return;
        }
        if (method === 'GET' && path === '/status') {
            this.sendToClient(client, { id, status: 200, data: this.status() });
            return;
        }
        if (method === 'GET' && path === '/subscriptions') {
            this.sendToClient(client, { id, status: 200, data: [] });
            return;
        }
        if (method === 'DELETE' && path === '/subscriptions') {
            this.sendToClient(client, { id, status: 200, data: {} });
            return;
        }
        if (method === 'GET' && path === '/connections/current') {
            this.sendToClient(client, { id, status: 200, data: { connected: client.authorized } });
            return;
        }
        if (method === 'POST' && path === '/gateway/switch') {
            const channelId = String(message.body?.channel || '');
            if (!channelId) throw this.makeError('invalid_request', '缺少 channel');
            await this.switchChannel(client, id, channelId);
            return;
        }
        if (method === 'GET' && path === '/channels') {
            await this.sendChannels(client, id);
            return;
        }
        if (method === 'GET' && path === '/solo') {
            await this.sendSolo(client, id);
            return;
        }
        if (method === 'PATCH' && /^\/channels\/[^/]+$/.test(path)) {
            const channelId = decodeURIComponent(path.slice('/channels/'.length));
            const body = message.body || {};
            if (body.mute === undefined && body.gain_db === undefined) {
                throw this.makeError('invalid_request', '通道修改必须包含 mute 或 gain_db');
            }
            const response = await this.audcore.request('PATCH', `/channels/${encodeURIComponent(channelId)}`, body);
            if (response.status !== 200) throw this.audcore.toError(response);
            this.sendToClient(client, { id, status: 200, event: 'channel-updated', data: response.data });
            return;
        }
        if (method === 'PATCH' && path === '/solo') {
            const channelId = String(message.body?.channel || '');
            const response = await this.audcore.request('PATCH', '/solo', {
                channel: channelId,
                on: message.body?.on !== false,
            });
            if (response.status !== 200) throw this.audcore.toError(response);
            this.sendToClient(client, { id, status: 200, event: 'solo-updated', data: response.data });
            return;
        }
        throw this.makeError('not_supported', `局域网网关不支持 ${method} ${path}`);
    }

    async handleGatewayAuth(client, message) {
        if (!this.config.clientToken) throw this.makeError('gateway_not_ready', '网关 token 尚未生成');
        const supplied = String(message.body?.token || '');
        if (!supplied || !timingSafeEqual(supplied, this.config.clientToken)) {
            this.logger.warn('手机局域网 token 认证失败', { remote: client.remote });
            this.closeClient(client, 1008, 'invalid gateway token');
            return;
        }
        client.authorized = true;
        this.sendToClient(client, { id: message.id, status: 200, event: 'gateway-authorized', data: this.status() });
        this.logger.info('手机已完成局域网 token 认证', { remote: client.remote });
        this.authorizeClientResources(client);
        this.emit('status');
    }

    async sendChannels(client, id) {
        await this.syncClientChannels(client, id);
    }

    async sendSolo(client, id) {
        await this.syncClientSolo(client, id);
    }

    async syncAudcoreResources() {
        for (const client of this.clients.values()) {
            if (client.authorized) this.authorizeClientResources(client);
        }
    }

    async syncClientChannels(client, requestId) {
        if (!this.audcore.authorized) {
            // 声核尚未授权时也返回带状态的响应，避免旧版 App 等待超时。
            this.sendToClient(client, {
                id: requestId,
                status: 409,
                data: { error: { code: 'audcore_not_ready', message: '声核尚未授权网关连接' } },
            });
            return;
        }
        try {
            const response = await this.audcore.request('GET', '/channels');
            if (response.status !== 200) throw this.audcore.toError(response);
            this.channelSnapshot = response.data || [];
            this.sendToClient(client, {
                id: requestId,
                status: 200,
                event: 'channels',
                data: this.channelSnapshot,
            });
            if (!client.channelsSubscription) {
                client.channelsSubscription = await this.audcore.subscribe('/channels', 10, client.id);
            }
        } catch (error) {
            this.logger.warn('同步手机通道列表失败', { remote: client.remote, error: error.message });
        }
    }

    async syncClientSolo(client, requestId) {
        if (!this.audcore.authorized) {
            if (requestId !== undefined) {
                this.sendToClient(client, {
                    id: requestId,
                    status: 409,
                    data: { error: { code: 'audcore_not_ready', message: '声核尚未授权网关连接' } },
                });
            }
            return;
        }
        try {
            const response = await this.audcore.request('GET', '/solo');
            if (response.status === 200) {
                this.soloSnapshot = response.data;
                this.sendToClient(client, { id: requestId, status: 200, event: 'solo', data: this.soloSnapshot });
            } else if (response.status !== 404) {
                throw this.audcore.toError(response);
            }
            if (!client.soloSubscription && response.status === 200) {
                client.soloSubscription = await this.audcore.subscribe('/solo', 10, client.id);
            }
        } catch (error) {
            this.logger.warn('同步手机独奏状态失败', { remote: client.remote, error: error.message });
        }
    }

    async switchChannel(client, id, channelId) {
        const result = await this.audcore.switchToChannel(channelId);
        this.sendToClient(client, {
            id,
            status: 200,
            event: 'channel-switched',
            data: { channel: channelId, mode: result.mode },
        });
        await this.syncClientChannels(client);
        await this.syncClientSolo(client);
    }

    forwardEvent(message) {
        if (message.subscription_id) {
            const event = this.eventForSubscription(message);
            if (event) this.broadcastToAuthorized(event);
        }
    }

    eventForSubscription(message) {
        const data = message.data;
        const resource = message.resource || '';
        if (resource.startsWith('/channels')) {
            if (Array.isArray(data)) this.channelSnapshot = data;
            else if (data && data.id) {
                const list = this.channelSnapshot ? [...this.channelSnapshot] : [];
                const index = list.findIndex((channel) => channel.id === data.id);
                if (index >= 0) list[index] = { ...list[index], ...data };
                else list.push(data);
                this.channelSnapshot = list;
            }
            return { event: 'channels-update', data, resource, sequence: message.sequence };
        }
        if (resource.startsWith('/solo')) {
            this.soloSnapshot = data;
            return { event: 'solo-update', data, resource, sequence: message.sequence };
        }
        return null;
    }

    broadcastToAuthorized(event) {
        for (const client of this.clients.values()) {
            if (client.authorized) this.sendToClient(client, event);
        }
    }

    broadcastStatus() {
        const status = this.status();
        this.broadcastToAuthorized({ event: 'gateway-status', data: status });
        this.emit('status');
    }

    sendToClient(client, message) {
        if (client.socket.readyState !== WebSocket.OPEN) return;
        client.socket.send(JSON.stringify(message), (error) => {
            if (!error) {
                client.sent += 1;
                return;
            }
            this.logger.warn('发送手机消息失败', { remote: client.remote, error: error.message });
        });
    }

    closeClient(client, code, reason) {
        client.socket.close(code, reason);
        this.removeClient(client);
    }

    removeClient(client) {
        if (!this.clients.delete(client.id)) return;
        const subscriptions = [client.channelsSubscription, client.soloSubscription].filter(Boolean);
        client.channelsSubscription = null;
        client.soloSubscription = null;
        subscriptions.forEach((subscription) => this.audcore.unsubscribe(subscription));
        this.logger.info('手机已断开网关', { remote: client.remote, clientId: client.id });
        this.emit('status');
    }

    status() {
        return {
            audcore: this.audcore.snapshot(),
            mobile: {
                connected: this.clients.size,
                authorized: [...this.clients.values()].filter((client) => client.authorized).length,
                clients: this.clientList(),
            },
        };
    }

    /** 供状态页面展示的通道视图：按声核显示位置排序，字段保持扁平便于前端直接渲染。 */
    channelView() {
        const channels = Array.isArray(this.channelSnapshot) ? this.channelSnapshot : [];
        return [...channels]
            .sort((left, right) => displayOrder(left) - displayOrder(right))
            .map((channel) => ({
                id: channel.id,
                name: channel.name || '未命名通道',
                index: channel.index ?? null,
                visualIndex: channel.visual_index ?? channel.index ?? null,
                mute: Boolean(channel.mute),
                gainDb: typeof channel.gain_db === 'number' ? channel.gain_db : null,
                color: channel.color || null,
                alignRight: Boolean(channel.align_right),
            }));
    }

    /** 临时独奏状态：selected/safe/excluded 都是声核原生通道编号。 */
    soloView() {
        const solo = this.soloSnapshot && typeof this.soloSnapshot === 'object' ? this.soloSnapshot : {};
        const list = (value) => (Array.isArray(value) ? value.map(Number).filter((item) => Number.isInteger(item)) : []);
        return {
            selected: list(solo.selected),
            safe: list(solo.safe),
            excluded: list(solo.excluded),
        };
    }

    clientList() {
        return [...this.clients.values()].map((client) => ({
            id: client.id,
            remote: client.remote,
            connectedAt: client.connectedAt,
            authorized: client.authorized,
            received: client.received,
            sent: client.sent,
        }));
    }

    gatewayInfo() {
        return {
            host: this.config.listenHost,
            port: this.config.listenPort,
            requireClientToken: this.config.requireClientToken,
            audcoreEndpoint: this.config.audcoreUrl,
            addresses: require('./config').networkAddresses(),
        };
    }

    makeError(code, message, status = 500) {
        const error = new Error(message);
        error.code = code;
        error.status = status;
        return error;
    }

    async close() {
        for (const client of this.clients.values()) client.socket.close(1001, 'gateway shutdown');
        this.clients.clear();
        await new Promise((resolve) => this.server.close(resolve));
    }
}

/** 声核通道的显示顺序：优先 visual_index，缺失时回退到内部 index。 */
function displayOrder(channel) {
    const value = channel?.visual_index ?? channel?.index;
    return typeof value === 'number' ? value : Number.MAX_SAFE_INTEGER;
}

function timingSafeEqual(left, right) {
    const a = Buffer.from(String(left));
    const b = Buffer.from(String(right));
    return a.length === b.length && crypto.timingSafeEqual(a, b);
}

module.exports = { MobileGateway, MAX_MESSAGE_BYTES };
