# Changelog

All notable changes, newest first. The format follows [Keep a Changelog](https://keepachangelog.com),
and versions follow [Semantic Versioning](https://semver.org) (0.x: anything may still change).

## [Unreleased]

### Fixed
- The stream's audio bitrate setting (Moblin's "Audio bitrate", from imported settings) was ignored:
  audio always went out at 128 kbps. Streams now use their own audio bitrate and recordings theirs,
  within what the phone's encoder can do (at most 256 kbps for mono AAC). RTMP servers are told
  the real audio bitrate, and no audio track at all when the stream has no sound.
- If a phone cannot encode Opus, NexusIRL now says it is streaming AAC instead; and if audio cannot
  start at all, it says the stream has no sound. Both were silent before.
- With an Opus stream falling back to AAC next to a recording, two identical audio encoders ran;
  now they share one. The microphone indicator turns off when the last working audio output stops.
- After another app used the camera while live (for example the phone's Camera app), the picture
  stuttered for several seconds once the camera was back, at about 2 frames a second. With the
  picture standing still, the video encoder saved up bitrate (keyframes of about 1 MB instead of
  0.1 MB) and spent it at once when the camera returned. While the picture really stands still
  (no camera image, no camera switch, nothing moving on screen), streams with constant bitrate now
  run at a tenth of their bitrate (at least 500 kbps), and adaptive bitrate does not climb
  meanwhile. Measured on a Galaxy S26 Ultra: 30 fps throughout, the largest frame after the camera
  returns 0.35 MB instead of up to 1.3 MB. Recordings and VBR streams are not affected.

## [0.5.0] - 2026-11-05

### Added
- OBS remote control, as in Moblin: connect to OBS on your computer (OBS 28+, Tools → WebSocket
  Server Settings) from the stream's settings, then switch scenes, start and stop OBS streaming and
  recording, mute inputs, adjust the stream source's audio sync and fix it from the new OBS quick
  button. Going live and ending can start and stop OBS streaming or recording, and OBS switches to
  a BRB scene while the phone's stream is reconnecting. The OBS password is kept encrypted. New
  chat bot command `!moblin obs fix`; remote control assistants see the OBS status.
- Opus audio for streams, as in Moblin (Streams → edit → Audio → Codec), over SRT, SRTLA and RTMP
  when the server accepts it. Recordings are still saved with AAC.
- Torch brightness, as in Moblin: on Android 15+ phones that support it, a slider next to the
  Torch button; the chat bot's `!moblin torch level 1-100` sets it too. At 100 % the phone's own
  torch level is used.
- Twitch, as in Moblin: hype trains, polls and predictions in chat and as banners under the live
  status (progress, votes or points, countdown; tap to make smaller, or close); watch streaks,
  Prime subscriptions, subscription upgrades, and shared chat partners' subscriptions, gifts and
  raids (when "Shared chat" is on); a toast when an ad break starts and a chat line for shoutouts.
  Twitch sign-in asks for six more read-only permissions for these: sign in again in the stream's
  settings to get them (earlier sign-ins keep their alerts and get a one-time hint).
- Stream editor: "Paste stream key or URL" puts a pasted stream URL, or just the key, in the right
  place for RTMP and SRT.
- Web remote control: copy its link with one tap.
- Kick sign-in, as in Moblin (Streams → edit → Sign in to Kick): you sign in on Kick's own page
  inside the app, then messages typed in NexusIRL and chat bot answers go to Kick chat too, and the
  stream title and category can be changed. Kick's session is kept encrypted and never exported;
  the sign-in page's web data is deleted afterwards. (Kick's official login needs a secret an
  open-source app cannot keep, so this uses Kick's website session, as Moblin does.)
  The chat bot's `!moblin stream title` and `stream category` change Kick too when the selected
  stream is signed in there (Moblin changes Twitch only).
- Kick alerts, as in Moblin: subscriptions, gifted subscriptions, rewards, hosts and Kicks pop up
  on screen and show in chat, and bans and timeouts show in chat, each switchable per stream
  (Streams → edit → Kick alerts), with a minimum for Kicks. Read from Kick's public chat
  connection, no sign-in needed. Moblin's Kick alert settings import from iOS.

### Changed
- Microphones work as in Moblin. **Bluetooth output only** is on by default (mic picker), so a
  car or headset stays a speaker instead of becoming the microphone: in the field tests the car's
  Bluetooth call microphone was used for whole streams. The microphone chosen is remembered and
  used again next time, and **Switch automatically** (on by default) moves only to a newly
  connected microphone that ranks higher, instead of to the highest-ranked one whenever anything
  changes (choosing a microphone no longer turns it off). The settings use Moblin's names
  (`debug.bluetoothOutputOnly`, `mics.autoSwitch`, `mics.defaultMic`).
- The Moblink page starts with how to add another phone's mobile data to this phone's stream,
  the password shared by both parts, and then "Use other phones as relays" before the relay part
  (which lends this phone's data to another phone's stream). It shows this phone's address for
  relays that do not find it, and warns while this phone is a relay. In the field test the relay
  part, which came first, was turned on instead, so the phones' roles were swapped.
- The diagnostics report says what happened with Moblink relays (connected, refused and why,
  wrong password, tunnels), and leaves out the video encoder's debug lines, which filled most of
  its log. Camera and scene switches, camera errors and Moblink relay events while live are also
  kept in the stream's own log, with the relays connected when it starts: Android's shared log
  only reached back two minutes in the field test, so they were lost.
- Releases have an APK for each processor type (`arm64-v8a`, `armeabi-v7a`, `x86_64`) besides the
  universal one, about a third of its size. Each has its own version code (10 × the release's plus
  1, 2 or 3; 0 for universal), so F-Droid can offer the same, signed by us.
- When the selected stream is signed in to neither Twitch nor Kick, the chat panel says where to
  sign in to send messages, instead of showing no message field.
- When the chat bot cannot change the stream title or category, a toast says why (Moblin fails
  silently).

### Security
From an independent pre-release audit (`docs/audit/`).
- Passwords, keys and tokens anywhere in the settings are kept in encrypted storage instead of the
  plain settings file, also those from iOS settings NexusIRL does not use itself (OBS, AI and TTS
  keys, Wi-Fi, DJI and GoPro, ingest stream keys, browser widget links and more). "Export without
  keys" leaves all of them out (it only covered stream keys, tokens and a few passwords before);
  exports with keys still include them. Settings saved by earlier versions are protected the next
  time the app starts.
- Web remote control can no longer be overwhelmed by slow or broken connections, which could
  crash the app mid-stream: it handles a limited number of requests at once and drops requests
  that stall.
- Remote control, web remote control and Moblink refuse oversized messages instead of holding them
  in memory.
- Remote control and Moblink check that the connecting device is on the local network, not only
  this phone's address, so port forwarding or a VPN cannot let internet hosts in. Devices on this
  phone's hotspot still connect.
- Unencrypted connections count as "this device" only for `localhost` and loopback addresses,
  never for look-alike names such as `127.example.com`.
- Releases are published only from commits on main that passed CI, with every APK's signature,
  signing certificate and version checked before upload.
- Saved stream keys, multi-stream URLs and Discord webhooks are shown or changed in the stream
  editor only after the fingerprint, face or screen lock (never when going live).
- Copied secrets (the web remote control link) are hidden from keyboards and clipboard previews and
  removed from the clipboard after a minute, unless something else was copied since.
- A remote control assistant can import settings only when "Allow settings import from the
  assistant" is on (off by default) and the import is accepted on the phone within 30 seconds,
  never while live. It was refused outright before.

### Fixed
- When writing a recording fails (for example with storage full), NexusIRL no longer says
  "Recording saved" for a broken file: a file that could still be finished is kept and the message
  says it stops early; one that could not be finished is deleted and the message says so.
- A very large or malformed colour LUT (for example in an imported settings file) is refused or
  skipped with a message, instead of running out of memory and crashing the app, on every start
  when the LUT was on.
- Going live, ending a stream and starting or stopping a recording no longer hold up the screen
  (stopping could take up to about 8 seconds, long enough for Android to offer to close the app as
  not responding). The recording message now appears once the file is finished.
- An RTMP destination that stops taking data could hold up to 100 MB of video in memory before
  reconnecting, as in Moblin; on a phone, two or more of them (multi streaming) could run the app
  out of memory. Each now holds at most an eighth of the app's memory (on most phones 32-64 MB,
  20-80 seconds of video) before reconnecting.
- An RTMPS server that accepts the connection but never answers no longer hangs a stream attempt:
  connecting gives up after 10 seconds, and stopping ends the attempt at once.
- A link with a user name and password (`ws://user:pass@host`) came back broken after being stored.
- The remote control screen's address check now accepts local IPv6 addresses (`ws://[fe80::…]`), and
  OBS addresses with underscores in `.local` names. The chat box no longer offers to send to Kick
  before the Kick channel is known, when sending could not work.
- When another app took the camera while live (for example the phone's Camera app), the stream
  showed a frozen picture until the camera was switched by hand. The camera now comes back by
  itself as soon as it is free, even with NexusIRL in the background.
- A possible crash when starting or stopping a recording or stream while the video was held up
  for several seconds.
- The video sent much more than its bitrate on some phones: in the 1 November field test the
  Galaxy S26 Ultra sent 7-14 Mbps for a 5 Mbps stream while driving, and kept doing so when
  adaptive bitrate lowered it for poor coverage, which overloaded the connection (round trips of
  up to 14 seconds and dropped video). As in Moblin, the video now stays within 1.2 times its
  bitrate in any second: streams use the encoder's constant bitrate mode, and when an encoder
  still sends too much, frames are skipped until it is back within the limit, for at most about a
  second at a time (the preview is not affected). In the 2 November field test the video stayed
  at its 5 Mbps while driving and the server lost 16 late packets in 14 minutes, against 1,896 in 8
  minutes before. The stream's rate control setting (ABR, CBR, VBR, from Moblin's settings) is now
  used: VBR keeps the encoder's own variable bitrate without a limit, as in Moblin, and CBR also
  stops adaptive bitrate following the connection's recent throughput, as in Moblin.
- Moblink relays and remote control could not connect over IPv6, which iPhones use when the
  phones share a hotspot or Wi-Fi: the iPhone found NexusIRL but stayed on "Connecting". IPv6
  link-local and unique local addresses now count as the local network, and so do public IPv6
  addresses of devices on the same network link (never other internet hosts, and still never
  over mobile data).

## [0.4.0] - 2026-10-29

### Added
- Multi streaming, as in Moblin: an RTMP stream can also go to more RTMP servers at once (for
  example Twitch and YouTube), from the same encoder (Streams → edit → Multi streaming). Each
  destination reconnects on its own, and their stream keys are kept like the stream's own.
- Chat bot (Settings → Chat → Bot), as in Moblin: you and your moderators run `!moblin` commands
  from chat to switch scenes, mute and unmute, zoom, turn the torch and effects on and off, take
  snapshots, and start or stop the stream or change its Twitch title and category. Each command
  has Moblin's permissions (moderators, subscribers, everyone, cooldown, chat answers), and
  aliases such as `!screen` work. Moblin's chat bot settings import from iOS.
- Snapshots upload to Discord, as in Moblin: set a webhook per stream (Streams → edit → Discord),
  with a separate one for chat bot snapshots and an "only when live" switch. The webhook links are
  kept in the encrypted secret store and left out of exports without keys.
- The chat bot's snapshot command can take the picture from another phone's web remote control
  (Settings → Chat → Bot → `!moblin snapshot`), as in Moblin.
- Chat and Twitch alerts forwarded by a remote control assistant (Moblin's `chatMessages` and
  `twitchEventSubNotification` requests) show in chat, the chat widget and alert toasts, as in
  Moblin. Messages from SOOP and Open Streaming Platform are shown too.
- Bonding status: while live over SRTLA, the main screen shows each network's share of the data
  (for example "60% Cellular, 40% WiFi"), as in Moblin. Remote controls get it too, with each
  path's round-trip time, and the bitrate as numbers (Moblin's newer `bitrateInfo` and
  `bondingInfo`).

### Changed
- Build tools updated: Android Gradle Plugin 9.4, Gradle 9.8, Kotlin 2.4 and compile SDK 37
  (Android 17), with newer AndroidX libraries (Compose BOM 2026.09, core 1.19, lifecycle 2.11,
  activity 1.13). The app still targets Android 16.
- Zooming sends remote controls at most two updates a second, as Moblin now does.

### Fixed
- Starting or stopping a recording or stream held up the video for up to a tenth of a second, so
  60 fps recordings could miss frames at the start. Encoders now start and stop beside the video
  instead of in its way.
- Importing a `.moblinSettings` file now brings its images along (image widgets and LUT files), and
  exports include them, as in Moblin. Before, image widgets from iOS stayed empty.
- Image widgets apply their LUT effects from Moblin settings.
- A scene whose camera is "None" shows black behind its widgets, as in Moblin, instead of the last
  camera.
- The map widget's position dot is the same size on every map, as in Moblin.
- After importing settings without quick buttons, mute, torch and effects did nothing until the
  app was restarted. An import now adds the missing buttons (all off, as Moblin) and applies them.
- Remote control: the camera status says "None" for a scene without a camera, as in Moblin.
- A crash when a stream was stopped within a moment of starting (for example two quick taps, or
  a remote control going live and ending straight away).
- A crash when chat showed an emote without a name.
- Remote control: the status reports live, with the bitrate, from the moment a stream starts, as
  in Moblin, and the web remote control's Live switch turns off when the stream ends.

## [0.3.0] - 2026-10-27

### Added
- Encrypted settings exports: exporting with stream keys can encrypt the file with a passphrase
  (AES-256-GCM, PBKDF2 key). NexusIRL imports it after asking for the passphrase; the plain
  export for iOS Moblin is still there, behind a warning.

### Changed
- Video frames go out on an exact time grid, like Moblin's frame timer, and audio times follow
  the sample count: every frame is exactly one frame time after the previous one, so players and
  servers see perfectly even timing.
- Recordings use B-frames (better quality for the same size), like Moblin's.
- Built-in microphones record and stream in mono unless **Prefer stereo** is on (microphone
  picker), as in Moblin; remote control assistants now see the real channel count.
- Ready for F-Droid: the APK has no Google-encrypted dependency block, the app builds with any JDK
  17 or newer and without the upstream submodule, and `fdroid/` holds the build recipe (checked
  with F-Droid's scanner and build tools).

### Fixed
- Switching between the back and front cameras failed with "Camera session configuration failed"
  or "Camera error 2": the next camera opened before the previous one had closed. Cameras now take
  turns, and quick taps end on the last camera chosen.
- A camera error stayed on screen after the camera worked again.
- Back from the LUTs or Widgets screen opened with a quick button went to Scenes instead of the
  main screen.
- The preview could still be drawn into its surface after the screen had released it (logged as
  "Surface failed to disconnect").
- With the Portrait button and a landscape stream, the picture was sideways when holding the phone
  upright; it now stays upright, like Moblin, and the stream shows a landscape crop of it.
- Two crashes: when the screen turned off while the preview was being set up, and when a quick
  scene switch restarted the camera while it was still opening.
- Recordings could start with a few milliseconds of audio before the picture and lose the last
  frames on stop.

## [0.2.1] - 2026-10-26

### Added
- Web remote control: the Recordings page works (list, download, delete and thumbnails of the
  app's recordings), as in Moblin.
- Stream logs: the app keeps a per-second log of the last 10 streams (frame rate, bitrate,
  connection quality per network, state changes), included in Share diagnostics, so streams far
  from a computer can be analysed. No keys, picture or sound.

### Fixed
- The app crashed when a browser closed its connection to the web remote control in the middle
  of a response; anyone on the same network could trigger it, even without the access link.
- Remote control and YouTube settings used different names from iOS Moblin, so an iOS
  remote control setup or YouTube video IDs were not imported, and NexusIRL's were not read by
  iOS. Settings saved by earlier versions are converted when loaded.
- The remote control status now shows the measured bitrate with the total sent and the uptime in
  Moblin's format ("5.0 Mbps (12.3 MB)", "1m 23s"); assistants showed the encoder's target before.
- Remote control requests take effect before they are answered, as in Moblin, so a status read
  right after "go live" shows the stream starting.

## [0.2.0] - 2026-10-25

### Added
- Twitch alert pop-ups on the streamer's screen (follows, subscriptions, resubscriptions, gift
  subscriptions, channel point rewards, raids, cheers), one at a time.
- Per-stream Twitch alert settings, as in Moblin: which alerts pop up and which appear in chat,
  with a minimum for cheers. Settings imported from iOS keep their choices.
- Twitch stream title and category: shown in the stream's settings, with category search, and
  changed on Twitch after a confirmation. Needs signing in to Twitch again once (one more
  permission, `channel:manage:broadcast`).
- Self-hosted remote control relay (`server/relay`: Moblin's open-source relay with automatic
  HTTPS) and a guide to every way of connecting a remote control (`docs/remote-control.md`).

### Changed
- The remote control assistant URL's path (a relay's bridge ID) is stored encrypted, left out of
  exports without keys and removed from logs, like stream keys. Existing settings are converted
  on start.

### Fixed
- Twitch alerts always appeared in chat, ignoring the alert settings of imported iOS streams.
- The privacy policy described an earlier preview; it now lists every service the app contacts
  and what each receives.
- Closing the camera logged a warning with a stack trace each time.
- An import confirmation said "1 stream(s)".
- While a remote control showed the preview, the stream lost one frame every second (the preview
  was read back at full size on the render thread). Found with an iOS Moblin assistant.
- The Twitch sign-in dialog now says to come back to the app: after approving on the phone,
  Twitch's page can stay blank.

### Tested
- Live on Twitch from a Galaxy S26 Ultra (RTMPS, H.264), a Huawei P30 Pro on Android 10, and
  interop with iOS Moblin (remote control assistant, settings import). See
  [docs/release-notes/v0.2.0.md](docs/release-notes/v0.2.0.md).

## [0.1.1] - 2026-10-22

### Added
- UI smoke tests on an emulator in CI (every screen opens; adding a stream never shows its key),
  and unit tests for the camera clock fix found on the Galaxy S26 Ultra.
- "Share diagnostics" on the main screen: a text report for bug reports (app and phone details,
  status, cameras, encoders, networks, settings without secrets, the app's recent log), with
  stream keys, passwords and tokens removed. Shared only when the user chooses to.

## [0.1.0] - 2026-10-22

First test release. See [docs/release-notes/v0.1.0.md](docs/release-notes/v0.1.0.md) for what
works, what was tested and known limitations.
