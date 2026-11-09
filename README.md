# Cofrinho

Offline-first personal budget ledger. The first release targets Android 16
(API 36) with a signed APK and Linux x86_64 for the desktop CLI.

## Current implementation

- `shared/finance`: platform-neutral money, date, recurring-income,
  recurring-expense, variable-spending, and budget rules.
- `shared/transfer`: format-2 `.cofrinho` JSON snapshots and import validation.
- `shared/storage`: SQLDelight schema for the phone and desktop SQLite stores.
- `apps/cli`: Linux-oriented Kotlin/JVM CLI with SQLite persistence, handoff,
  conflict choice, local backup policy, and a keyboard-driven Lanterna TUI
  opened by the bare `cofrinho` command.
- `apps/android`: Android 16 Compose application shell for the adopted dark
  interface and offline flow.
- `docs/design/mobile-preview.html`: clickable interface preview.

## Build with Docker

The host needs Docker and a checkout of this repository. Java, Gradle, the
Android SDK, and Android Studio do not need to be installed on the host.
The Linux x86_64 build uses JDK 17, Gradle 8.11.1, Android SDK
platform 36, and SDK Build Tools 35.0.0, matching Android Gradle Plugin 8.10.1.
These versions follow [Android's compatibility table](https://developer.android.com/build/releases/agp-8-10-0-release-notes).
Gradle distribution and wrapper JAR checksums are pinned to the
[official Gradle checksum reference](https://gradle.org/release-checksums/).
Building the image accepts Google's Android SDK licenses. The first build
downloads tools and dependencies; the installed application works offline.

Generate the official Gradle Wrapper once, from the project directory:

```bash
docker build --target wrapper-files --output type=local,dest=. .
```

This exports `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and
`gradle/wrapper/gradle-wrapper.properties`. Commit all four files. The temporary
`gradlew` bootstrap message is replaced by Gradle's official generated launcher.
The wrapper pins Gradle and verifies its downloaded distribution checksum; it
is part of the source repository, rather than a host-installed program.
The Dockerfile generates it in an isolated empty build, so application compile
errors cannot prevent wrapper generation. The builder also keeps this wrapper
inside the image, available when the source directory is mounted over it.

Build the toolchain image, then run tests and create a development APK:

```bash
docker build --tag cofrinho-build .
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --volume "$PWD:/workspace" \
  cofrinho-build check :apps:android:assembleDebug
```

The bind mount keeps build outputs on the host. Running with the host user's
UID/GID keeps those files owned by that user. Downloads are cached in the
ignored `.gradle-docker/` directory. The APK is written to
`apps/android/build/outputs/apk/debug/cofrinho-debug.apk` and is signed with a
development key, not the permanent release key. Android's development keystore
is retained in the ignored `.android-docker/` directory between container runs.

To inspect the toolchain without compiling the application:

```bash
docker run --rm cofrinho-build --version
```

Direct `./gradlew check` is also available after exporting the wrapper, but
running outside Docker requires JDK 17 and Android SDK 36 on the host. No
separate system Gradle installation is needed. APK installation and upgrade
checks still need an Android 16+ phone or an emulator.

## Release builds

To download signed APKs and the Linux CLI from GitHub without building locally, follow the
[GitHub Releases setup](docs/release/github-releases.md). Configure the four
repository signing secrets once, then push a version tag such as `v0.1.0`.
The Android release workflow runs checks, packages and smoke-tests the CLI,
signs the APK with the existing key, verifies its signature, and publishes all
artifacts with SHA-256 checksums.
The non-publishing `CLI TUI checks` workflow runs the same CLI/TUI validation
on master pushes and pull requests.

To publish the next patch release from a clean `master` checkout, run
`python3 scripts/bump.py`. The script increments the project version, Android
`versionName`, and `versionCode`, commits the changes, pushes `master`, and then
creates and pushes the annotated version tag. It needs Python 3 and Git, and
checks remote history and tag conflicts before editing files. Run its tests
with `python3 -m unittest discover -s scripts -p 'test_*.py'`.

Use a PKCS#12 (`.p12`) signing keystore and `storeType=PKCS12` in the local
signing configuration. The [Android signing guide](docs/release/android-signing.md)
explains how to convert an existing JKS without changing the signing identity,
verify the certificate, and update the GitHub Actions secrets.

Release signing is supplied through an uncommitted `keystore.properties` file.
Docker's build context excludes this file and keystores; supply them only to
the running build container. A release build uses `:apps:android:assembleRelease`
with a readable signing configuration and keystore. The `storeFile` path must
refer to the container's keystore path. A local project-root signing file is
visible through the source bind mount; a keystore outside the project needs a
separate read-only mount. Keep the permanent signing key backed up separately.
The release target remains a direct APK. GitHub Actions release automation
lives in `.github/workflows/android-release.yml` and uses this Dockerfile.

Package the Linux x86_64 CLI with its JDK 17 runtime using
`:apps:cli:packageLinuxX64` as the container command instead of the Android build
tasks. The generated runtime still requires Linux x86_64 validation.

The Docker flow passed its configured tests and produced a development APK
before the project rename. The GitHub release workflow now rebuilds the image,
runs checks, packages and smoke-tests the CLI, and validates release signing
before publishing artifacts. Release installation and upgrade checks still need
an Android device or emulator.

The release APK is `apps/android/build/outputs/apk/release/cofrinho-release.apk`.
The CLI launcher is named `cofrinho`; `:apps:cli:packageLinuxX64` writes its
self-contained distribution to `apps/cli/build/package/cofrinho-linux-x86_64/`.

This release deliberately invalidates earlier development databases and format-1
exports. Create a new ledger and re-enter its income, recurring expenses, and
variable expenses; old files are rejected without modification. Existing signing
keys must be reused; the signing guide explains how to preserve their identity
while changing file and alias labels.

## CLI examples

Running `cofrinho` with no command opens the interactive terminal interface.
Use `cofrinho --help` for automation-safe help, or use an explicit command as
shown below. The TUI needs a real terminal; pipes and CI jobs should use an
explicit command instead.

```text
cofrinho
cofrinho --db ./ledger.sqlite
cofrinho --help
./gradlew :apps:cli:run --args='setup --currency BRL'
./gradlew :apps:cli:run --args='income add --name Salary --amount 3000 --from 2026-10'
./gradlew :apps:cli:run --args='recurring add --name Rent --amount 1000 --from 2026-10'
./gradlew :apps:cli:run --args='expense add --amount 42.90'
./gradlew :apps:cli:run --args='budget show --month 2026-10'
./gradlew :apps:cli:run --args='transfer export --file ./ledger.cofrinho'
./gradlew :apps:cli:run --args='backup list'
```

Income and recurring-expense schedules resolve when a month is queried. The CLI
does not create rows merely because a month passed. A new variable expense
without `--date` uses the machine's local date. Explicit dates survive edits and
transfers.

Import conflict choices are explicit: use `--use-import` to keep the incoming
whole ledger, or `--keep-local` to leave the current ledger unchanged. Before
replacement, the CLI creates a recoverable local backup.

`budget show --json` emits exact labeled totals for machine consumers. Reports
remain readable while a device is in handed-off read-only mode. Run all host
tests with `./gradlew check`; Android document-picker and APK checks still need
an Android SDK/device.

Backup restore requires an explicit `backup restore --file PATH --use-backup`
confirmation and leaves the restored ledger in a recovery handoff state.
