#!/usr/bin/env node
/**
 * chess-guru relay — a tiny, dependency-free HTTPS forwarder for the Lichess / Chess.com APIs.
 *
 * Runs on Render.com (free web service) or on an Android phone in Termux (`node relay.js`).
 * It is NOT an open proxy: only the hosts in TARGETS are reachable, and every request must carry
 * the shared secret in the `X-Relay-Secret` header.
 *
 *   GET  /health                      -> {"ok":true}  (no secret; used to wake a sleeping Render instance)
 *   ANY  /p/<target>/<path>?<query>   -> https://<host of target>/<path>?<query>
 *
 * Streaming responses (NDJSON game/event streams, seeks) are piped through unbuffered, and closing the
 * client connection aborts the upstream request (so a cancelled seek is cancelled on Lichess too).
 *
 * Env:
 *   RELAY_SECRET  required, at least 24 characters (generate one with: node relay.js --gen-secret)
 *   PORT          default 4100 (Render sets this automatically)
 *   HOST          default 0.0.0.0
 */
'use strict';
const http = require('node:http');
const https = require('node:https');
const crypto = require('node:crypto');
const os = require('node:os');

const VERSION = '1.0.0';
const TARGETS = {
  lichess: 'lichess.org',
  explorer: 'explorer.lichess.ovh',
  tablebase: 'tablebase.lichess.ovh',
  database: 'database.lichess.org',
  chesscom: 'api.chess.com',
};
const FORWARD_REQ_HEADERS = ['authorization', 'content-type', 'accept', 'content-length'];
const FORWARD_RES_HEADERS = ['content-type', 'content-length', 'retry-after', 'x-ratelimit-remaining', 'x-ratelimit-reset', 'cache-control', 'last-modified', 'etag'];
const MAX_BODY = 64 * 1024;

if (process.argv.includes('--gen-secret')) {
  console.log(crypto.randomBytes(24).toString('base64url'));
  process.exit(0);
}

const SECRET = process.env.RELAY_SECRET || '';
if (SECRET.length < 24) {
  console.error('RELAY_SECRET must be set to at least 24 characters. Generate one with: node relay.js --gen-secret');
  process.exit(1);
}
const SECRET_BUF = Buffer.from(SECRET);
const PORT = Number(process.env.PORT || 4100);
const HOST = process.env.HOST || '0.0.0.0';

// Very small brute-force guard: per-IP failed-auth counter.
const failures = new Map();
function tooManyFailures(ip) {
  const f = failures.get(ip);
  return f && f.count >= 20 && Date.now() - f.since < 15 * 60_000;
}
function noteFailure(ip) {
  const f = failures.get(ip);
  if (!f || Date.now() - f.since > 15 * 60_000) failures.set(ip, { count: 1, since: Date.now() });
  else f.count++;
}

function secretOk(given) {
  if (typeof given !== 'string') return false;
  const b = Buffer.from(given);
  return b.length === SECRET_BUF.length && crypto.timingSafeEqual(b, SECRET_BUF);
}

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) });
  res.end(body);
}

function log(...args) {
  // Never log headers or bodies (they may contain OAuth tokens).
  console.log(new Date().toISOString(), ...args);
}

const server = http.createServer((req, res) => {
  const ip = req.socket.remoteAddress || '?';
  const url = new URL(req.url || '/', 'http://relay.local');

  if (url.pathname === '/health') return send(res, 200, { ok: true, version: VERSION, targets: Object.keys(TARGETS) });

  if (tooManyFailures(ip)) return send(res, 429, { error: 'Too many failed attempts' });
  if (!secretOk(req.headers['x-relay-secret'])) {
    noteFailure(ip);
    return send(res, 401, { error: 'Missing or wrong relay secret' });
  }

  const m = /^\/p\/([a-z]+)(\/.*)$/.exec(url.pathname);
  const host = m && Object.prototype.hasOwnProperty.call(TARGETS, m[1]) ? TARGETS[m[1]] : null;
  if (!m || !host) return send(res, 404, { error: 'Unknown target' });
  if (!['GET', 'POST', 'DELETE', 'HEAD'].includes(req.method || '')) return send(res, 405, { error: 'Method not allowed' });

  const path = m[2] + url.search;
  const headers = { 'user-agent': `chess-guru-relay/${VERSION}` };
  for (const h of FORWARD_REQ_HEADERS) if (req.headers[h]) headers[h] = req.headers[h];

  let size = 0;
  const upstream = https.request({ host, path, method: req.method, headers, timeout: 0 }, (up) => {
    const out = {};
    for (const h of FORWARD_RES_HEADERS) if (up.headers[h]) out[h] = up.headers[h];
    if ((up.headers['content-type'] || '').includes('ndjson')) out['x-accel-buffering'] = 'no';
    res.writeHead(up.statusCode || 502, out);
    res.flushHeaders();
    up.pipe(res);
    log(req.method, m[1], m[2], up.statusCode);
  });
  upstream.on('error', (e) => {
    log('upstream error', m[1], m[2], e.code || e.message);
    if (!res.headersSent) send(res, 502, { error: 'Upstream unreachable', code: e.code || 'ERR' });
    else res.destroy();
  });
  // Closing the client connection cancels the upstream request (important for seeks and streams).
  res.on('close', () => upstream.destroy());

  req.on('data', (chunk) => {
    size += chunk.length;
    if (size > MAX_BODY) {
      upstream.destroy();
      if (!res.headersSent) send(res, 413, { error: 'Body too large' });
      req.destroy();
      return;
    }
    upstream.write(chunk);
  });
  req.on('end', () => upstream.end());
});

server.keepAliveTimeout = 65_000;
server.requestTimeout = 0; // allow long-lived streams
server.headersTimeout = 70_000;
server.listen(PORT, HOST, () => {
  log(`chess-guru relay ${VERSION} listening on ${HOST}:${PORT}`);
  if (!process.env.RENDER) {
    for (const addrs of Object.values(os.networkInterfaces())) {
      for (const a of addrs || []) if (a.family === 'IPv4' && !a.internal) log(`  reachable on your network at http://${a.address}:${PORT}`);
    }
  }
});
