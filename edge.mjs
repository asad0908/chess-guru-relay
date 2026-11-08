/**
 * chess-guru relay — fetch-API version for Deno Deploy (main.ts) and Cloudflare Workers (worker.js).
 * Same rules as relay.js: allowlisted hosts only, shared secret required, streams passed through
 * unbuffered, client disconnect cancels the upstream request, headers/bodies are never logged.
 */
export const VERSION = '1.1.0';
export const TARGETS = {
  lichess: 'lichess.org',
  explorer: 'explorer.lichess.ovh',
  tablebase: 'tablebase.lichess.ovh',
  database: 'database.lichess.org',
  chesscom: 'api.chess.com',
};
const FORWARD_REQ_HEADERS = ['authorization', 'content-type', 'accept'];
const FORWARD_RES_HEADERS = ['content-type', 'content-length', 'retry-after', 'x-ratelimit-remaining', 'x-ratelimit-reset', 'cache-control', 'last-modified', 'etag'];
const MAX_BODY = 64 * 1024;

const json = (status, obj) => new Response(JSON.stringify(obj), { status, headers: { 'content-type': 'application/json' } });

function sameSecret(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const enc = new TextEncoder();
  const x = enc.encode(a);
  const y = enc.encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

/**
 * @param {string} secret
 * @param {typeof fetch} [fetchImpl]
 * @returns {(req: Request, ip?: string) => Promise<Response>}
 */
export function createHandler(secret, fetchImpl = fetch) {
  if (!secret || secret.length < 24) throw new Error('RELAY_SECRET must be set to at least 24 characters');
  const failures = new Map();

  return async function handle(req, ip = 'unknown') {
    const url = new URL(req.url);
    if (url.pathname === '/health') return json(200, { ok: true, version: VERSION, targets: Object.keys(TARGETS) });

    const f = failures.get(ip);
    if (f && f.count >= 20 && Date.now() - f.since < 15 * 60_000) return json(429, { error: 'Too many failed attempts' });
    if (!sameSecret(req.headers.get('x-relay-secret'), secret)) {
      if (!f || Date.now() - f.since > 15 * 60_000) failures.set(ip, { count: 1, since: Date.now() });
      else f.count++;
      return json(401, { error: 'Missing or wrong relay secret' });
    }

    const m = /^\/p\/([a-z]+)(\/.*)$/.exec(url.pathname);
    const host = m && Object.prototype.hasOwnProperty.call(TARGETS, m[1]) ? TARGETS[m[1]] : null;
    if (!m || !host) return json(404, { error: 'Unknown target' });
    if (!['GET', 'POST', 'DELETE', 'HEAD'].includes(req.method)) return json(405, { error: 'Method not allowed' });

    const headers = new Headers({ 'user-agent': `chess-guru-relay/${VERSION}` });
    for (const h of FORWARD_REQ_HEADERS) {
      const v = req.headers.get(h);
      if (v) headers.set(h, v);
    }
    let body;
    if (req.method === 'POST') {
      const len = Number(req.headers.get('content-length') ?? 0);
      if (len > MAX_BODY) return json(413, { error: 'Body too large' });
      const buf = await req.arrayBuffer();
      if (buf.byteLength > MAX_BODY) return json(413, { error: 'Body too large' });
      body = buf;
    }
    let up;
    try {
      up = await fetchImpl(`https://${host}${m[2]}${url.search}`, { method: req.method, headers, body, signal: req.signal, redirect: 'follow' });
    } catch (e) {
      if (req.signal?.aborted) return new Response(null, { status: 499 });
      console.log(new Date().toISOString(), 'upstream error', m[1], m[2], String(e && e.message));
      return json(502, { error: 'Upstream unreachable' });
    }
    const out = new Headers();
    for (const h of FORWARD_RES_HEADERS) {
      const v = up.headers.get(h);
      if (v) out.set(h, v);
    }
    console.log(new Date().toISOString(), req.method, m[1], m[2], up.status);
    // Body is streamed through (NDJSON game/event streams, seeks); cancelling the client cancels upstream via req.signal.
    return new Response(up.body, { status: up.status, headers: out });
  };
}
