# chess-guru relay

A tiny forwarder that lets chess-guru reach Lichess and Chess.com when your own network blocks them.
It has no dependencies. `relay.js` runs on Node ≥ 18 (Render, Termux); `edge.mjs` + `main.ts` run on Deno Deploy / Cloudflare Workers. It only forwards to these hosts:
`lichess.org`, `explorer.lichess.ovh`, `tablebase.lichess.ovh`, `database.lichess.org` and `api.chess.com`.
Every request must carry your secret.

You need the **secret** from the `RELAY_SECRET=` line of chess-guru's `.env` file.
To make a new one, run `node relay.js --gen-secret` and put the result in both places.

## Option A: Deno Deploy (free, no card, works from anywhere) — recommended
Uses `main.ts` + `edge.mjs` (the fetch-API version of the relay; same rules as `relay.js`).
1. Go to https://console.deno.com and sign in with GitHub. Allow access to the `chess-guru-relay` repository.
2. **New app** → pick the `chess-guru-relay` repo → branch `main`. Leave the framework as **No preset / None** and set the **entrypoint** to `main.ts` (`deno.json` already says so). No build step is needed.
3. In the app's **Settings → Environment variables**, add `RELAY_SECRET` with your secret, then redeploy if asked.
4. Copy the app URL (something like `https://chess-guru-relay.<your-name>.deno.net`) into chess-guru **Settings → Relay URL**.
5. Check it: opening `<url>/health` in a browser should show `{"ok":true,...}`.

## Option A1: Back4App Containers (free, no card)
1. https://containers.back4app.com → **New app** → connect GitHub → pick `chess-guru-relay` (branch `main`). It builds the `Dockerfile`.
2. **Port:** `4100`. **Environment variables:** `RELAY_SECRET` = your secret.
3. Deploy, then copy the public URL (`https://<app>-<id>.b4a.run`) into chess-guru **Settings → Relay URL**.

## Option A2: Cloudflare Workers (free, no card)
`npx wrangler deploy worker.js --name chess-guru-relay --compatibility-date 2026-01-01`, then `npx wrangler secret put RELAY_SECRET`.
The URL is `https://chess-guru-relay.<account>.workers.dev`. Note: workers.dev may be blocked on some networks.

## Option A3: Render.com (Render may ask for a card)
1. Create a GitHub repository, say `chess-guru-relay` (private is fine). Upload `relay.js`, `package.json` and `render.yaml`.
2. On https://render.com sign in with GitHub, then choose **New → Blueprint**, pick the repository and click **Apply**.
   Alternatively: **New → Web Service** with runtime *Node*, build command `echo no build`, start command `node relay.js`, plan *Free*.
3. When asked for **RELAY_SECRET**, paste your secret.
4. Copy the service URL, e.g. `https://chess-guru-relay-xxxx.onrender.com`. In chess-guru open **Settings → Relay URL** and paste it, or set `RELAY_URL=` in `.env`.
5. Click **Check relay** in Settings. The free plan sleeps after 15 idle minutes. The first request then takes 30–60 s while it wakes up, and chess-guru shows a "waking up" message.

## Option B: an Android phone on the same Wi-Fi (Termux)
1. Install **Termux** from F-Droid (https://f-droid.org/packages/com.termux/) or its GitHub releases. The Play Store version is outdated.
2. In Termux: `pkg install nodejs`
3. Copy `relay.js` to the phone, e.g. with `termux-setup-storage` and then copying it from Downloads.
4. Run: `RELAY_SECRET=<your secret> node relay.js`. It prints `reachable on your network at http://192.168.x.y:4100`.
5. In chess-guru **Settings → Relay URL** enter that address.
6. Keep it alive: pull down the Termux notification and tap **Acquire wakelock**. Also set Android battery optimisation for Termux to *Unrestricted*.

## Security notes
- Not an open proxy: it only allows the five hosts above, and only GET, POST, DELETE and HEAD.
- The secret is compared in constant time. An IP is locked out for 15 minutes after 20 wrong attempts.
- Request bodies are capped at 64 KB.
- The relay never logs headers or bodies. Your Lichess token passes through in the `Authorization` header and is never stored.
- Your Lichess token travels: laptop → relay (HTTPS on Render; plain HTTP on your home Wi-Fi with the phone) → Lichess (HTTPS).
- On a work laptop, check that using a relay is allowed by your employer's IT policy.

## Option C: Android app (no Termux needed)
1. On the phone, open https://github.com/asad0908/chess-guru-relay/raw/main/dist/chess-guru-relay.apk and install it. Android asks once to allow installs from your browser.
2. Open **chess-guru relay**, paste the secret (from `RELAY_SECRET=` in `.env`), tap **Start relay**, then **Allow background running**.
3. Put the address it shows (e.g. `http://192.168.1.102:4100`) into chess-guru **Settings → Relay URL**. The phone and computer must be on the same Wi-Fi.

Build it yourself: `python -I relay/android/build.py --sdk <Android SDK>` (plain SDK tools; no Gradle). Source is in `relay/android/src`.
