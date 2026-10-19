# Releasing

Releases are built by `.github/workflows/release.yml` when a tag such as `v0.1.0` is pushed.
The workflow runs the tests and lint, builds a signed APK, and attaches it to a GitHub Release
together with `SHA256SUMS`. Releases are marked as pre-releases until v1.

## One-time setup: the signing key

The signing key never goes in the repository. Create it once, on your own machine:

```sh
keytool -genkeypair -keystore nexusirl-release.jks -alias nexusirl \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=NexusIRL"
```

Use a pseudonymous `-dname` such as the one above; it is embedded in every APK.

Back up `nexusirl-release.jks` and its passwords somewhere safe (a password manager).
**If the key is lost, existing installs cannot be updated**: users would have to uninstall first.

Then add four repository secrets (GitHub → Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `NEXUSIRL_KEYSTORE_BASE64` | `base64 -w0 nexusirl-release.jks` (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("nexusirl-release.jks"))`) |
| `NEXUSIRL_KEYSTORE_PASSWORD` | the keystore password |
| `NEXUSIRL_KEY_ALIAS` | `nexusirl` |
| `NEXUSIRL_KEY_PASSWORD` | the key password |

## Making a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit, then tag and push: `git tag v0.1.0 && git push origin v0.1.0`.

## Local release builds

Set the same variables, with `NEXUSIRL_KEYSTORE` pointing at the `.jks` file, then run
`./gradlew assembleRelease`. Without them, Gradle produces `app-release-unsigned.apk`.

## Licence check

`./gradlew :app:licensee` (also part of `check`) fails the build if a dependency is not under an
allowed permissive licence (Apache-2.0, MIT, BSD). See docs/PORT_PLAN.md §8.4.
