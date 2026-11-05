# Field test: mobile data, bonding and failover

Streaming over mobile networks can only be tested where coverage is good. This test needs no
computer and no Wi-Fi. The phone streams to a test receiver
([server/test-receiver](../../server/test-receiver/README.md)) that logs every network path every
5 seconds and records the stream, and the app keeps its own per-second log of each stream. Both
are analysed afterwards and matched by time.

## What you need

- **Streaming phone** with a data SIM, running NexusIRL with the **VPS bonding** stream
  (`srtla://…:5000?passphrase=…`) selected.
- **For bonding (tests C and D): a second phone with a data SIM**, ideally on a **different
  carrier** (if one network fades, the other may not). It runs a Moblink relay (NexusIRL, or
  Moblin on an iPhone) and joins the streaming phone's hotspot, so no Wi-Fi network is needed.
  Without a second SIM, do tests A and B.
- Batteries charged, ideally a power bank.
- **Mobile data:** about 37 MB per minute at 5 Mbps; the whole test uses roughly 0.5–1 GB in total,
  shared between the SIMs in tests C and D.
- **Safety:** the person holding the phone must not be the driver.

## Before you leave home (once)

On the **streaming phone**:

1. Select the **VPS bonding** stream (Streams → tap its circle). Do not select a Twitch stream.
2. Main screen (scroll the controls down) → **Moblink** → **Use other phones as relays**: turn on
   and note the password.

On the **relay phone** (bonding only):

- **Android:** install NexusIRL. Main screen → **Moblink** → **Moblink relay**: turn on, enter
  the same password. Android Settings → Developer options → **Mobile data always active**: on.
  Without it, some phones drop mobile data once they join a Wi-Fi network (the hotspot).
- **iPhone (Moblin):** Settings → Moblink → **Relay**: turn on, same password; leave **Streamer**
  off. If it does not find the streaming phone, turn on Manual and enter the address the streaming
  phone's Moblink page shows (`ws://…:7777`).

Only the streaming phone has "Use other phones as relays" on; only the relay phone has its relay
on. With the roles the other way round, the relay never connects.

## At the location

Pick a spot with good coverage (full bars, 4G or 5G). If you can, run a speed test on the
streaming phone and note the **upload** speed; streaming needs at least about 3 Mbps up.

**Write down the time** at each step (a photo of a clock or a note on the phone is fine).

| Test | Steps | What it shows |
|---|---|---|
| **A. Mobile only, standing still** (3 min) | Wi-Fi off on the streaming phone. Go live, wait 3 minutes, End stream. | The stream runs on one mobile network; how the bitrate settles. |
| **B. Mobile only, moving** (5 min, optional) | As A, while walking or as a passenger in a car or bus. | Adaptive bitrate through changing coverage and cell handovers. |
| **C. Bonding** (3 min) | Turn on the streaming phone's **hotspot**. Connect the relay phone to it. On the streaming phone, the **Moblink** screen should list the relay. Go live, wait 3 minutes, End stream. | Both SIMs carry the stream. |
| **D. Failover** (4 min) | As C. After 1 minute turn on **aeroplane mode on the relay phone**; after another minute turn it off again. After 1 more minute, End stream. | Losing one network does not stop the stream, and the path comes back. |
| **E. Camera taken away** (2 min) | During any test: open the phone's own **Camera app** for about 10 seconds, then go back to NexusIRL. | The stream keeps running with the last picture while the Camera app has the camera, and the live picture comes back by itself as soon as it is closed. |
| **F. Heat** (during the longest test) | Every 10 minutes, note the **thermal** state in the status line and the battery percentage. | Whether the phone gets hot enough to slow down at this bitrate. |
| **G. Opus audio** (3 min) | Streams → edit the VPS stream → Audio → Codec: **Opus**, Save. Go live as in A, End stream after 3 minutes. Set it back to AAC afterwards. | The server receives Opus audio, in sync with the video. |
| **H. Recording while live** (2 min) | During any test: tap **Record**, wait a minute, tap it again. Then End stream. | The stream keeps going while recording, the recording is saved (AAC audio even in test G), and it plays. |
| **I. Car Bluetooth** (any test, in a car) | Connect the phone to the car's Bluetooth before going live. | The microphone in the status line stays **Built-in**: the car stays a speaker ("Bluetooth output only"). |

During each test, watch the status line at the top of the controls: it should stay **LIVE** (red).
Note the time of any "Reconnecting". Under the bitrate, a line shows each network's share of the
data, for example "100% Cellular" in test A, or "55% Cellular, 45% Pixel" (the relay phone's name)
in tests C and D. In test D the relay's share should drop to 0% and come back.

## Checks at home (no drive needed)

New in v0.5.0, on the streaming phone unless noted:

1. **Torch brightness** (Android 15+): torch on, the slider next to it changes the brightness. On
   an older phone (Android 10) the slider is not shown and the torch works at full strength.
2. **Stream key protection:** Streams → edit a stream → **Show** on its URL asks for the
   fingerprint, face or screen lock; Cancel keeps it hidden. Going live never asks.
3. **Paste stream key:** copy a stream key, then **Paste stream key or URL** in the editor puts it
   in the URL.
4. **OBS** (OBS 28+ on a computer on the same Wi-Fi): OBS → Tools → WebSocket Server Settings →
   enable, set a password. In NexusIRL: Streams → edit → OBS remote control: on, `ws://<computer's
   IP>:4455` and the password. The main screen shows "OBS: …"; the **OBS** quick button (turn it
   on under the quick buttons' **Edit** if it is not shown) switches
   scenes. A wrong password shows "Authentication failed.".
5. **Twitch** (weekends only, on your own channel): sign in to Twitch again in the stream's
   settings to get the new alerts (hype trains, polls, predictions).

## Afterwards (within 2 days)

1. On the streaming phone, tap **Share diagnostics** (bottom of the main screen) and email the
   file to yourself. It contains the per-second log of the last 10 streams, with keys and
   passwords removed. If you used a relay phone, do the same on it.
2. Send the diagnostics file(s), the times you noted and the speed test result.

The test receiver keeps recordings for 2 days, so send these within 2 days.

## How it is analysed

- **Phone stream log** (in the diagnostics file): every second the frames sent, encoder, target
  bitrate, round-trip time, SRT retransmissions and each network path's share and round-trip
  time, plus every state change (LIVE, Reconnecting and why) and the phone's temperature status.
- **Receiver log** (`journalctl -u nexusirl-srtla` on the server): every 5 seconds each path the
  receiver sees (the phone's public address on that network) with packets per second and Mbit/s.
- **Recording** (`/var/lib/nexusirl/recordings`, 10-minute segments): gaps and frame rate as a
  viewer would have seen them.

Both logs use the phone's local time, so the three line up.
