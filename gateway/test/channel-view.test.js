'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { MobileGateway } = require('../src/gateway');

/** 用原型构造一个只带快照字段的网关实例，避免为视图逻辑启动真实 WebSocket。 */
function gatewayWith(channelSnapshot, soloSnapshot) {
    const gateway = Object.create(MobileGateway.prototype);
    gateway.channelSnapshot = channelSnapshot;
    gateway.soloSnapshot = soloSnapshot;
    return gateway;
}

test('channelView 按 visual_index 排序并补齐默认值', () => {
    const gateway = gatewayWith([
        { id: 'b', name: '发送', index: 1, visual_index: 8, mute: false, gain_db: -3.5, align_right: true },
        { id: 'a', name: '伴奏', index: 2, visual_index: 0, mute: true, gain_db: 0, color: '#2A2AFF' },
        { id: 'c', index: 5, visual_index: 3 },
    ]);
    const view = gateway.channelView();
    assert.deepEqual(view.map((channel) => channel.id), ['a', 'c', 'b']);
    assert.equal(view[0].name, '伴奏');
    assert.equal(view[0].mute, true);
    assert.equal(view[0].gainDb, 0);
    assert.equal(view[1].name, '未命名通道');
    assert.equal(view[1].gainDb, null);
    assert.equal(view[2].alignRight, true);
    assert.equal(view[2].visualIndex, 8);
});

test('channelView 在没有快照时返回空数组', () => {
    assert.deepEqual(gatewayWith(undefined, null).channelView(), []);
});

test('channelView 用 index 兜底排序与 visualIndex', () => {
    const view = gatewayWith([{ id: 'x', index: 4 }, { id: 'y', index: 2 }]).channelView();
    assert.deepEqual(view.map((channel) => channel.id), ['y', 'x']);
    assert.equal(view[0].visualIndex, 2);
});

test('soloView 只保留整数通道编号', () => {
    const gateway = gatewayWith([], { selected: [1, '2', 3.5, 5], safe: [0, 6], excluded: [] });
    assert.deepEqual(gateway.soloView(), { selected: [1, 2, 5], safe: [0, 6], excluded: [] });
});

test('soloView 在无快照时返回空分组', () => {
    assert.deepEqual(gatewayWith([], null).soloView(), { selected: [], safe: [], excluded: [] });
});
