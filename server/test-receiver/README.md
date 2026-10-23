# SRTLA test receiver

A small Linux server that receives bonded SRTLA streams for testing (for example mobile data plus
Wi-Fi from a phone) and records them. It is a test tool, not a production ingest: one stream at a
time, recordings kept for 2 days.

- **Server:** any Ubuntu 24.04 VPS with 1 vCPU and 1 GB RAM, ideally close to where you test.
  A 5 Mbit/s stream uses about 2.3 GB of transfer per hour.
- **Security:** key-only SSH, a firewall allowing only SSH and UDP 5000, automatic security
  updates. The stream is encrypted with a random SRT passphrase, so nobody else can send to it.

## Setup

```sh
./gradlew :tools:srtla-receiver:installDist
scp -r tools/srtla-receiver/build/install/srtla-receiver root@SERVER:/tmp/
scp server/test-receiver/setup.sh root@SERVER:/tmp/
ssh root@SERVER sh /tmp/setup.sh
ssh root@SERVER cat /etc/nexusirl/srt-passphrase
```

In the app, add a stream with the URL `srtla://SERVER:5000?passphrase=PASSPHRASE`. Recordings are
in `/var/lib/nexusirl/recordings` on the server (MPEG-TS, 10-minute segments).

The receiver logs every path (network) of a stream every 5 seconds: `journalctl -u nexusirl-srtla`.
For testing bonding away from a desk, see [docs/testing/field-test.md](../../docs/testing/field-test.md).

To test from a desktop instead: `tools/push/build/install/push/bin/push --input clip.mp4 --url
"srtla://SERVER:5000?passphrase=PASSPHRASE"`.
