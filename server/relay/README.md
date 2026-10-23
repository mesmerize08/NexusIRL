# Self-hosted remote control relay

Control NexusIRL from anywhere over the internet, through a relay you run yourself. Neither phone
needs a public address: the streamer and the assistant both connect out to the relay.

This packages [Moblin's remote control relay](https://github.com/eerimoq/moblin-remote-control-relay)
(MIT, by Erik Moqvist), built from a pinned commit, behind [Caddy](https://caddyserver.com) for
automatic HTTPS. It works with NexusIRL and iOS Moblin alike, since both speak the same protocol.

You don't need a relay when both devices are on the same Wi-Fi or hotspot, or both are on
Tailscale. See [docs/remote-control.md](../../docs/remote-control.md).

## What you need

- **Server:** any Linux VPS with Docker (1 vCPU and 512 MB RAM are plenty). Remote control is
  light: status once a second and a small preview image.
- **A domain name** pointing at the server (an `A` record such as `relay.example.org`). Caddy
  needs it to get a TLS certificate. Without TLS the app refuses `ws://` addresses on the
  internet, because the password exchange could be captured and guessed offline.
- **Open ports:** TCP 80 (certificate challenge, redirects to HTTPS) and TCP 443.

## Setup

```sh
git clone https://github.com/mesmerize08/NexusIRL.git
cd NexusIRL/server/relay
echo "RELAY_DOMAIN=relay.example.org" > .env
docker compose up -d --build
```

Open `https://relay.example.org/` and check that the page loads with a valid certificate.

## Connecting

1. **Assistant:** open `https://relay.example.org/assistant.html` in a browser, or turn on the
   relay in iOS Moblin's remote control assistant settings with your relay's URL. Enter a streamer
   name and a strong password and apply them.
2. Copy the **Assistant URL** the page shows, `wss://relay.example.org/streamer/<bridge id>`.
3. **Streamer (NexusIRL):** Settings → Remote control: turn it on, paste the URL as the assistant
   URL and enter the same password.

The status appears in the assistant within a few seconds.

## Security

- **Treat the assistant URL like a password.** The bridge ID in it is what lets an assistant
  take part. Anyone who has it can pose as your assistant, and if the streamer then signs in, they
  can control it and try to guess the password offline. NexusIRL keeps the URL's path in its
  encrypted secret store, leaves it out of settings exports (unless you export with keys) and
  removes it from logs and diagnostics. Don't show it on stream. To revoke it, regenerate the
  bridge ID on the assistant page and paste the new URL into the app.
- Use a long random password (the app's **Generate** button). It is never sent in clear text;
  each side proves it knows it.
- The relay forwards messages and keeps nothing on disk. Its container runs as a non-root user
  with a read-only file system and no extra capabilities. Caddy stores only its certificates.
- The relay publishes connection counts at `/stats.json`, as upstream does. They contain no names
  or addresses.
- The assistant web page loads its fonts from `assets.ubuntu.com` (upstream's Vanilla framework
  stylesheet).

## Updating

`RELAY_COMMIT` in the `Dockerfile` pins the upstream version. To update, set it to a newer commit
from the upstream repository after reading its changes, then:

```sh
git pull
docker compose up -d --build
```

The relay build verifies Go module checksums against upstream's `go.sum`.
