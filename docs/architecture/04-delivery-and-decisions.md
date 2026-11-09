# Delivery plan and decisions

The first release follows architecture A: Compose Android, a Kotlin/JVM Linux
CLI/TUI, shared KMP rules, SQLDelight storage, and format-2 snapshot handoff.

## Adopted decisions

| Decision | Position |
| --- | --- |
| Android | API 36 minimum, signed APK distribution |
| CLI | Linux x86_64 package with a bundled JDK 17 |
| Language | English, locale-aware dates and amounts |
| Budget | Calendar month, no carryover; planned income resolves automatically |
| Expenses | Dated variable entries plus monthly recurring schedules |
| Credit | Counts against the budget immediately; default payment method |
| Sync | User-carried whole-ledger files, read-only handoff, explicit conflict choice |
| Format | Plaintext `.cofrinho` JSON format 2; no password in v1 |
| Compatibility | Schema generation 3 and format 2 only; old data is rejected |
| iOS | Shared-core path reserved; no iOS app in the first release |

## Implementation slices

1. Shared finance rules resolve recurring income and expense schedules, variable
   spending, effective-month changes, one-month overrides, zero skips, and stops.
2. Fresh generation-3 SQLite storage persists the new collections and rejects
   legacy, unversioned, malformed, or newer databases without writing to them.
3. Format-2 transfer validation carries recurring schedules and dated expenses;
   handoff, receipts, backups, conflict choices, and repeated-import protection
   remain transactional.
4. CLI commands expose `expense` for variable entries and `recurring` for
   add/list/change/override/clear-override/archive. JSON budget reports use
   `report_version: 2` with income, recurring, variable, used, available,
   credit, debit, and status.
5. Android and TUI expose the same overview, variable-expense, recurring,
   recurring-income, and transfer behavior. Amount fields enforce numeric money
   syntax and new expenses default to local today and credit.
6. TUI navigation uses Left/Right and `h`/`l` for months, `j`/`k` with
   Up/Down for lists and forms, a bounded expense viewport, and buffered
   width-safe frames. Small terminals show a resize warning without drawing a
   hidden form or accepting a save.
7. Docker and GitHub Actions build checks, the Android APK, the bundled Linux
   CLI, PTY smoke checks, and preview tests. Release tags and signing keys stay
   outside feature implementation.

## Required proof

The shared tests cover register-once recurrence, effective boundaries, overrides
and skips, stops, variable date/default/payment behavior, exact arithmetic,
zero budgets, overspending, failed writes, and format-2 round trips. CLI tests
cover old-database rejection, lock/handoff behavior, recurring commands, and
TUI setup, numeric filtering, month navigation, scrolling, and read-only mode.
Python script tests and the browser preview test run without Java:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
node --test docs/design/mobile-preview.test.cjs
```

The full gate, when Docker access and the toolchain are available, is:

```bash
docker build --tag cofrinho-build .
docker run --rm --user "$(id -u):$(id -g)" --volume "$PWD:/workspace" \
  cofrinho-build check :apps:android:assembleDebug :apps:cli:packageLinuxX64
```

Android device/emulator checks remain separate from host compilation. Do not
claim the full gate passed when Docker, JDK, SDK, or an Android device is
unavailable; record the unavailable proof instead.

## Recovery and release boundary

Before imports, restores, or replacements, create a recoverable backup. A
backup failure prevents destructive replacement; a failed routine daily backup
does not roll back an already-saved financial edit and offers retry. Keep
conflict copies pinned and never restore stale handoff ownership silently.

Do not add bank integrations, hosted synchronization, automatic record merging,
categories, installments, or generated monthly transaction rows in v1.
