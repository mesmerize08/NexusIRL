package io.github.mesmerize08.nexusirl.core.codable

import kotlinx.serialization.json.Json

/**
 * JSON configuration matching Swift's `JSONEncoder`/`JSONDecoder` as used by Moblin's synthesized
 * `Codable` types (remote control, Moblink, settings).
 *
 * - `nil` optionals are omitted on encode (`explicitNulls = false`).
 * - Unknown keys are ignored and missing keys fall back to Kotlin defaults, mirroring Moblin's
 *   forgiving `container.decode(key, Type, default)` helper (upstream
 *   `Common/Various/CommonUtils.swift`).
 */
val SwiftJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}
