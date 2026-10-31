# Releasing

Releases are built by `.github/workflows/release.yml` when a tag such as `v0.1.0` is pushed.
The workflow runs the tests and lint, builds signed APKs (one each for `arm64-v8a`, `armeabi-v7a`
and `x86_64`, plus a universal one), and attaches them to a GitHub Release together with
`SHA256SUMS`. Releases are marked as pre-releases until v1.

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

1. Bump `versionCode` (always +1) and `versionName` (semantic versioning) in
   `app/build.gradle.kts`. The APKs' own version codes are 10 × `versionCode` plus 0 (universal),
   1 (`armeabi-v7a`), 2 (`arm64-v8a`) or 3 (`x86_64`), so every APK of a release has its own code
   and a phone gets the most specific one. Then write:
   - release notes for testers in `docs/release-notes/v<version>.md` (used instead of GitHub's
     commit summary),
   - the `CHANGELOG.md` entry (move it from "unreleased" to the release date),
   - the F-Droid note `fastlane/metadata/android/en-US/changelogs/<code>.txt`, where `<code>` is
     10 × `versionCode` + 3 (the highest APK code, which F-Droid reads it for; up to v0.4.0 it was
     plain `versionCode`), 500 characters at most.
2. Commit, then tag and push: `git tag v0.1.0 && git push origin v0.1.0`.

## Local release builds

Set the same variables, with `NEXUSIRL_KEYSTORE` pointing at the `.jks` file, then run
`./gradlew assembleRelease -Pabi=arm64-v8a` (or `armeabi-v7a`, `x86_64`; leave out `-Pabi` for
the universal APK). Without them, Gradle produces an unsigned `app-release-unsigned.apk`. Each APK
is a separate build, as F-Droid builds them; debug builds are universal.

## Dry run

Run the Release workflow by hand (Actions → Release → Run workflow, or
`gh workflow run release.yml`). It builds and signs the APKs and uploads them as a workflow
artifact, but publishes nothing.

## Licence check

`./gradlew :app:licensee` (also part of `check`) fails the build if a dependency is not under an
allowed permissive licence (Apache-2.0, MIT, BSD). See docs/PORT_PLAN.md §8.4.

## Dependency updates

Monthly, and before each release:

1. GitHub Actions: Dependabot's grouped pull request moves each action to a new commit, with the
   version in a comment. Check that each commit is that version's tag
   (`gh api repos/<owner>/<action>/commits/<tag> --jq .sha`), read the release notes of major
   versions, and merge once CI passes. The pull request's CI does not run `release.yml`, so start a
   release dry run (below) after merging.
2. Gradle: check for newer versions of the entries in `gradle/libs.versions.toml` (AGP, Kotlin,
   Compose BOM, OkHttp, kotlinx, MapLibre, Tink, test libraries). Prefer releases that are a few
   weeks old. Update, regenerate the verification metadata (below), review that the new
   checksums are for the expected groups, run the full gate and the UI tests, and test on a
   phone when the camera, codec or network stack changed. The build uses AGP 9 (Kotlin built
   into AGP, so Android modules have no `kotlin-android` plugin), Gradle 9 and compile SDK 37;
   the target SDK is a separate decision because it changes app behaviour.
3. Security advisories (GitHub's Dependabot alerts on the repository) are handled right away.

## Dependency verification

`gradle/verification-metadata.xml` pins the SHA-256 of every downloaded dependency and plugin, so
a tampered or swapped artifact fails the build. After adding or upgrading a dependency, regenerate
it and review the diff:

```sh
./gradlew --write-verification-metadata sha256 --refresh-dependencies ktlintCheck test lint :app:licensee assembleDebug assembleDebugAndroidTest assembleRelease
ANDROID_SERIAL=emulator-5554 ./gradlew --write-verification-metadata sha256 :app:connectedDebugAndroidTest
```

`--refresh-dependencies` matters: with a warm cache Gradle skips BOM files, and CI on Linux then
fails verification. The second command records the UI-test runner, which only the emulator run
(CI's "ui-tests" job) downloads. Gradle only ever adds entries, so after a big upgrade delete the
file first and run both commands to drop versions no longer used. A fresh file only has the
`aapt2` jar of the computer that made it: add the other two (`-linux`, `-osx`, `-windows`) by hand
from Google Maven, checking each SHA-1 against its `.sha1` file, or CI on Linux fails.

`aapt2` is published per operating system and Gradle only records the jar for the machine it runs
on. Keep the Linux, macOS and Windows `aapt2-<version>-<os>.jar` entries in sync by hand
(download each from Google Maven and run `sha256sum`).

## F-Droid

**Status:** the first submission (fdroiddata merge request !54816, v0.4.0) was closed by F-Droid's
reviewer until the project is three months old, as F-Droid's inclusion policy requires. Reopen it
(or open a new one) on or after 2027-01-19, with the recipe updated to the latest release and its
per-ABI builds (below).

`fdroid/io.github.mesmerize08.nexusirl.yml` is the build recipe in fdroiddata format, exactly as
`fdroid rewritemeta` writes it (fdroiddata's CI rejects any other layout, so it has no comments).
Checked on 2026-10-29 for v0.4.0 with F-Droid's own tools
(`registry.gitlab.com/fdroid/docker-executable-fdroidserver`): `fdroid lint` and `rewritemeta`
against fdroiddata's `config/` are clean, `fdroid scanner` finds no problems, and `fdroid build`
builds it on their JDK 21 build environment. Re-run them before submitting or after build changes:

```sh
# In a work directory holding metadata/io.github.mesmerize08.nexusirl.yml (commit set to a pushed tag or commit):
docker run --rm --entrypoint sh -v "$PWD/metadata:/meta:ro" registry.gitlab.com/fdroid/docker-executable-fdroidserver:master -c \
  '. /etc/profile.d/bsenv.sh; mkdir -p /work && cp -r /meta /work/metadata && cd /work && ${fdroidserver}/fdroid scanner -v io.github.mesmerize08.nexusirl && ${fdroidserver}/fdroid build -v --no-tarball --test io.github.mesmerize08.nexusirl'
```

Requirements this relies on: no dependency metadata block in the APK (`dependenciesInfo`), one
APK per build (`-Pabi`, or none for v0.4.0 and earlier), any JDK 17 or newer, and no `upstream/` submodule needed for the
build. Lint needs fdroiddata's `config/` folder: run it in a sparse clone of fdroiddata
(`git sparse-checkout set config`) with the recipe in `metadata/`, then `fdroid lint` and
`fdroid rewritemeta` (no diff means the layout is right). To submit: update the build entry to the
latest release, with the tag's **full commit hash** (fdroiddata does not accept tag names), copy
the file to `metadata/` in a fork of https://gitlab.com/fdroid/fdroiddata, and open a merge
request. The recipe declares the `NonFreeNet` anti-feature, as chat and sign-in use Twitch, Kick
and YouTube.

The build is reproducible: F-Droid's build of v0.4.0 matches the release's universal APK, so the
recipe sets `Binaries` (the release's universal APK) and `AllowedAPKSigningKeys` (the release
certificate's SHA-256), and F-Droid publishes our signed APK. Users can move between GitHub and
F-Droid installs. `fdroid build` with a reference binary checks exactly that ("compared built
binary to supplied reference binary successfully"), so run it before changing the build.

**From the next release on, F-Droid builds one APK per ABI** (2026-10-29: F-Droid's builds of
`-Pabi=armeabi-v7a`, `arm64-v8a` and `x86_64` at ad7e701 matched a signed release dry run byte for
byte, and lint is clean). When updating the recipe for that release:

- remove the app-level `Binaries` field;
- replace the single build with three, one per ABI, each with `versionCode` 10 × `versionCode`
  plus 1, 2 or 3, the tag's full commit hash, `gradleprops: [abi=<abi>]` and
  `binary: https://github.com/mesmerize08/NexusIRL/releases/download/v%v/NexusIRL-v%v-<abi>.apk`;
- add `VercodeOperation: ['10*%c+1', '10*%c+2', '10*%c+3']` after `UpdateCheckMode`, and set
  `CurrentVersionCode` to the highest code;
- run `fdroid rewritemeta` (it wraps each `binary` URL onto its own line), lint and build.
