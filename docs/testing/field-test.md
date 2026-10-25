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
  carrier** (if one network fades, the other may not). It runs a Moblink relay and joins the
  streaming phone's hotspot, so no Wi-Fi network is needed. Without a second SIM, do tests A and B.
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

1. Install NexusIRL. Main screen → **Moblink** → **Moblink relay**: turn on, enter the same
   password.
2. Android Settings → Developer options → **Mobile data always active**: on. Without it, some
   phones drop mobile data once they join a Wi-Fi network (the hotspot).

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

During each test, watch the status line at the top of the controls: it should stay **LIVE** (red).
Note the time of any "Reconnecting".

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
