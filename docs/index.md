---
title: NexusIRL, a free IRL streaming app for Android
description: >-
  NexusIRL is a free, open-source IRL live streaming app for Android with SRTLA bonding of mobile
  data and Wi-Fi, adaptive bitrate, SRT and RTMP, Twitch and Kick chat and OBS remote control. An
  Android port of Moblin.
---

# NexusIRL: free IRL live streaming for Android

NexusIRL is a free, open-source **IRL streaming app for Android**: stream live from your phone
while you are out and about, to Twitch, Kick, YouTube or your own server. It is an Android port of
[Moblin](https://github.com/eerimoq/moblin), the iOS IRL streaming app, and works with Moblin's
settings files, remote control and Moblink relays. No accounts, ads, analytics or tracking.

Not affiliated with or endorsed by the Moblin project.

**[Download the latest release](https://github.com/mesmerize08/NexusIRL/releases/latest)** (Android 10 or
newer) · [Source code](https://github.com/mesmerize08/NexusIRL) ·
[What's new](https://github.com/mesmerize08/NexusIRL/blob/main/CHANGELOG.md)

## Features

- **Bonding (SRTLA):** combine mobile data, Wi-Fi and Ethernet, and other phones' mobile data
  through Moblink relays, into one stream that keeps going when a network fades.
- **SRT, SRTLA, RTMP and RTMPS**, H.264 and H.265 (HEVC) video, AAC or Opus audio.
- **Adaptive bitrate** that follows the connection, as in Moblin.
- **Twitch and Kick:** chat, alerts, title and category, and a chat bot (`!moblin` commands).
- **OBS remote control:** switch OBS scenes and start or stop OBS from the phone, with an
  automatic BRB scene while the phone's stream reconnects.
- **Scenes and widgets:** text, images, maps, browser sources, LUTs and effects.
- **Remote control** from a browser or another phone, and recording to the phone.
- **Moblin compatible:** import a Moblin settings file from an iPhone.

## Questions

**Is there a Moblin app for Android?** Moblin itself is iOS only. NexusIRL is an independent,
open-source Android port of it, made with the same protocols, so an iPhone running Moblin and a
phone running NexusIRL can share settings, remote control each other and bond each other's
mobile data.

**Is it free?** Yes, free and open source under the MIT licence, with no paid tier, accounts or
ads.

**Which phones does it run on?** Android 10 or newer. It is tested on a Samsung Galaxy S26 Ultra
and a Huawei P30 Pro; [reports from other phones](testing/community-testing.md) are very welcome.

**What do I need for bonding?** A server that receives SRTLA, such as a self-hosted
[SRTLA receiver](https://github.com/mesmerize08/NexusIRL/tree/main/server) or an IRL hosting
service, and the phone's mobile data plus Wi-Fi or a second phone as a Moblink relay.

## Guides

- [Remote control](remote-control.md): from a browser, another phone, over Tailscale or through
  your own relay.
- [Chat bot](chat-bot.md): control the stream with `!moblin` commands in chat.
- [Field test](testing/field-test.md): testing mobile data and bonding away from home.
- [Testing on your phone](testing/community-testing.md): a short checklist for other phones.

## Policies

- [Privacy policy](privacy-policy.md)
- [Security policy](https://github.com/mesmerize08/NexusIRL/blob/main/SECURITY.md) and
  [threat model](threat-model.md)

## For developers

- [Port plan](PORT_PLAN.md): architecture, phases and their status
- [Porting tracker](PORTING.md): which Moblin files map to which Kotlin files
- [Releasing](RELEASING.md): releases, signing and dependency updates
- [Contributing](https://github.com/mesmerize08/NexusIRL/blob/main/CONTRIBUTING.md)

<script type="application/ld+json">
{
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  "name": "NexusIRL",
  "description": "Free, open-source IRL live streaming app for Android with SRTLA bonding, adaptive bitrate, SRT and RTMP, Twitch and Kick chat and OBS remote control. An Android port of Moblin.",
  "applicationCategory": "MultimediaApplication",
  "operatingSystem": "Android 10 or newer",
  "url": "https://mesmerize08.github.io/NexusIRL/",
  "downloadUrl": "https://github.com/mesmerize08/NexusIRL/releases/latest",
  "softwareVersion": "0.5.0",
  "license": "https://opensource.org/licenses/MIT",
  "isAccessibleForFree": true,
  "offers": { "@type": "Offer", "price": "0", "priceCurrency": "USD" }
}
</script>
