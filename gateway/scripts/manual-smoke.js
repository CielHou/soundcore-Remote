'use strict';

const fs = require('node:fs');
const path = require('node:path');
const WebSocket = require('ws');

const token = fs.readFileSync(path.join(__dirname, '..', '.state', 'client-token.txt'), 'utf8').trim();
const socket = new WebSocket(`ws://127.0.0.1:18533/api/v1?token=${encodeURIComponent(token)}`);

let authSent = false;
let connectionSent = false;

socket.on('open', () => {
  socket.send(JSON.stringify({
    id: 'auth-1',
    method: 'AUTH',
    path: '/gateway/authorize',
    body: { token },
  }));
});

socket.on('message', (raw) => {
  const message = JSON.parse(raw.toString());

  if (message.id === 'auth-1' && !connectionSent) {
    connectionSent = true;
    socket.send(JSON.stringify({
      id: 'connections-1',
      method: 'POST',
      path: '/connections',
      body: { app_name: 'manual-smoke' },
    }));
    return;
  }

  if (message.id === 'connections-1' && !authSent) {
    authSent = true;
    socket.send(JSON.stringify({
      id: 'status-1',
      method: 'GET',
      path: '/gateway/status',
    }));
    socket.send(JSON.stringify({
      id: 'channels-1',
      method: 'GET',
      path: '/channels',
    }));
    return;
  }

  if (message.id === 'channels-1') {
    if (message.status === 200 && Array.isArray(message.data)) {
      console.log(JSON.stringify({
        event: 'smoke-success',
        connectionsStatus: 200,
        channelCount: message.data.length,
        authorized: true,
      }));
      socket.close(1000, 'smoke complete');
    } else {
      console.error(JSON.stringify({ event: 'smoke-failed', response: message }));
      process.exitCode = 1;
      socket.close(1011, 'smoke failed');
    }
  }
});

socket.on('close', (code) => {
  if (!authSent) process.exitCode = 1;
  setTimeout(() => process.exit(process.exitCode || 0), 50);
});

setTimeout(() => {
  console.error(JSON.stringify({ event: 'smoke-timeout' }));
  process.exit(1);
}, 10000);

const timeout = setTimeout(() => {
    console.error('验证超时');
    process.exitCode = 1;
    socket.close();
}, 5000);

socket.on('open', () => {
    socket.send(JSON.stringify({
        id: 'auth-1',
        method: 'AUTH',
        path: '/gateway/authorize',
        body: { token },
    }));
});

socket.on('message', (raw) => {
    const message = JSON.parse(raw.toString());
    console.log(JSON.stringify(message));
    if (message.event === 'gateway-authorized') {
        socket.send(JSON.stringify({
            id: 'status-1',
            method: 'GET',
            path: '/gateway/status',
        }));
    }
    if (message.id === 'status-1') {
        socket.send(JSON.stringify({
            id: 'channels-1',
            method: 'GET',
            path: '/channels',
        }));
    }
    if (message.id === 'channels-1') {
        clearTimeout(timeout);
        socket.close(1000, 'verification complete');
    }
});

socket.on('close', () => {
    if (!process.exitCode) process.exitCode = 0;
});

socket.on('error', (error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
});
