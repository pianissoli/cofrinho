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

## Toolchain

Install JDK 17, Android SDK platform 36, and Gradle. Then run:

```text
./gradlew check
```

Build the unsigned development APK with `./gradlew :apps:android:assembleDebug`.
Release signing is supplied through a local `keystore.properties` file and is
never committed. The release target remains a direct APK; no store publishing
configuration is included.

Package the Linux x86_64 CLI with its JDK 17 runtime using
`./gradlew :apps:cli:packageLinuxX64` on a Linux build host.

The current environment used to create this project has no JDK, Kotlin compiler,
Gradle installation, or Android SDK, so those commands could not run here.

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
