# Caixa

Offline-first personal budget ledger. The first release targets Android 16
(API 36) with a signed APK and Linux x86_64 for the desktop CLI.

## Current implementation

- `shared/finance`: platform-neutral money, date, recurring-income, planned-
  expense, and budget rules.
- `shared/transfer`: versioned `.caixa` JSON snapshots and import validation.
- `shared/storage`: SQLDelight schema for the phone and desktop SQLite stores.
- `apps/cli`: Linux-oriented Kotlin/JVM CLI with SQLite persistence, handoff,
  conflict choice, and local backup policy.
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
docker build --tag caixa-build .
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --volume "$PWD:/workspace" \
  caixa-build check :apps:android:assembleDebug
```

The bind mount keeps build outputs on the host. Running with the host user's
UID/GID keeps those files owned by that user. Downloads are cached in the
ignored `.gradle-docker/` directory. The APK is written to
`apps/android/build/outputs/apk/debug/android-debug.apk` and is signed with a
development key, not the permanent release key. Android's development keystore
is retained in the ignored `.android-docker/` directory between container runs.

To inspect the toolchain without compiling the application:

```bash
docker run --rm caixa-build --version
```

Direct `./gradlew check` is also available after exporting the wrapper, but
running outside Docker requires JDK 17 and Android SDK 36 on the host. No
separate system Gradle installation is needed. APK installation and upgrade
checks still need an Android 16+ phone or an emulator.

## Release builds

Release signing is supplied through an uncommitted `keystore.properties` file.
Docker's build context excludes this file and keystores; supply them only to
the running build container. A release build uses `:apps:android:assembleRelease`
with a readable signing configuration and keystore. The `storeFile` path must
refer to the container's keystore path. A local project-root signing file is
visible through the source bind mount; a keystore outside the project needs a
separate read-only mount. Keep the permanent signing key backed up separately.
The release target remains a direct APK. GitHub Actions release automation is
not configured by this Dockerfile.

Package the Linux x86_64 CLI with its JDK 17 runtime using
`:apps:cli:packageLinuxX64` as the container command instead of the Android build
tasks. The generated runtime still requires Linux x86_64 validation.

The Docker `check :apps:android:assembleDebug` flow has completed successfully,
including the configured automated tests and a development APK build. Release
signing, installation and upgrades on Android devices, and the packaged CLI
still require validation before publishing release artifacts.

## CLI examples

```text
./gradlew :apps:cli:run --args='setup --currency BRL'
./gradlew :apps:cli:run --args='income add --name Salary --amount 3000 --from 2026-10'
./gradlew :apps:cli:run --args='expense add --amount 42.90'
./gradlew :apps:cli:run --args='expense plan --amount 1000 --expected-date 2026-10-28'
./gradlew :apps:cli:run --args='expense record --id EXPENSE_ID --amount 950'
./gradlew :apps:cli:run --args='expense reopen --id EXPENSE_ID --amount 1000 --expected-date 2026-11-02'
./gradlew :apps:cli:run --args='budget show --month 2026-10'
./gradlew :apps:cli:run --args='transfer export --file ./ledger.caixa'
./gradlew :apps:cli:run --args='backup list'
```

Income recurrence is resolved when a month is queried. The CLI does not create
rows merely because a month passed. A new expense without `--date` uses the
machine's local date; planned-expense recording does the same for its actual
date. Explicit dates survive edits and transfers.

Import conflict choices are explicit: use `--use-import` to keep the incoming
whole ledger, or `--keep-local` to leave the current ledger unchanged. Before
replacement, the CLI creates a recoverable local backup.

`budget show --json` emits exact labeled totals for machine consumers. Reports
remain readable while a device is in handed-off read-only mode. Run all host
tests with `./gradlew check`; Android document-picker and APK checks still need
an Android SDK/device.

Backup restore requires an explicit `backup restore --file PATH --use-backup`
confirmation and leaves the restored ledger in a recovery handoff state.
