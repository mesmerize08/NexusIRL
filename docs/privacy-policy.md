# NexusIRL privacy policy

_Last updated: 2026-10-25_

NexusIRL is a free, open-source live-streaming app. It has no accounts of its own, no ads,
analytics, crash reporting or tracking, and the developers run no servers that the app talks to.
**The developers never receive any of your data.**

## What stays on your phone

- **Camera and microphone.** Used for the preview while the app is open, and while you stream or
  record. Video and audio go only to the streaming server **you** enter, and recordings and
  snapshots are saved on your phone (`Movies/NexusIRL`, `Pictures/NexusIRL`).
- **Stream URLs, stream keys, passwords and sign-ins.** Stored on your phone, encrypted with a key
  held in the Android Keystore, excluded from backups, and removed from logs and diagnostics.
- **Location** (off by default). Only when you turn it on, for widgets that show speed, distance
  or a map. It appears in your stream only through those widgets, and areas you mark as private
  are hidden.
- **Network state.** Read to bond mobile data, Wi-Fi and Ethernet together (SRTLA). Not stored or
  sent anywhere.
- **Settings.** Saved on your phone. Exports are created only when you ask, without keys unless
  you choose otherwise; with keys they can be encrypted with a passphrase of your choice.

## When the app contacts other services

Only when you use the feature, and only the service named:

| Feature | Service contacted | What it receives |
|---|---|---|
| Streaming | The server in your stream URL | Your video and audio |
| Twitch chat (reading) | `irc-ws.chat.twitch.tv` | The channel name you enter (read anonymously) |
| Twitch sign-in | `id.twitch.tv`, `api.twitch.tv`, `eventsub.wss.twitch.tv` | Your Twitch sign-in, used to send chat, show alerts and viewer counts, and change your stream's title and category when you ask. Permissions requested: `user:write:chat`, `channel:manage:broadcast`, `moderator:read:followers`, `channel:read:subscriptions`, `bits:read`, `channel:read:redemptions`. Signing out revokes the sign-in at Twitch. |
| Twitch badges | `static-cdn.jtvnw.net` | Requests for badge images |
| Kick chat | `kick.com`, `files.kick.com`, `ws-us2.pusher.com` | The channel name you enter |
| Kick sign-in | `kick.com`, `search.kick.com` | You sign in on Kick's own website inside the app; the app keeps Kick's session (encrypted) and uses it to send chat messages and chat bot answers, and to change your stream's title and category when you ask. Kick's website data is deleted from the app once you are signed in. Signing out deletes the session from the app; it stays valid at Kick until it expires or you sign out on kick.com. |
| YouTube chat | `www.youtube.com` | The channel handle or video you enter |
| Emotes (off by default) | `api.betterttv.net`, `cdn.betterttv.net`, `7tv.io` and their image servers | The Twitch channel whose emotes are shown |
| Map widget | `tiles.openfreemap.org`, or the map style you set | Map tile requests, which reveal the area shown |
| Browser widgets | The web pages you add | What any website receives from a browser |
| Remote control | The assistant or relay address you enter | Status and preview images of the stream |

Each service handles data under its own privacy policy.

## Things that never leave your phone unless you share them

- **Stream logs.** For each of your last 10 streams the app keeps a technical log on the phone:
  times, frame rate, bitrate, connection quality per network and state changes. It contains no
  stream keys, picture or sound, and is deleted with the app.
- **Diagnostics.** "Share diagnostics" creates a text file (app and phone details, settings
  without keys, the stream logs, the app's recent log) and opens the share sheet. Nothing is
  sent unless you choose where to send it.
- **Local network servers** (web remote control, remote-control assistant, Moblink). Off by
  default; when on, they accept connections only from your local network, hotspot or Tailscale,
  protected by an access link or password.

## Permissions

Camera and microphone (streaming and recording), location (only for location widgets, only when
you turn it on), notifications (the ongoing notification that keeps a stream running in the
background), and network access.

## Changes and contact

Changes to this policy are tracked in the
[project repository](https://github.com/mesmerize08/NexusIRL/commits/main/docs/privacy-policy.md).
For questions, open an issue at <https://github.com/mesmerize08/NexusIRL/issues>.
