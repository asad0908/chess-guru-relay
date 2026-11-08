// Cloudflare Workers entrypoint (alternative host): `wrangler secret put RELAY_SECRET`.
import { createHandler } from './edge.mjs';

let handle;
export default {
  fetch(req, env) {
    handle ??= createHandler(env.RELAY_SECRET);
    return handle(req, req.headers.get('cf-connecting-ip') ?? 'unknown');
  },
};
