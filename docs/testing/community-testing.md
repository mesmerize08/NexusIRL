# Testing NexusIRL on your phone

NexusIRL is tested on a Galaxy S26 Ultra, a Huawei P30 Pro and the Android emulator. Every phone's
camera, video encoder and network behave a little differently, so reports from other phones are
the most useful help there is. Pixels (Tensor), MediaTek and Exynos phones, and Android 11 to 15,
are especially wanted.

## Before you start

- Install the APK for your phone from the
  [newest release](https://github.com/mesmerize08/NexusIRL/releases): `arm64-v8a` for
  practically every phone from the last several years.
- You need somewhere to stream to that is **not a public channel**, for example a private or test
  stream on your own platform account, or a server you run
  ([server/test-receiver](../../server/test-receiver/README.md) is a small one).
- Never share stream keys or passwords in a report. The app's **Share diagnostics** (bottom of
  the main screen) removes them and is the most useful thing to attach.

## Checklist

Each takes a few minutes; do as many as you like. They match the
[Device test report](https://github.com/mesmerize08/NexusIRL/issues/new?template=device_test_report.yml)
form.

1. **Opens:** install, open, allow camera and microphone. The camera preview shows.
2. **Camera:** switch back and front, pinch to zoom, turn the torch on.
3. **Recording:** tap Record for a minute, stop. The file in Movies/NexusIRL plays, with sound in
   sync.
4. **SRT or SRTLA:** stream for 5 minutes. Watch the stream on another device: no freezes, sound
   in sync.
5. **RTMP or RTMPS:** the same over RTMP.
6. **H.265:** Streams → edit → Video → Codec H.265, stream again. If your phone has no H.265
   encoder, the app falls back to H.264 and says so.
7. **Bonding:** with Wi-Fi and mobile data both on, stream over SRTLA. The status line shows
   each network's share.
8. **Camera taken away:** while live, open the phone's Camera app for 10 seconds, then return. The
   stream keeps going and the picture comes back.
9. **Heat:** stay live for 30 minutes and note the thermal state in the status line.
10. **Microphone:** if you have one, pick a Bluetooth or USB microphone in the mic picker.

## Reporting

Open a [Device test report](https://github.com/mesmerize08/NexusIRL/issues/new?template=device_test_report.yml),
tick what worked, describe what did not, and attach the diagnostics file. A problem that needs
fixing can also go in a [bug report](https://github.com/mesmerize08/NexusIRL/issues/new?template=bug_report.yml).
