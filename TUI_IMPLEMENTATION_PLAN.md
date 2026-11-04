# Cofrinho default TUI implementation plan

Status: product defaults accepted by the user on 2026-11-03. The four
increments below are implemented in this checkout; the remaining release gate
is the packaged Linux pseudo-terminal run described in Increment 4.

## Goal and stopping condition

Running `cofrinho` in an interactive terminal opens the TUI by default. The
user can open an existing ledger or complete first-run setup, inspect the
monthly budget, record an expense using the keyboard, see refreshed totals,
and quit with the terminal restored.

Keep the existing CLI commands available for automation and for operations not
yet exposed in the TUI. Use the same SQLite ledger, financial rules, backups,
and transfer safeguards. Do not introduce a separate TUI database.

Stop after this milestone and its release checks pass. Income editing, planned
expense editing, transfer/import screens, backup management screens, expense
editing/deletion, and payday-date support are later work. Existing CLI access
to those operations remains available. Existing plans still contribute to the
overview's reserved amount and expense list.

## Accepted decisions

| Topic | Required behavior |
| --- | --- |
| Toolkit | Lanterna in actual terminal mode, usable from Kotlin/JVM 17; no Swing/GUI fallback |
| Launch | `cofrinho` opens the TUI; no `tui` subcommand is required |
| Alternate database | `cofrinho --db PATH` opens that ledger in the TUI; existing explicit commands retain `--db` support |
| Help | `cofrinho --help` and `cofrinho -h` print help and exit successfully without a terminal or database writes |
| Noninteractive use | A default TUI launch without a usable terminal exits with a clear error and nonzero status; explain how to use `--help` or an explicit command |
| Language/theme | English, simple dark theme, readable labels; communicate state through text as well as color |
| Overview | Current local month initially; planned income, spent, reserved, remaining/available, simple bars, and the selected month's expenses |
| Navigation | Arrows navigate lists, Tab/Shift-Tab move focus, Enter confirms the focused action, Escape cancels, and `q` quits from the overview |
| Shortcuts | `n` opens the expense form from the overview; Page Up/Page Down select the previous/next month |
| Text entry | `q` and `n` remain normal text inside fields; show available shortcuts in a footer |
| Minimum size | Target 80 columns by 24 rows; smaller terminals show a resize message without losing form contents |
| Save | Keep invalid fields visible with errors; after success, show the expense's month, refreshed totals, and a confirmation |
| Unsaved input | Confirm before discarding a form changed by the user; untouched defaults alone do not make it dirty |
| Concurrency | One cooperating desktop ledger session at a time; explain when another process holds it instead of overwriting unseen changes |

Dates default to the local day of insertion, including a form left open across
midnight. Credit is the default payment method and immediately consumes the
budget. Recurring planned monthly income defines the budget irrespective of
payday; no income confirmation or payment-day model is added here.

Use the ledger's existing currency and scale. For first-run setup, propose BRL
and scale 2 and require confirmation before creating the ledger. Preserve
valid existing ledgers, including any scale already supported by the CLI.

## Repository orientation and boundaries

Read [AGENTS.md](AGENTS.md) before editing. Inspect the current checkout rather
than assuming this document contains a complete source listing.

- [Main.kt](apps/cli/src/main/kotlin/cofrinho/cli/Main.kt): process entry point,
  currently routes all arguments to `CofrinhoCli`.
- [CofrinhoCli.kt](apps/cli/src/main/kotlin/cofrinho/cli/CofrinhoCli.kt): command
  parsing mixed with setup, financial operations, backups, and handoff checks.
- [JdbcLedgerStore.kt](apps/cli/src/main/kotlin/cofrinho/cli/JdbcLedgerStore.kt):
  SQLite adapter; opening it can create a schema or run migration work.
- [BackupManager.kt](apps/cli/src/main/kotlin/cofrinho/cli/BackupManager.kt):
  existing backup policy and conflict preservation.
- [LedgerService.kt](shared/finance/src/commonMain/kotlin/cofrinho/finance/LedgerService.kt):
  exact money/date validation, expense creation, and budget calculations.
- [Domain.kt](shared/finance/src/commonMain/kotlin/cofrinho/finance/Domain.kt):
  ledger, transfer state, clock, identifiers, and storage contracts.
- [CLI build](apps/cli/build.gradle.kts): application dependencies, JRE modules,
  Linux packaging, and the launcher that selects the bundled runtime.
- [Release workflow](.github/workflows/android-release.yml): shared Android/CLI
  checks, packaging, smoke tests, APK signing, and one release publisher.
- [Product rules](docs/architecture/01-product-and-domain.md) and
  [transfer rules](docs/architecture/03-transfer-and-sync.md): adopted behavior.

Keep the implementation in `apps/cli`, with focused terminal UI modules, for
example under `cofrinho/cli/tui/`. Keep terminal-library types out of shared
finance, storage, and transfer modules. Wrap terminal I/O behind the thin
project-owned seam required by AGENTS.md so tests can use named fakes.

Reuse `LedgerService`, `MoneyRules`, `LedgerClock`, and `IdentifierSource`.
Extract desktop operations only where both interfaces need them; do not build
a general command bus, plugin framework, or a second financial model. The TUI
must not invoke CLI subprocesses or parse printed command output to operate.
No database-schema or portable-file-format change is needed for this milestone.

## Increment 1: prove terminal and packaged-runtime support

1. Select and pin an open-source Lanterna version compatible with JVM 17.
   Check its license and include required distribution notices. Prove its
   actual terminal path works; do not add desktop-window fallback behavior.
2. Add entry-point routing: no financial command opens a minimal TUI, while
   explicit commands continue through the command interface. Parse `--db` and
   help before opening SQLite. Unknown/malformed options must not open a TUI
   or mutate a database accidentally.
3. Implement a minimal screen and quit action. Detect an unusable terminal
   before opening the ledger. Restore terminal mode, cursor, and screen in
   `finally`/resource cleanup paths, including exceptions and interruption.
   Do not exit the JVM while terminal cleanup is still pending.
4. Resolve the existing SLF4J missing-binding warning using the actual runtime
   dependency graph. For this milestone, an explicit compatible no-op binding
   for dependency logging is sufficient if no project backend already exists.
   Do not suppress application errors. Any added diagnostic logs must follow
   AGENTS.md's structured-JSON rule and stay off the interactive screen.
5. Package with the existing `packageLinuxX64` task. Inspect and verify the
   required `jlink` modules; add only modules needed by the selected toolkit.
   Preserve the bundled-runtime launcher, architecture checks, and legal files.
6. Update the current archive smoke test's bare `"$cli"` invocation to
   `"$cli" --help` immediately. The old invocation will no longer be a
   noninteractive help check and could fail or hang CI after this change.

Gate: the packaged command opens and quits under a pseudo-terminal with the
bundled JRE, no system Java, and an invalid ambient `JAVA_HOME`. Help and explicit
commands work without a TTY. Startup and forced-error tests restore the terminal
and exit within a bounded timeout. No real user ledger is touched by this gate.

## Increment 2: share desktop operations and preserve safety

1. Extract setup, budget reading, selected-month expense reading, and recorded
   expense creation into desktop actions reusable by CLI and TUI. Keep command
   parsing and presentation in their respective entry points. Preserve the
   output contract of existing explicit commands, particularly `--json`.
2. Make the desktop ledger session own its database connection/resources,
   advisory lock, financial service, and backup adapter. Inject dependencies
   rather than constructing global clock, identifiers, or terminal instances.
3. Acquire an OS-backed advisory lock for the selected database before opening
   the driver or performing migration work. Normalize its identity so two paths
   to the same database do not create independent locks. All cooperating CLI
   commands that open the store must participate: current reads can also run
   schema initialization/migrations. Help does not acquire a ledger lock.
4. Hold the lock for the TUI session or explicit command lifetime. Refuse a
   second session with a clear message. Release resources and locks on every
   exit path. An OS lock must release on process death; do not use a persistent
   boolean as the authority or unlink a live lock file and create an inode race.
5. Preserve `requireEditable` behavior in the shared desktop action layer.
   Handed-off ledgers remain viewable; attempted writes fail even if a UI button
   is bypassed. The TUI must not silently resume editing or change checkpoints.
6. Reuse backup timing and failure behavior. Do not duplicate an expense when a
   successful financial write is followed by a backup failure: distinguish the
   saved result from the backup warning so a retry cannot save it again.

Gate: existing financial, storage, and transfer tests pass. CLI and TUI actions
produce identical ledger changes, revisions, totals, and backup/handoff effects.
Add tests for a second process/session, lock release after failure, attempted
edits after handoff, and backup-failure reporting after a successful save.

## Increment 3: setup, overview, and add-expense flow

### Setup and existing ledgers

An absent or explicitly detected uninitialized ledger opens guided setup with
currency confirmation and cancellation. Create the ledger only after explicit
confirmation, using the same rules as CLI setup. Cancellation creates no
financial records. Do not treat an arbitrary read, permission, or corruption
error as permission to replace an existing database; show a recoverable error.

Opening a valid existing ledger shows its current local month without changing
financial rows, revision, checkpoints, or handoff state. Keep first-run setup
distinct from import/restore, which are outside the first TUI milestone.

### Overview

Show planned income, spent, reserved, remaining, available, clear zero-budget
and overcommitted states, simple progress bars, and the selected month's expense
list. Use shared totals; do not recompute the budget in UI widgets. Keep recorded
spending and reservations distinct. Bars must handle zero and overspent budgets
without division errors or misleading labels.

Start on the current month. Use the shared date rules for month navigation.
Display each expense's relevant date, status, amount, payment method, and
description. Select rows with arrows and keep the selected row visible. Do not
add placeholder edit/delete actions for features not implemented in this slice.

A new ledger may have no income sources. Show an honest zero budget and allow
expense entry under existing overspending rules; do not invent sample income or
automatically create a source. Recurring-income editing remains in the CLI for
this milestone.

### Expense form

Focus amount first. Include amount, optional description, date, payment method,
Save, and Cancel. Use the existing exact amount parser and ISO date rules; do
not use floating-point money. Default to credit and local today even when the
overview is displaying a historical month.

Track whether the user deliberately edited the date. If untouched, resolve it
again on successful insertion so an open form crossing midnight uses the new
local day. Preserve an explicitly chosen date. Midnight refresh alone must not
make the form dirty or discard other entered fields.

Validation errors retain the entered values and focus the relevant field.
Escape returns immediately from an untouched form; otherwise ask before
discarding it. Apply the same changed-form policy to normal quit requests.
EOF or an unrecoverable terminal failure must close safely without saving a
partial form or waiting indefinitely for an impossible confirmation.

On Save, validate and execute one shared add-expense action. Prevent duplicate
submission. Refresh from the saved ledger, switch to the expense's month, and
show the updated totals and a visible confirmation. Do not claim a save failed
when only a subsequent backup failed. Disable editing in handed-off mode and
show the reason in text while continuing to allow viewing and quitting.

Gate: using only the keyboard, complete setup or open an existing ledger, add
an expense, see correct totals, cancel a changed form without unexpected writes,
and quit cleanly. Resizing below 80x24 and back preserves state and focus.

## Increment 4: prove the release artifact

1. Test screen/application state and actions with named fake terminal, clock,
   identifiers, store, and backup dependencies. Keep tests deterministic and
   independent of the user's real terminal, locale, date, and financial files.
2. Cover default routing, `--db`-only routing, explicit commands, help, absent
   terminal, setup confirmation/cancellation, corrupted databases, validation,
   dirty-form cancellation, date rollover, explicit dates, credit/debit,
   zero/overspent budgets, handoff, duplicate submission, and resizing.
3. Keep the existing CLI archive smoke test's financial/export/import sequence.
   Its startup probe must now be `--help`. This verifies the automation interface
   remains usable without a terminal or system Java.
4. Add a separate pseudo-terminal smoke test for the actual extracted archive,
   with a usable `TERM`, 80x24 size, bounded waits, and explicit temporary `--db`
   paths. Test normal exit and at least one create-expense flow against fixtures.
   Verify the saved result through an explicit CLI query after the TUI exits.
5. Use real PTY allocation, such as Python's standard `pty` facilities on the
   GitHub runner, to drive the no-system-Java container. A pipe supplying keys
   does not substitute for a terminal. If using `docker run -it`, its caller must
   actually have a PTY; plain hosted-runner stdin does not provide one.
6. Test the archive extracted under a path containing spaces, with its bundled
   JRE and an invalid external `JAVA_HOME`. Require clean exit, terminal cleanup,
   and no raw SLF4J binding warnings. Retain the Linux x86_64 target.
7. Keep one existing GitHub publisher for APK, CLI archive, and both checksums.
   CLI/TUI verification must succeed before signing/publication proceeds. Keep
   Android signing secrets scoped to the APK signing step.

Wire new host-side regression tests into the existing `check`/release checks
rather than creating a parallel undocumented verification process. Keep the
bump-script tests. Update CLI help, README, and release instructions to explain
default interactive startup and noninteractive command/help usage.

The Docker build/check/CLI packaging commands are:

```bash
docker build --tag cofrinho-build .
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --volume "$PWD:/workspace" \
  cofrinho-build check :apps:cli:packageLinuxX64
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Also execute the existing CLI archive smoke test and the new PTY smoke test.
Do not present syntax checks or fake-process tests as proof that the real TUI
runs with the packaged JRE. If environment restrictions block Docker or terminal
execution, report the exact unverified gate and the command needed to run it.

## Final handoff

Report implemented increments, changed entry points, reused desktop actions,
dependency/JRE changes, test results, and any blocked checks. Confirm that the
financial schema and exchange format are unchanged and that tests used only
temporary ledgers. Preserve unrelated user changes.

Do not edit the user's installed application or real database as part of
implementation. Do not run `scripts/bump.py`, push commits/tags, or publish a
release unless separately instructed; this plan authorizes the described
implementation and verification, not an automatic production release.
