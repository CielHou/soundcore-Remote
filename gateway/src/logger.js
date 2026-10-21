'use strict';

const fs = require('node:fs');
const path = require('node:path');

class Logger {
    constructor(maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.entries = [];
        this.seq = 0;
        this.writeQueue = Promise.resolve();
    }

    add(level, message, data) {
        const entry = {
            seq: ++this.seq,
            time: new Date().toISOString(),
            level,
            message: String(message),
            data,
        };
        this.entries.push(entry);
        if (this.entries.length > this.maxEntries) {
            this.entries.splice(0, this.entries.length - this.maxEntries);
        }
        const line = `${entry.time} ${level.toUpperCase().padEnd(5)} ${entry.message}${data ? ` ${JSON.stringify(data)}` : ''}`;
        if (level === 'error') console.error(line);
        else console.log(line);
        return entry;
    }

    debug(message, data) {
        return this.add('debug', message, data);
    }

    info(message, data) {
        return this.add('info', message, data);
    }

    warn(message, data) {
        return this.add('warn', message, data);
    }

    error(message, data) {
        return this.add('error', message, data);
    }

    recent(limit = 100) {
        return this.entries.slice(-Math.max(1, Math.min(limit, this.maxEntries)));
    }

    writeState(filePath, state) {
        const target = path.resolve(filePath);
        const payload = `${JSON.stringify(state, null, 2)}\n`;
        this.writeQueue = this.writeQueue
            .then(async () => {
                await fs.promises.mkdir(path.dirname(target), { recursive: true });
                await fs.promises.writeFile(target, payload, 'utf8');
            })
            .catch((error) => {
                this.debug('写入状态文件失败', { error: error.message });
            });
        return this.writeQueue;
    }
}

module.exports = { Logger };
