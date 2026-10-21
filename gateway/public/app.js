'use strict';

const stateElement = (id) => document.getElementById(id);
const lastPayload = { value: null };

function formatTime(value) {
    if (!value) return '—';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleTimeString('zh-CN', { hour12: false });
}

function formatCount(value) {
    return Number(value || 0).toLocaleString('zh-CN');
}

function setText(id, value) {
    stateElement(id).textContent = value;
}

function setDot(id, status) {
    const element = stateElement(id);
    element.className = `status-dot ${status}`;
}

async function loadStatus() {
    try {
        const response = await fetch('/api/status', { cache: 'no-store' });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const payload = await response.json();
        lastPayload.value = payload;
        render(payload);
        setText('refreshState', '状态每 2 秒刷新');
    } catch (error) {
        setText('refreshState', `无法读取状态：${error.message}`);
        setDot('audcoreDot', 'bad');
        setDot('mobileDot', 'bad');
    }
}

function render(payload) {
    const audcore = payload.audcore || {};
    const mobile = payload.mobile || {};
    const gateway = payload.gateway || {};
    const audcoreOnline = audcore.status === 'open' && audcore.authorized;
    const audcoreWarning = audcore.status === 'open' || audcore.status === 'connecting' || audcore.status === 'reconnecting';
    setDot('audcoreDot', audcoreOnline ? 'good' : audcoreWarning ? 'warn' : 'bad');
    setDot('mobileDot', Number(mobile.connected) > 0 ? 'good' : 'warn');
    stateElement('audcoreMeter').style.width = audcoreOnline ? '100%' : audcoreWarning ? '55%' : '12%';
    stateElement('mobileMeter').style.width = Number(mobile.connected) > 0 ? '100%' : '8%';
    setText('audcoreStatus', audcore.status === 'open'
        ? (audcore.authorized ? '已连接并授权' : '已连接，等待声核授权')
        : statusLabel(audcore.status));
    setText('mobileStatus', Number(mobile.connected) > 0 ? '有手机正在连接' : '等待手机连接');
    setText('audcoreEndpoint', audcore.endpoint || '—');
    setText('audcoreAuthorized', audcore.authorized ? '已授权' : audcore.authorizationPending ? '等待允许' : '未授权');
    setText('audcoreMessages', formatCount(audcore.messageCount));
    setText('audcoreLastMessage', formatTime(audcore.lastMessageAt));
    setText('mobileClients', formatCount(mobile.connected));
    setText('mobileAuthorized', formatCount(mobile.authorized));
    setText('gatewayPort', payload.gateway ? payload.gateway.port : '—');
    const addresses = (payload.network || []).map((item) => item.address).join(', ');
    setText('networkAddresses', addresses || '—');
    setText('clientCount', formatCount(mobile.connected));
    setText('diagAudcore', statusLabel(audcore.status));
    setText('diagAuthorization', audcore.authorized ? '已授权' : audcore.authorizationPending ? '等待声核允许' : '未授权');
    setText('diagSubscriptions', formatCount(audcore.subscriptions));
    setText('diagReconnects', formatCount(audcore.reconnectAttempts));
    setText('diagError', audcore.lastError || '无');
    renderClients(mobile.clients || []);
    renderChannels(payload.channels || [], payload.solo || {});
    renderLogs(payload.recentLogs || []);
    renderDeploy(gateway, payload.network || []);
}

function formatGain(value) {
    if (typeof value !== 'number' || Number.isNaN(value)) return '—';
    const rounded = Math.round(value * 10) / 10;
    return `${rounded > 0 ? '+' : ''}${rounded.toFixed(1)} dB`;
}

function renderChannels(channels, solo) {
    const list = Array.isArray(channels) ? channels : [];
    const selected = new Set((solo.selected || []).map(Number));
    const safe = new Set((solo.safe || []).map(Number));
    const live = list.filter((channel) => !channel.mute && !selected.has(Number(channel.index))).length;
    const muted = list.filter((channel) => channel.mute).length;

    setText('channelCount', list.length ? `${formatCount(list.length)} 个通道` : '0 个通道');
    setText('soloCount', selected.size ? `独奏 ${selected.size} 个` : '独奏 关闭');
    const summary = stateElement('channelSummary');
    if (!list.length) {
        summary.innerHTML = '<span class="empty-inline">尚未收到声核通道数据，请确认声核已打开并已授权本机连接。</span>';
    } else {
        summary.innerHTML = [
            `<span class="badge live">发声 ${formatCount(live)}</span>`,
            `<span class="badge mute">静音 ${formatCount(muted)}</span>`,
            selected.size ? `<span class="badge solo">独奏 ${formatCount(selected.size)}</span>` : '',
            safe.size ? `<span class="badge safe">安全 ${formatCount(safe.size)}</span>` : '',
        ].filter(Boolean).join('');
    }

    const body = stateElement('channelRows');
    if (!list.length) {
        body.innerHTML = '<tr><td colspan="6" class="empty">暂无通道数据</td></tr>';
        return;
    }
    body.innerHTML = list.map((channel) => {
        const index = Number(channel.index);
        const isSolo = selected.has(index);
        const isSafe = safe.has(index);
        const swatch = channel.color
            ? `<span class="channel-swatch" style="background:${escapeHtml(channel.color)}"></span>`
            : '<span class="channel-swatch"></span>';
        return `<tr>
            <td>${channel.visualIndex === null || channel.visualIndex === undefined ? '—' : formatCount(Number(channel.visualIndex) + 1)}</td>
            <td><div class="channel-name">${swatch}<span>${escapeHtml(channel.name)}</span></div></td>
            <td>${channel.mute ? '<span class="badge mute">静音</span>' : '<span class="badge live">发声</span>'}</td>
            <td>${isSolo ? '<span class="badge solo">独奏</span>' : isSafe ? '<span class="badge safe">安全</span>' : '<span class="muted-text">—</span>'}</td>
            <td>${formatGain(channel.gainDb)}</td>
            <td>${channel.alignRight ? '右侧通道' : '常规通道'}</td>
        </tr>`;
    }).join('');
}

function statusLabel(status) {
    return ({
        stopped: '已停止',
        connecting: '正在连接',
        open: '连接已建立',
        closing: '正在关闭',
        closed: '连接已关闭',
        reconnecting: '正在重连',
    })[status] || status || '未知';
}

const connectionUrlState = { value: '' };
const gatewayTokenState = { value: '' };
const adapterSelectionState = { value: '' };

function renderDeploy(gateway, network) {
    const candidates = (gateway.connectionUrls || []).filter((item) => {
        if (item.url && item.url.includes('token=')) return true;
        return !gateway.token;
    });
    const addresses = new Set(network.map((item) => item.address));
    const options = candidates.map((item) => ({
        ...item,
        adapter: network.find((entry) => entry.address === item.address)?.name || item.adapter || '未知适配器',
    }));
    const selected = options.find((item) => item.address === adapterSelectionState.value)
        || options.find((item) => item.address === gateway.preferredAddress)
        || options.find((item) => addresses.has(item.address))
        || options[0];
    adapterSelectionState.value = selected?.address || '';

    renderAdapterOptions(options, selected);
    if (connectionUrlState.value !== (selected?.url || '')) {
        connectionUrlState.value = selected?.url || '';
    }
    gatewayTokenState.value = gateway.token || '';
    stateElement('connectionUrl').value = connectionUrlState.value || '未获取到连接地址';
    stateElement('gatewayToken').value = gatewayTokenState.value || 'Token 未配置';
    stateElement('copyConnectionUrl').disabled = !connectionUrlState.value;
    stateElement('copyGatewayToken').disabled = !gatewayTokenState.value;

    if (!connectionUrlState.value) {
        stateElement('deployNote').textContent = '网关尚未生成连接地址，请检查网关状态和局域网适配器。';
        return;
    }
    stateElement('deployNote').textContent = `当前选择：${selected?.adapter} / ${selected?.address || '未知地址'}。请确认手机与电脑连接到同一局域网。`;
}

function renderAdapterOptions(options, selected) {
    const select = stateElement('adapterAddress');
    select.innerHTML = options.length
        ? options.map((item) => `<option value="${escapeHtml(item.address)}"${item.address === selected?.address ? ' selected' : ''}>${escapeHtml(`${item.adapter} — ${item.address}`)}</option>`).join('')
        : '<option value="">未检测到物理/无线网卡</option>';
    select.disabled = !options.length;
}

async function copyText(value, button, successLabel) {
    if (!value) return;
    await navigator.clipboard.writeText(value);
    const original = button.dataset.original || button.textContent;
    button.dataset.original = original;
    button.textContent = successLabel;
    setTimeout(() => { button.textContent = original; }, 1400);
}

function selectInput(id) {
    const input = stateElement(id);
    input.focus();
    input.select();
}

function renderClients(clients) {
    const body = stateElement('clientRows');
    if (!clients.length) {
        body.innerHTML = '<tr><td colspan="5" class="empty">暂无手机连接</td></tr>';
        return;
    }
    body.innerHTML = clients.map((client) => `<tr>
        <td>${escapeHtml(client.remote)}</td>
        <td>${client.authorized ? '<span style="color: var(--green)">已认证</span>' : '<span style="color: var(--amber)">等待认证</span>'}</td>
        <td>${formatCount(client.received)}</td>
        <td>${formatCount(client.sent)}</td>
        <td>${formatTime(client.connectedAt)}</td>
    </tr>`).join('');
}

function renderLogs(logs) {
    const list = stateElement('logList');
    setText('logCount', formatCount(logs.length));
    if (!logs.length) {
        list.innerHTML = '<div class="empty">暂无事件</div>';
        return;
    }
    list.innerHTML = [...logs].reverse().map((entry) => `<div class="log-entry">
        <span class="log-time">${escapeHtml(formatTime(entry.time))}</span>
        <span class="log-level ${escapeHtml(entry.level)}">${escapeHtml(entry.level)}</span>
        <span>${escapeHtml(entry.message)}${entry.data ? ` <span style="color: var(--muted)">${escapeHtml(JSON.stringify(entry.data))}</span>` : ''}</span>
    </div>`).join('');
}

function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, (character) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    })[character]);
}

stateElement('adapterAddress').addEventListener('change', (event) => {
    const address = event.target.value;
    if (!address) return;
    const url = (lastPayload.value?.gateway?.connectionUrls || []).find((item) => item.address === address)?.url;
    if (!url) return;
    adapterSelectionState.value = address;
    connectionUrlState.value = url;
    stateElement('connectionUrl').value = url;
    stateElement('copyConnectionUrl').disabled = false;
});

stateElement('copyConnectionUrl').addEventListener('click', async () => {
    await copyText(connectionUrlState.value, stateElement('copyConnectionUrl'), '已复制地址');
});
stateElement('copyGatewayToken').addEventListener('click', async () => {
    await copyText(gatewayTokenState.value, stateElement('copyGatewayToken'), '已复制 Token');
});
stateElement('selectConnectionUrl').addEventListener('click', () => selectInput('connectionUrl'));
stateElement('selectGatewayToken').addEventListener('click', () => selectInput('gatewayToken'));

stateElement('copyStatus').addEventListener('click', async () => {
    if (!lastPayload.value) return;
    await navigator.clipboard.writeText(JSON.stringify(lastPayload.value, null, 2));
    stateElement('copyStatus').textContent = '已复制';
    setTimeout(() => { stateElement('copyStatus').textContent = '复制状态'; }, 1200);
});

loadStatus();
setInterval(loadStatus, 2000);
