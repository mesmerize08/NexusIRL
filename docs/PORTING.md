# Porting tracker

This file maps upstream Moblin sources (`upstream/`, pinned at `97e26d2`) to their Kotlin ports.
When the submodule is bumped, check every row for upstream changes:

```sh
git -C upstream diff <old>..<new> -- <path>
```

| Upstream (Swift) | Kotlin port | Notes |
|---|---|---|
| `Moblin/Media/WrappingTimestamp.swift` | `core/clock/.../WrappingTimestamp.kt` | `CMTime` → `MediaTime` |
| `MoblinTests/Moblin/Media/WrappingTimestampSuite.swift` | `core/clock/src/test/.../WrappingTimestampTest.kt` | Vectors copied verbatim |
| Swift `JSONEncoder`/`Codable` conventions | `core/codable/` | Golden vectors from `RemoteControlSuite.swift` and `MoblinkSuite.swift` |
| `Common/Various/CommonUtils.swift` (`decode(key, Type, default)`) | `SwiftJson` config (`ignoreUnknownKeys`, defaults) | Full per-field forgiveness arrives with settings in Phase 3 |
| `Moblin/Media/HaishinKit/Util/ByteReader.swift`, `ByteWriter.swift` | `media/mux/.../Bytes.kt` | `ByteWriter` is append-only (the overwrite mode is unused by ported code) |
| `Moblin/Media/HaishinKit/Mpeg/Crc32.swift` | `media/mux/.../Crc32.kt` | |
| `Mpeg/MpegTsPacket.swift`, `MpegTsAdaptationField.swift` | `media/mux/.../ts/MpegTsPacket.kt` | Includes `TSTimestamp`, and PCR coding with upstream's reserved-bit quirk kept |
| `Mpeg/MpegTsProgramSpecificInformation.swift`, `ElementaryStreamSpecificData.swift` | `media/mux/.../ts/MpegTsProgram.kt` | Encode only; the PSI decoder arrives with `MpegTsReader` (post-v1 ingest) |
| `Mpeg/MpegTsPacketizedElementaryStream.swift` | `media/mux/.../ts/MpegTsPes.kt` | PTS ticks = `(seconds * 90000).toLong()`, truncating like upstream |
| `Mpeg/MpegTsAudioConfig.swift`, `Adts.swift`, `Opus.swift`, `Avc/MpegTsVideoConfigAvc.swift`, `Hevc/MpegTsVideoConfigHevc.swift` | `media/mux/.../ts/MpegTsConfigs.kt` | CoreMedia format-description bridges are not ported |
| `Mpeg/MpegTsTimecodeGenerator.swift` | `media/mux/.../ts/MpegTsTimecodeGenerator.kt` | Clock kept as epoch seconds (`Double`) |
| `Mpeg/MpegTsWriter.swift` | `media/mux/.../ts/MpegTsWriter.kt` | Takes Annex-B input (MediaCodec) instead of AVCC. `newSrt` becomes `OutputMode.BLOCKS`/`CHUNKED`. The global `payloadSize` becomes a constructor property. TrueTime becomes `TimecodeClock`. |
| `Mpeg/NalUnitReader.swift`, `NalUnitWriter.swift`, `NalUnitStream.swift` | `media/mux/.../nal/NalUnits.kt` | Adds `annexBToLengthPrefixed` for FLV/RTMP |
| `Mpeg/Avc/*.swift`, `Mpeg/Hevc/*.swift` | `media/mux/.../nal/AvcHevc.kt` | Header, raw parameter sets and timecode SEI |
| `MpegTsSuite`, `TSTimestampSuite`, `NalUnitReaderSuite`, `NalUnitWriterSuite`, `NalUnitSeiSuite`, `MpegTsTimecodeGeneratorSuite` | `media/mux/src/test/.../Upstream*Test.kt` | All vectors copied verbatim |
| — | `MpegTsWriterTest`, `FfmpegInteropTest` | New: an independent demuxer, plus an ffmpeg strict-decode check (skipped when ffmpeg is absent) |
| `Moblin/Media/Srtla/Common/Srt.swift`, `Srtla.swift`, `HaishinKit/Srt/SrtPerformanceData.swift` | `transport/srt/.../Srt.kt` | 32-bit sequence numbers held in `Long` |
| `Moblin/Media/HaishinKit/Srt/SrtSender.swift` | `transport/srt/.../SrtSender.kt` | Injected `HostClock` and `SerialExecutor` (instead of `srtlaClientQueue`) for deterministic tests. Quirks kept: no shutdown is sent; a received shutdown is only logged. |
| `Moblin/Media/HaishinKit/Srt/SrtStreamMoblin.swift` + SRT parts of `Various/Media.swift` | `transport/srt/.../SrtStream.kt` | Rejects `passphrase` until SRT encryption lands (plan §9.5) |
| `Moblin/Media/Srtla/Client/RemoteConnection.swift` | `transport/srt/.../srtla/RemoteConnection.kt` | `NWConnection` replaced by `UdpLink`/`BondingInterface` (Android binds the socket to a `Network`) |
| `Moblin/Media/Srtla/Client/SrtlaClient.swift` | `transport/srt/.../srtla/SrtlaClient.kt` | `NWPathMonitor` replaced by `setInterfaces()`. The interface sort is a stable rank, because upstream's comparator isn't a strict ordering. The libsrt `LocalListener` path is deferred to post-v1. |
| `SrtSenderSuite`, `SrtSuite` | `transport/srt/src/test/.../SrtSenderTest.kt` | Golden handshake vectors copied verbatim, plus pacing, retransmit, ACK and drop tests |
| — | `SrtInteropTest` | New: a live stream into ffmpeg's libsrt listener, with a strict decode of the recording |
| `Moblin/Media/Srtla/Server/SrtlaServer.swift`, `SrtlaServerClient.swift`, `SrtlaServerClientConnection.swift` | `transport/srt/.../srtla/server/SrtlaServer.kt` | SRTLA relay only. One `DatagramSocket` demuxed by endpoint replaces `NWListener`. Any SRT listener can sit behind it (libsrt via ffmpeg in tests); the libsrt-backed ingest arrives post-v1. |
| — | `SrtlaInteropTest` | New: bonded stream over 2 paths → ported SRTLA server → ffmpeg libsrt, checking per-path usage and a strict decode |
| `Moblin/Media/AdaptiveBitrate/AdaptiveBitrate.swift`, `AdaptiveBitrateSrtFight.swift`, `AdaptiveBitrateSrtBelabox.swift` | `transport/abr/` | Injected `HostClock`. The 20/200 ms driver and the transport-bitrate EWMA (from `Media.swift`) arrive with the Phase 2 pipeline. The RIST experiment comes post-v1. |
| `AdaptiveBitrateSuite` | `transport/abr/src/test/.../AdaptiveBitrateTest.kt` | Vectors kept; a fake clock replaces the 20 ms sleeps. Adds behaviour tests for both algorithms. |
| `Moblin/Media/HaishinKit/Rtmp/Amf/Amf.swift` | `transport/rtmp/.../Amf.kt` | `AsObject` keeps insertion order (`LinkedHashMap`); upstream uses a hash-ordered Swift `Dictionary` |
| `Rtmp/RtmpChunk.swift`, `RtmpChunkReader.swift`, `Rtmp/Message/*.swift` | `transport/rtmp/.../RtmpChunk.kt` | Messages are plain type + payload, with typed command/user-control helpers |
| `Rtmp/RtmpStream.swift`, `RtmpConnection.swift`, `RtmpSocket.swift`, `RtmpHandshake.swift` | `transport/rtmp/.../RtmpStream.kt` | One class with a blocking socket plus a writer thread (replacing `NWConnection`). Handshake consumes exactly S2. RTMPS adds hostname verification. Annex-B is converted to AVCC. |
| `Flv/Flv.swift` + FLV tag building in `RtmpStream.swift` | `transport/rtmp/.../Flv.kt` | Adds `avcC`/`hvcC` builders, because MediaCodec gives raw parameter sets |
| `Rtmp/RtmpStreamInfo.swift`, `Util/BitrateStats.swift` | `transport/rtmp/.../RtmpStreamInfo.kt` | Injected clock |
| `AmfSuite`, `RtmpSuite`, `RtmpStreamInfoSuite` | `transport/rtmp/src/test/.../UpstreamRtmpSuitesTest.kt` | The SRS `_result` vector is abridged; the others are verbatim |
| — | `RtmpInteropTest` | New: publish H.264 and enhanced-RTMP HEVC to ffmpeg's RTMP server, then strict decode; all 90 frames must arrive |
| — (not in upstream's own sender) | `transport/srt/.../SrtCrypto.kt` + `SrtSender` KMREQ/KMRSP | New: SRT AES-CTR encryption (HaiCrypt scheme: PBKDF2-HMAC-SHA1, RFC 3394 key wrap, even key). Tested with RFC vectors and live against libsrt (AES-128/256, wrong passphrase, wire tap). No key refresh yet (libsrt refreshes after 2^25 packets). |
| `Various/Media.swift` (updateAdaptiveBitrate*, updateSrtTransportBitrate) + 20 ms/1 s timers in `Model.swift` | `transport/srt/.../SrtAdaptiveBitrateDriver.kt` | Belabox every 20 ms, others every 200 ms; transport-bitrate EWMA (0.7/0.3) every 1 s; no transport cap in CBR mode |
| — | `SrtAdaptiveBitrateInteropTest` + `ShapingUdpRelay` | New: synthetic encoder → SRT → 2 Mbit/s shaped relay → libsrt; both algorithms back off below the cap and recover after it lifts, with no disconnects |
