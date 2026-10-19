# Threat model

Each threat is mapped to the guardrails in [PORT_PLAN.md](PORT_PLAN.md) §9 and to how they are
verified. **Status** shows which guardrails are implemented so far.

## Assets

- **Stream credentials:**
  - Stream keys, including the RTMP path key, SRT `streamid`/`passphrase` and WHIP bearer headers.
  - Multi-stream destination URLs.
- **Platform credentials:** Twitch/YouTube tokens and the Kick session cookie.
- **App passwords:** remote-control, Moblink and OBS WebSocket passwords, and relay URLs.
- **Integration secrets:** Discord webhooks and third-party API keys.
- **Control of the live stream itself:** going live or ending the stream, switching scenes, and
  changing what viewers see.

## Threats

| # | Threat | Guardrails | Verification | Status |
|---|---|---|---|---|
| T1 | **Device theft or file extraction**: an attacker with a rooted device or an adb backup reads stored settings | Secrets live in `SecretStore` (Tink AES-256-GCM, keyset wrapped by a non-exportable Keystore key). Settings hold only `{secret:…}` references. Backups and device transfer are disabled. | `AeadSecretStoreTest` (no plaintext on disk, a wrong key can't decrypt), plus an instrumented adb pull + grep check | `SecretStore`, backup rules: **done**. Settings splitting: Phase 3 |
| T2 | **Shoulder-surfing, screenshots, screen recording, casting or the recents thumbnail** leak a key | Masked fields with a reveal toggle, `FLAG_SECURE` on secret screens, the overlay shows only stream name and host, optional biometric reveal | Instrumented FLAG_SECURE test | Phase 3 |
| T3 | **Logs or diagnostics leak secrets**: logcat, the in-app log, remote-control `log` events, log export | `Redactor` on every sink: URL reduction, credential patterns, exact match on stored values. No telemetry. | `RedactorTest` | Redactor: **done**. Wiring into sinks: Phase 2+ |
| T4 | **Hostile LAN** (venue or hotel Wi-Fi) controls the stream through the web remote control, which is unauthenticated upstream | All local servers off by default and bound to LAN only. Random access token for web control, embedded in the generated `config.mjs`. Lockout after wrong passwords. | System tests: no token gives 401/close; lockout triggers | Phase 6 |
| T5 | **A sniffed remote-control session** allows offline brute force of the password hash | Strong generated default passwords, a minimum strength rule, and a recommendation to use `wss://` relay or Tailscale over the internet. The protocol stays upstream's for iOS interop. | Unit test of the password policy | Phase 6 |
| T6 | **A remote assistant overwrites settings** (`importSettings`) or redirects the stream | Dangerous requests are disabled by default and need confirmation on the device | System test | Phase 6 |
| T7 | **A malicious deep link, QR code or settings file** silently changes stream targets or starts streaming | Treated as untrusted input: strict validation, a diff-style confirmation, never auto-start, rejected while live | System test | Phase 3 |
| T8 | **A key sent in cleartext on the network** (RTMP, SRT without a passphrase) | "Unencrypted" badge in the UI, RTMPS suggested, SRT AES encryption implemented in the Kotlin sender, full TLS verification | SRT KM interop test, packet capture | SRT AES-128/256 encryption, RTMPS hostname verification: **done** (live libsrt interop and wire tap). UI badge: Phase 3 |
| T9 | **A malicious browser-widget page** reads app data | The `moblin.js` bridge exposes only chat and speech-to-text, no file or content access | Unit test of the bridge surface | Phase 4 |
| T10 | **Token over-privilege** | Minimal, incremental OAuth scopes and revoke on sign-out | Review | Phase 5 |
| T11 | **Supply chain**: a compromised dependency or a leaked secret in the repo | Version catalog pinning, dependency verification, CodeQL and gitleaks in CI, signed releases with checksums | CI | gitleaks and CodeQL in CI: **done**. Dependency verification: pending |
| T12 | **Dependence on third-party services** run by the upstream author | No author-run endpoints. CI check for author hosts. | `tools/check-independence.sh` in CI | **done** |
