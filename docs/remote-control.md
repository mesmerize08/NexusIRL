# Remote control

An assistant can watch a NexusIRL stream's status and preview, and control it: go live or end the
stream, record, switch scenes, zoom, torch, mute and more. None of it needs a server run by the
NexusIRL or Moblin developers. Pick whichever of these fits:

| How | Works over | Needs |
|---|---|---|
| 1. Web remote control | Same Wi-Fi, hotspot or Tailscale | A browser |
| 2. Assistant on the same network | Same Wi-Fi or hotspot | A second phone running Moblin or NexusIRL as assistant |
| 3. Tailscale (or ZeroTier) | The internet | The VPN app on both devices |
| 4. Self-hosted relay | The internet | A small VPS and a domain name |

## 1. Web remote control

Settings → Remote control → Web remote control. The phone serves upstream Moblin's browser remote
control on port 8080 and shows a link with a QR code. The link carries a private access token, so
share it only with people who may control your stream. **New link** signs out every browser. The
server accepts connections only from Wi-Fi, hotspot and Tailscale addresses, never over mobile
data.

## 2. Assistant on the same network

On the assistant phone, turn on the remote control assistant (port 2345 by default). On the
streaming phone, go to Settings → Remote control, enter `ws://<assistant's IP>:2345` and the same
password. Plain `ws://` is fine here: it never leaves your local network, and the password itself
is never sent (each side proves it knows it). After 5 wrong passwords from an address, the NexusIRL
assistant refuses that address for 60 seconds.

## 3. Over the internet with Tailscale

Install [Tailscale](https://tailscale.com) on both devices, then use option 1 or 2 with the
device's Tailscale address (`100.x.y.z`). Traffic is encrypted end to end, and nothing is exposed
to the internet.

## 4. Self-hosted relay

Run Moblin's open-source relay on your own server with automatic HTTPS. Setup and security
notes are in [server/relay/README.md](../server/relay/README.md). The streamer's assistant URL then
looks like `wss://relay.example.org/streamer/<bridge id>`. Treat it like a password: NexusIRL
stores its path encrypted and leaves it out of exports and logs.

NexusIRL refuses `ws://` addresses outside the local network and shows a warning, because a
captured password exchange could be brute-forced offline. Use `wss://` (a relay with TLS) or
Tailscale instead.

## Compatibility

The protocol is the same as iOS Moblin's, so NexusIRL works with a Moblin assistant, the relay's
browser assistant page and Moblin's Python `moblin_assistant`. NexusIRL never offers the Moblin
author's public relay as a default. Remote settings imports from an assistant are refused.
