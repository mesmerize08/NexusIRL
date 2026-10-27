# Releasing

Releases are built by `.github/workflows/release.yml` when a tag such as `v0.1.0` is pushed.
The workflow runs the tests and lint, builds signed APKs (`arm64-v8a` and universal), and attaches
them to a GitHub Release together with `SHA256SUMS`. Releases are marked as pre-releases until v1.

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
   `app/build.gradle.kts`, and write:
   - release notes for testers in `docs/release-notes/v<version>.md` (used instead of GitHub's
     commit summary),
   - the `CHANGELOG.md` entry (move it from "unreleased" to the release date),
   - the F-Droid note `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500
     characters at most).
2. Commit, then tag and push: `git tag v0.1.0 && git push origin v0.1.0`.

## Local release builds

Set the same variables, with `NEXUSIRL_KEYSTORE` pointing at the `.jks` file, then run
`./gradlew assembleRelease`. Without them, Gradle produces unsigned APKs
(`app-<abi>-release-unsigned.apk`). Release builds are split per ABI; debug builds are not.

## Dry run

Run the Release workflow by hand (Actions → Release → Run workflow, or
`gh workflow run release.yml`). It builds and signs the APKs and uploads them as a workflow
artifact, but publishes nothing.

## Licence check

`./gradlew :app:licensee` (also part of `check`) fails the build if a dependency is not under an
allowed permissive licence (Apache-2.0, MIT, BSD). See docs/PORT_PLAN.md §8.4.

## Dependency updates

Monthly, and before each release:

1. GitHub Actions: merge Dependabot's grouped pull request once CI passes on it.
2. Gradle: check for newer versions of the entries in `gradle/libs.versions.toml` (AGP, Kotlin,
   Compose BOM, OkHttp, kotlinx, MapLibre, Tink, test libraries). Prefer releases that are a few
   weeks old. Update, regenerate the verification metadata (below), review that the new
   checksums are for the expected groups, run the full gate and the UI tests, and test on a
   phone when the camera, codec or network stack changed.
   Compose 1.12 (BOM 2026.x), core 1.19 and lifecycle 2.11 need AGP 9.1 and compile SDK 37,
   so they wait for the AGP 9 migration; until then the AndroidX libraries stay on their
   AGP 8 versions.
3. Security advisories (GitHub's Dependabot alerts on the repository) are handled right away.

## Dependency verification

`gradle/verification-metadata.xml` pins the SHA-256 of every downloaded dependency and plugin, so
a tampered or swapped artifact fails the build. After adding or upgrading a dependency, regenerate
it and review the diff:

```sh
./gradlew --write-verification-metadata sha256 --refresh-dependencies ktlintCheck test lint :app:licensee assembleDebug assembleDebugAndroidTest assembleRelease
```

`--refresh-dependencies` matters: with a warm cache Gradle skips BOM files, and CI on Linux then
fails verification.

`aapt2` is published per operating system and Gradle only records the jar for the machine it runs
on. Keep the Linux, macOS and Windows `aapt2-<version>-<os>.jar` entries in sync by hand
(download each from Google Maven and run `sha256sum`).

## F-Droid

`fdroid/io.github.mesmerize08.nexusirl.yml` is the build recipe in fdroiddata format. Checked on
2026-10-26 with F-Droid's own tools (`registry.gitlab.com/fdroid/docker-executable-fdroidserver`):
`fdroid scanner` finds no problems in the source or the APK, and `fdroid build` builds it on their
JDK 21 build environment. Re-run both before submitting or after build changes:

```sh
# In a work directory holding metadata/io.github.mesmerize08.nexusirl.yml (commit set to a pushed tag or commit):
docker run --rm --entrypoint sh -v "$PWD/metadata:/meta:ro" registry.gitlab.com/fdroid/docker-executable-fdroidserver:master -c \
  '. /etc/profile.d/bsenv.sh; mkdir -p /work && cp -r /meta /work/metadata && cd /work && ${fdroidserver}/fdroid scanner -v io.github.mesmerize08.nexusirl && ${fdroidserver}/fdroid build -v --no-tarball --test io.github.mesmerize08.nexusirl'
```

Requirements this relies on: no dependency metadata block in the APK (`dependenciesInfo`), one
release APK with `-PnoAbiSplits`, any JDK 17 or newer, and no `upstream/` submodule needed for the
build. To submit: update the build entry to the latest release tag (v0.3.0 is the first that builds), copy
the file to `metadata/` in a fork of https://gitlab.com/fdroid/fdroiddata, check the categories
against fdroiddata's `config/categories.yml`, and open a merge request.
