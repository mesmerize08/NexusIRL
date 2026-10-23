# Field test: bonding and failover

Bonding (mobile data plus Wi-Fi at the same time) can only be tested where mobile coverage is good.
This test needs no computer: the phone streams to a test receiver
([server/test-receiver](../../server/test-receiver/README.md)), which logs every network path every
5 seconds, so the result can be analysed afterwards.

## Before you go

- The app has a stream pointing at the test receiver (`srtla://…:5000?passphrase=…`), selected.
- Battery charged. Mobile data and Wi-Fi both on in Android's settings.
- Data use: about **37 MB per minute** at 5 Mbps, split between the networks. The whole test uses
  roughly 200–400 MB of mobile data.

## At the location

Pick a spot with good coverage (full bars, 4G or 5G). If you can, run a speed test first with Wi-Fi
off and note the **upload** speed; streaming needs at least about 3 Mbps up at 5 Mbps.

Write down the **time** at each step; the receiver's log is matched by time.

| Test | Steps | What it shows |
|---|---|---|
| **A. Mobile only** (2 min) | Wi-Fi off. Go live, wait 2 minutes, End stream. | The stream works on mobile data alone. |
| **B. Bonding and failover** (3 min) | On a Wi-Fi network (café, friend's place) with good mobile coverage too. Go live. After 1 minute turn **Wi-Fi off**; after another minute turn it **on**; after another minute End stream. | Both networks carry the stream; losing Wi-Fi does not stop it. |
| **C. Walking away** (optional, 3 min) | Go live on Wi-Fi and walk out of its range, then back. | The stream moves between networks on its own. |

During each test, glance at the status bar at the top of the controls: it should stay **LIVE**
(red). "Reconnecting" means the stream was interrupted; note when.

## Afterwards

1. On the main screen, tap **Share diagnostics** and send the file to yourself (email or Drive).
   It contains the app's log for the last few minutes, with keys and passwords removed.
2. Send the times you wrote down, the speed test result, and the diagnostics file to whoever
   analyses the test. The receiver's log shows, every 5 seconds, how much each network carried.

To read the receiver's log yourself: `journalctl -u nexusirl-srtla --since "YYYY-MM-DD HH:MM"`
on the server. Each report lists every path (the phone's public address on that network) with
its packets per second and Mbit/s.
