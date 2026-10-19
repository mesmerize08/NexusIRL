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
