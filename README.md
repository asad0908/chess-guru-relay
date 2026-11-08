# chess-guru relay

A tiny forwarder that lets chess-guru reach Lichess and Chess.com when your own network blocks them.
It is one file (`relay.js`) with no dependencies, needs Node ≥ 18, and only forwards to these hosts:
`lichess.org`, `explorer.lichess.ovh`, `tablebase.lichess.ovh`, `database.lichess.org` and `api.chess.com`.
Every request must carry your secret.

You need the **secret** from the `RELAY_SECRET=` line of chess-guru's `.env` file.
To make a new one, run `node relay.js --gen-secret` and put the result in both places.

## Option A: Render.com (free, works from anywhere)
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
