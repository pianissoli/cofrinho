# Delivery plan and open decisions

This delivery order uses adopted architecture A and snapshot handoff.

## Decisions before implementation

| Decision | Current position | Change the design when |
| --- | --- | --- |
| Stack | Adopted: Compose, KMP core, Kotlin/JVM CLI, SQLDelight | Shared mobile UI or another ecosystem is preferred |
| Android minimum | Adopted: Android 16, API 36 | Supporting older Android versions is requested |
| Distribution | Adopted: signed APK for direct installation | Store distribution is requested |
| Launch language | Confirmed: English with locale-aware date/amount formatting | Additional translations are requested |
| iOS | Required next-platform path; app deferred beyond version one | iOS delivery begins |
| Conflict handling | Adopted: explicit whole-ledger choice, both versions backed up; no automatic merge | Record-by-record reconciliation becomes necessary |
| Device editing | Adopted: read-only handoff; explicit Resume editing here action | Users prefer unrestricted editing with conflict warnings on import |
| Budget period | Adopted: calendar month, no carryover | Weekly periods or rollover are required |
| Budget source | Confirmed: recurring planned income with optional monthly overrides | Only received income should be spendable |
| Income recurrence | Adopted: register once; resolve each month automatically from effective schedules | Recurrence periods beyond monthly are required |
| Expense date | Adopted: local day of insertion by default; preserve deliberate overrides | Date-entry behavior changes |
| Credit behavior | Confirmed: count purchases immediately | Statements, debt, interest, or installments must be tracked |
| Currency | Adopted: one setup-confirmed two-decimal currency per ledger | Multi-currency or other scales are required |
| Export privacy | Confirmed: plaintext, no password for now | Password-protected transfer is required |
| Planned expenses | Adopted: reserve budget until recorded or cancelled; required lifecycle tests | Forecasting semantics change |
| Desktop support | Confirmed: Linux x86_64 CLI with bundled JVM | Distribution expands to more targets |
| File compatibility | Adopted: independent file/schema versions; reject unsupported versions safely | Compatibility requirements expand |
| Backup details | Adopted: all timing, storage, retention, system-backup, and recovery defaults in the transfer document | Backup requirements change |

The four original questions were answered on 2026-10-21. Planned-expense
reservations were adopted on 2026-10-26, including their test coverage.
Recurring income was adopted on 2026-10-30. Product defaults, stack, Android 16
minimum, APK distribution, file compatibility, and English launch language
were adopted on 2026-10-31. The detailed backup defaults were also adopted on
2026-10-31. Linux x86_64 was confirmed as the initial CLI target on the same date,
with a bundled JVM. No product or platform decisions block first-release work.
Routine dependency versions, module wiring, signing setup, and test tooling can
be resolved during implementation.

## Slice 1: one financial path on both interfaces

Create the minimum modules from architecture A, using KMP common source sets
for financial, storage, and transfer code and Android/JVM first-release targets.
Check that shared dependencies support iOS Kotlin/Native before adopting them;
keep platform APIs in their application entry points. Define a ledger currency, one
income source with a recurring monthly amount, and an expense. Read the same summary
through Android and the CLI. Inject persistence and external dependencies at
entry points. Keep public function documentation and named fakes consistent
with the project's AGENTS.md.

Acceptance:

- No operation requires network connectivity.
- Saving an expense defaults to credit and updates the selected month exactly
  once; debit is an explicit alternative.
- Restarting each application preserves its ledger.
- Given equivalent records, both applications display identical financial totals.
- Failed writes leave the form recoverable and the stored summary unchanged.
- New expenses default to local today on every fresh form, even from an older
  report month. Untouched defaults refresh at save after midnight; explicitly
  chosen dates survive. Plan recording defaults its actual date to today, and
  CLI entry without `--date` behaves the same. Edits and imports retain dates.

## Slice 2: complete the local product

Add expense history, editing and deletion, effective-month income changes,
source stopping, monthly overrides, planned expenses, month navigation, progress
and daily spending charts. Give the CLI
equivalent commands and terminal summaries. Keep the quick-entry flow short
and make chart values readable without relying on color.

Acceptance:

- Moving or deleting an expense updates the affected months.
- Registering recurring income once defines subsequent months offline, including
  months when the application was not opened. One-month overrides leave other
  months unchanged; recurring changes and stops preserve earlier budgets.
- Zero budgets and overspending have defined, accessible presentations.
- Amount parsing is exact and rejects invalid precision or overflow.

Include planning, recording, rescheduling, and cancellation here. Verify that reservations reduce available
budget, recording releases the reservation without double counting, and all
states survive desktop/phone transfer.

### Required planned-expense test suite

These are mandatory implementation cases. The shared financial and transfer
test fixtures cover the core lifecycle; persistence and Android boundary tests
remain part of the host/device verification gate.
Shared financial cases belong in KMP `commonTest`; SQLite, CLI, and Android
checks exercise the same behavior through their actual boundaries.

Each case starts independently with an October 2026 budget of 3,000.00 and
recorded spending of 500.00. Transition cases first create a 1,000.00 plan due
on October 28. Use a named fake clock fixed at October 26 unless a case advances
it. Amounts below are display values; assertions use exact integer minor units.

| Case | Required result |
| --- | --- |
| Create plan | Spent 500.00, reserved 1,000.00, available 1,500.00; no recorded purchase is created |
| Change planned amount to 1,200.00 | Spent stays 500.00; reserved becomes 1,200.00 and available 1,300.00 |
| Record at expected amount | Same ID becomes RECORDED; spent 1,500.00, reserved zero, available 1,500.00 |
| Record at 950.00 or 1,100.00 | Available becomes 1,550.00 or 1,400.00 respectively; estimated reservation is fully released |
| Retry recording | No duplicate row or second budget charge; confirmed amount/date remain unchanged |
| Cancel plan | Spent stays 500.00; reserved becomes zero and available 2,500.00; retain CANCELLED state |
| Advance clock past due date | Plan stays PLANNED, visible as overdue and reserved in October; no automatic spending |
| Reschedule to November | Release October reservation and reserve November; recorded spending stays unchanged |
| Purchase occurs in November | Release October reservation; record actual amount in November only, preserving ID and expected date |
| Reopen a recorded plan | Require explicit choice and confirmed expected amount/date; transition back without counting both reservation and spending |
| Remove recorded expense without reopening | Remove its spending without recreating a reservation |
| Overcommit with a 3,000.00 plan | Available is -500.00; charts and text show overcommitment rather than hiding it |
| Zero-budget month | Display reserved and negative available amounts without dividing by zero |
| Payment method | New plan defaults to credit; explicit debit survives recording and transfer; both consume budget identically |
| Invalid amount, date, status or date/state combination | Reject with expected shape; totals and stored records stay unchanged |
| Fail during recording transaction | Roll back amount, dates, status, and local financial revision together; original reservation remains |

SQLite integration tests must verify state/date constraints and restart
persistence for every status. Editing a plan or changing its status must advance
the financial revision used by transfer guards in the same transaction.

Android and CLI checks cover creating, editing, recording, rescheduling,
cancelling, reopening, and displaying plans. Assert identical totals, accessible
spent/reserved/available labels, and recovery of input after a failed save.

### Required recurring-income and date cases

| Case | Required result |
| --- | --- |
| Register 2,500.00 from October | October, November, and later budgets include it once; September excludes it |
| Skip app use until January | January resolves the correct budget with no manual copy, job, or duplicate rows |
| Change amount to 2,700.00 from December | October/November stay 2,500.00; December onward use 2,700.00 |
| Override November to 2,400.00 | Only November changes; override replaces rather than adds to recurrence |
| Skip November with zero | November contributes zero; recurrence resumes automatically in December |
| Remove November override | November returns to its applicable scheduled amount |
| Stop from December | December onward contribute zero, including future overrides; earlier budgets remain |
| Restart, repeated queries, and cross-device round trip | Identical budgets; navigation creates no financial writes or duplicated income |
| Malformed or duplicate schedule/override | Reject atomically; valid history and financial revision remain consistent |
| Open fresh expense from a historical month | Actual date is local today, not the selected month or a fixed sample date |
| Open before/after midnight and in timezones on both sides of UTC | Local calendar date is correct and refreshed for every opening |
| Save an untouched default after midnight | Date becomes the insertion day's local date |
| Choose a backdated expense deliberately | Preserve that choice; edits and imports never reset stored dates to today |
| Record plan with a different expected date | Actual date defaults to today; expected date stays unchanged |
| CLI omits `--date` | Use local today; an explicit `--date` overrides the default |

The HTML preview has a small executable test command for its date defaults:
`node --test docs/design/mobile-preview.test.cjs`. This does not replace the
future Kotlin financial, persistence, or mobile tests.

## Slice 3: complete the round trip

Implement portable-format validation and snapshot handoff once in shared code.
Connect Android document-picker access and desktop file access. Add import
preview, receipts, backup, retry, and explicit recovery actions.

Acceptance:

- Phone expense and income records survive export, desktop import, desktop
  edits, export back, and phone import with their identities intact.
- A credit/debit choice, purchase date, income schedule/override, and exact amount
  survive a round trip without reinterpretation.
- PLANNED, RECORDED, and CANCELLED expenses preserve IDs, amounts, dates, and
  payment methods through Android-to-CLI-to-Android transfer. Assert identical
  spent, reserved, and available totals before and after each import.
- Duplicate imports never recreate reservations or charges. Conflicting plan
  edits or status changes require the same explicit choice as other financial
  edits; backups preserve both versions. Invalid imported state/date combinations
  leave the entire working ledger unchanged.
- Reimporting a known checkpoint is a no-op; a modified payload reusing its
  checkpoint ID is rejected.
- Divergent edits, stale exports, missing parents, and wrong ledgers cannot
  silently replace the working ledger.
- Valid conflicting versions show a warning and an explicit user decision;
  both originals remain recoverable after the selection.
- Process death during staging, failed file writes, malformed imports, and
  SQLite transaction failures leave a recoverable ledger or retryable handoff.
- A consistent backup can actually be restored.
- Recurring schedules, one-month overrides, skipped months, and effective stops
  preserve historical and future budgets through the complete device round trip.

## Slice 4: release readiness

Package the CLI for Linux x86_64 with a bundled JVM and implement the adopted Android backup and
database/file-format compatibility policies. Android's
automatic app backup must not silently restore device ownership metadata as a
second active installation; exclude local handoff state or restore into an
explicit recovery flow. Keep financial logs free of ledger content.

Android version support is Android 16 (API 36) and later. Verify installation,
offline use, and upgrades using a signed APK with the same application ID and
signing key. Do not require users to uninstall to update their ledger app.
Verify CLI installation, startup, SQLite access, and portable-file round trips
on Linux x86_64 without a separately installed Java runtime.

Implement the adopted backup retention and OS backup rules from the transfer
document. The implementation
must prove durable creation, safe restore, failure handling, retention, and
protection of pinned conflict copies. Recovery cannot silently restore stale
editing ownership, and backup creation cannot alter transfer lineage.

Required backup acceptance cases:

- Successful pre-operation backups precede imports, migrations, replacements,
  and restores. A required backup failure prevents destructive changes.
- The first financial edit on a local day creates one routine daily backup;
  retries and additional edits that day do not create duplicate daily backups.
- A failed daily backup preserves the saved expense and offers retry.
- Completed backups retain the latest 10 ordinary copies. A failed new backup
  never prunes old copies; pinned conflict versions and manual exports survive.
- Snapshot creation preserves committed WAL records without changing financial
  revisions, handoff state, or transfer checkpoint identities.
- Restore preview preserves the active ledger until explicitly accepted and
  never silently reactivates stale editing ownership.
- Android cloud/device-transfer exclusions are verified on supported devices;
  ordinary user-selected external exports remain available.

Publish one application test command. For the adopted stack, use a root
`./gradlew check` task wired to shared finance tests, JVM CLI tests, format
fixtures, migration checks, and Android host-side unit tests. Document any
separate device/emulator requirement for Android lifecycle and file-picker
checks; the host test command alone does not prove those interactions.

Use named fake clocks, UUID sources, file stores, and failing I/O adapters.
Test SQLite queries and migrations against temporary real SQLite databases;
mocking the database cannot prove SQL constraints or migration behavior.

The smallest useful shared fixture set covers monthly budget arithmetic,
planned-expense lifecycle, credit/debit defaults, month boundaries, zero budget, overspending, duplicate
imports, incompatible histories, and export/import round trips. Include failure
injection around the persistence boundaries rather than testing only happy
paths. For option B, both language implementations consume these fixtures.

## Follow-up: iOS

After the first release, add Kotlin/Native iOS targets and a SwiftUI application
that consumes the shared core. Supply native SQLite and document-access adapters
and verify Swift interop, lifecycle, migrations, and Android/CLI/iOS round trips.
Reuse shared financial behavior fixtures for iOS target tests. Apple builds
and platform validation require macOS/Xcode tooling. A focused portability
compile check can happen earlier without implementing or shipping an iOS app.

## Boundary for the first release

Stop once Android and the CLI provide the requested local financial features
and the selected transfer workflow passes the round-trip and recovery checks.
Bank integrations, a hosted synchronization service, multiple users, automatic
categorization, and credit-card accounting are separate product decisions.
