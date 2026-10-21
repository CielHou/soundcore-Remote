'use strict';

const { EventEmitter } = require('node:events');
const { randomUUID } = require('node:crypto');
const WebSocket = require('ws');

const READY_STATE_LABELS = ['connecting', 'open', 'closing', 'closed'];

/**
 * 单个声核 WebSocket 连接：负责授权、请求配对、订阅转发和自动重连。
 * 手机侧网关只通过此对象发送受控请求，不直接接触声核连接。
 */
class AudcoreConnection extends EventEmitter {
    constructor(config, logger) {
        super();
        this.config = config;
        this.logger = logger;
        this.socket = null;
        this.status = 'stopped';
        this.lastError = null;
        this.authorized = false;
        this.authorizationPending = false;
        this.instanceId = null;
        this.reconnectAttempts = 0;
        this.reconnectTimer = null;
        this.requestTimer = null;
        this.nextRequestId = 0;
        this.pending = new Map();
        this.subscriptions = new Map();
        this.started = false;
        this.requestCount = 0;
        this.messageCount = 0;
        this.lastMessageAt = null;
    }

    start() {
        if (this.started) return;
        this.started = true;
        this.logger.info('网关准备连接声核本机 API', { url: this.config.audcoreUrl });
        this.openSocket();
    }

    stop() {
        this.started = false;
        this.clearReconnectTimer();
        this.authorized = false;
        this.authorizationPending = false;
        const socket = this.socket;
        this.socket = null;
        this.rejectPending(new Error('网关已停止连接声核'));
        if (!socket) return;
        this.setStatus('closing');
        try {
            socket.close(1000, 'gateway stopped');
        } catch {
            socket.terminate();
        }
    }

    openSocket() {
        if (!this.started) return;
        this.clearReconnectTimer();
        this.setStatus(this.reconnectAttempts ? 'reconnecting' : 'connecting');
        this.logger.info('正在连接声核本机 API', {
            attempt: this.reconnectAttempts + 1,
            url: this.config.audcoreUrl,
        });
        const socket = new WebSocket(this.config.audcoreUrl, {
            handshakeTimeout: this.config.connectTimeoutMs,
        });
        this.socket = socket;
        socket.on('open', () => {
            if (socket !== this.socket) return;
            this.setStatus('open');
            this.reconnectAttempts = 0;
            this.lastError = null;
            this.logger.info('声核 WebSocket 已建立');
            this.sendAuthorizationRequest();
        });
        socket.on('message', (raw) => {
            if (socket !== this.socket) return;
            this.messageCount += 1;
            this.lastMessageAt = new Date().toISOString();
            try {
                this.handleMessage(JSON.parse(raw.toString()));
            } catch (error) {
                this.logger.warn('无法解析声核消息', { error: error.message });
            }
        });
        socket.on('close', (code, reason) => {
            if (socket !== this.socket) return;
            this.socket = null;
            this.authorized = false;
            this.authorizationPending = false;
            this.rejectPending(new Error(`声核连接关闭：${reason || code}`));
            this.setStatus('closed');
            if (!this.started) return;
            this.lastError = `声核连接关闭（code=${code}）`;
            this.logger.warn('声核连接已关闭', { code, reason: reason?.toString() || '' });
            this.scheduleReconnect();
        });
        socket.on('error', (error) => {
            this.lastError = error.message;
            this.logger.error('声核 WebSocket 错误', { error: error.message });
        });
    }

    sendAuthorizationRequest() {
        const body = { app_name: this.config.appName };
        // 声核本机连接由电脑上的网关代理，token 只保存在电脑本地文件中。
        const token = this.readToken();
        if (token) body.token = token;
        this.request('POST', '/connections', body)
            .then((response) => {
                const status = response?.status;
                if (status === 202) {
                    this.authorizationPending = true;
                    this.authorized = false;
                    this.logger.info('等待声核客户端允许网关连接');
                    this.emit('status');
                    return;
                }
                if (status === 200) {
                    this.handleAuthorized(response.data);
                    return;
                }
                throw this.toError(response);
            })
            .catch((error) => {
                this.lastError = error.message;
                this.logger.error('建立声核授权连接失败', { error: error.message });
                this.emit('status');
            });
    }

    handleAuthorized(data) {
        this.authorizationPending = false;
        this.authorized = true;
        this.instanceId = data?.instance_id || null;
        if (data?.token) this.writeToken(data.token);
        this.logger.info('网关已获得声核授权', { instanceId: this.instanceId });
        this.emit('status');
        this.emit('authorized');
    }

    handleMessage(message) {
        const id = message.id;
        if (id && this.pending.has(id)) {
            const pending = this.pending.get(id);
            this.pending.delete(id);
            clearTimeout(pending.timer);
            pending.resolve(message);
            return;
        }
        if (message.event === 'authorized') {
            this.handleAuthorized(message.data);
            return;
        }
        if (message.event === 'authorization-denied') {
            this.authorized = false;
            this.authorizationPending = false;
            this.lastError = '声核拒绝了网关连接';
            this.logger.warn('声核拒绝网关连接');
            this.emit('status');
            return;
        }
        if (message.event) {
            this.emit('event', message);
            return;
        }
        this.emit('message', message);
    }

    request(method, path, body) {
        return new Promise((resolve, reject) => {
            const socket = this.socket;
            if (!socket || socket.readyState !== WebSocket.OPEN) {
                reject(new Error('网关与声核的连接不可用'));
                return;
            }
            const id = `${++this.nextRequestId}-${randomUUID()}`;
            const payload = { id, method, path };
            if (body !== undefined) payload.body = body;
            const timer = setTimeout(() => {
                this.pending.delete(id);
                reject(new Error(`声核请求超时：${method} ${path}`));
            }, 10000);
            this.pending.set(id, { resolve, reject, timer });
            this.requestCount += 1;
            socket.send(JSON.stringify(payload), (error) => {
                if (!error) return;
                this.pending.delete(id);
                clearTimeout(timer);
                reject(error);
            });
        });
    }

    async subscribe(resource, frequencyHz = 10, owner = 'gateway') {
        const response = await this.request('POST', '/subscriptions', {
            resource,
            frequency_hz: frequencyHz,
        });
        if (response.status !== 201) throw this.toError(response);
        const subscriptionId = response.data?.subscription_id;
        if (!subscriptionId) throw new Error('声核未返回 subscription_id');
        this.subscriptions.set(subscriptionId, { resource, owner });
        return subscriptionId;
    }

    async unsubscribe(subscriptionId) {
        if (!this.subscriptions.has(subscriptionId)) return;
        this.subscriptions.delete(subscriptionId);
        if (this.socket?.readyState !== WebSocket.OPEN) return;
        await this.request('DELETE', `/subscriptions/${subscriptionId}`).catch((error) => {
            this.logger.debug('取消声核订阅失败', { subscriptionId, error: error.message });
        });
    }

    async switchToChannel(channelId) {
        const soloResponse = await this.request('PATCH', '/solo', {
            channel: channelId,
            on: true,
        });
        if (soloResponse.status === 200) return { mode: 'solo', response: soloResponse };
        if (soloResponse.status === 404) {
            const channelsResponse = await this.request('GET', '/channels');
            if (channelsResponse.status !== 200) throw this.toError(channelsResponse);
            const channels = channelsResponse.data || [];
            const results = await Promise.all(
                channels.map((channel) =>
                    this.request('PATCH', `/channels/${encodeURIComponent(channel.id)}`, {
                        mute: channel.id !== channelId,
                    }),
                ),
            );
            const failure = results.find((response) => response.status !== 200);
            if (failure) throw this.toError(failure);
            return { mode: 'mute-fallback', response: results };
        }
        throw this.toError(soloResponse);
    }

    toError(response) {
        const error = response?.data?.error || {};
        const result = new Error(error.message || `声核返回状态 ${response?.status}`);
        result.status = response?.status;
        result.code = error.code;
        return result;
    }

    rejectPending(error) {
        for (const pending of this.pending.values()) {
            clearTimeout(pending.timer);
            pending.reject(error);
        }
        this.pending.clear();
    }

    scheduleReconnect() {
        if (!this.started || this.reconnectTimer) return;
        const delay = Math.min(
            this.config.reconnectDelayMs * 2 ** this.reconnectAttempts,
            this.config.maxReconnectDelayMs,
        );
        this.reconnectAttempts += 1;
        this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            this.openSocket();
        }, delay);
        this.emit('status');
    }

    clearReconnectTimer() {
        if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
        this.reconnectTimer = null;
    }

    setStatus(status) {
        this.status = status;
        this.emit('status');
    }

    readToken() {
        try {
            return require('node:fs').readFileSync(this.config.tokenFile, 'utf8').trim() || null;
        } catch {
            return null;
        }
    }

    writeToken(token) {
        try {
            require('node:fs').mkdirSync(require('node:path').dirname(this.config.tokenFile), {
                recursive: true,
            });
            require('node:fs').writeFileSync(this.config.tokenFile, `${token}\n`, {
                mode: 0o600,
            });
        } catch (error) {
            this.logger.warn('无法保存声核 token', { error: error.message });
        }
    }

    snapshot() {
        return {
            status: this.status,
            authorized: this.authorized,
            authorizationPending: this.authorizationPending,
            endpoint: this.config.audcoreUrl,
            instanceId: this.instanceId,
            lastError: this.lastError,
            reconnectAttempts: this.reconnectAttempts,
            socketState: this.socket ? READY_STATE_LABELS[this.socket.readyState] : 'closed',
            subscriptions: this.subscriptions.size,
            requestCount: this.requestCount,
            messageCount: this.messageCount,
            lastMessageAt: this.lastMessageAt,
        };
    }
}

module.exports = { AudcoreConnection };
