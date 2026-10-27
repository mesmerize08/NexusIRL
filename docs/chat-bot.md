---
title: Chat bot
---

# Chat bot

The chat bot lets you and your moderators control the stream by typing commands in chat. It reads
the chat of the selected stream (Twitch, Kick and YouTube), and also chat that a remote control
assistant forwards. The commands are the same as in Moblin, so viewers who know Moblin's bot can
use them.

Turn it on under **Settings → Chat → Bot → Enabled**.

## Commands

| Command | What it does |
|---|---|
| `!moblin scene <name>` | Switches to the scene with that name. Use quotes for names with spaces: `!moblin scene "My scene"`. |
| `!moblin mute`, `!moblin unmute` | Mutes or unmutes the microphone. |
| `!moblin zoom <x>` | Zooms the camera, for example `!moblin zoom 2`. |
| `!moblin torch on`, `!moblin torch off` | Turns the torch on or off. |
| `!moblin filter <filter> <on or off>` | Turns an effect on or off: `grayscale`, `sepia`, `pixellate`, `4:3`, `whirlpool` or `pinch`. |
| `!moblin snapshot` | Saves a picture of the stream to the phone (Pictures/NexusIRL) and, if the stream has a chat bot Discord webhook, posts it there with "Snapshot taken by <user>." The picture can come from another phone's web remote control instead (tap the command to choose); for another NexusIRL phone, paste its whole web remote control link, including `?t=`. |
| `!moblin stream start`, `!moblin stream stop` | Goes live or ends the stream. |
| `!moblin stream title <title>` | Changes the Twitch title (needs Twitch sign-in on the stream). |
| `!moblin stream category <category name>` | Changes the Twitch category; the name must match exactly. |
| `!moblin help` | Answers in chat with a link to this page (when signed in to Twitch). |

Small typos in the command words are forgiven when only one command is close, for example
`!moblin mut`.

Moblin's other commands (text to speech, OBS, widgets, music, Tesla and so on) are not in
NexusIRL yet and are ignored.

## Who may use a command

Tap a command under **Commands** to choose who may use it:

- **You** (the channel owner) always may.
- **Moderators**: on by default, except for scene and stream commands.
- **Subscribers**: off by default. NexusIRL cannot check Twitch subscriber tiers, so with a
  minimum tier above 1, Twitch subscribers are treated as others.
- **Others**: everyone else; off by default.
- **Cooldown**: how long subscribers and others must wait between uses.
- **Send chat responses**: the bot answers in chat when someone is not allowed or must wait. It
  can only answer in Twitch chat, and only when the stream is signed in to Twitch.

## Aliases

An alias is a short command that stands for a longer one. For example the alias `!screen` with the
replacement `!moblin scene Screen` switches to the Screen scene. Aliases must be one word starting
with `!`, written in lower case.
