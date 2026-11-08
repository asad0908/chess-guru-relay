// Deno Deploy entrypoint: set the RELAY_SECRET environment variable in the Deno Deploy dashboard.
import { createHandler } from './edge.mjs';

const handle = createHandler(Deno.env.get('RELAY_SECRET') ?? '');
Deno.serve((req, info) => handle(req, (info.remoteAddr as Deno.NetAddr).hostname));
