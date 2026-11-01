# Portable files and bidirectional transfer

File transport and conflict handling are separate concerns. USB, a removable
drive, Bluetooth, or a user-selected document provider can move the same file.
Cofrinho does not require a particular cable, a local server, or a cloud account.

The Android sandbox protects the live database. Export through the system
document picker and import a selected document into private app storage. On
desktop, the CLI imports into its configured SQLite ledger.

## Portable format: a versioned logical snapshot

Adopted on 2026-10-31: versioned logical snapshots are the first-release transport.

Use a single UTF-8 JSON document with the `.cofrinho` extension for version one.
It contains financial records and transfer metadata, not SQLite internals.
Dates and enum values have fixed representations. Encode minor-unit amounts
as decimal strings and validate their signed 64-bit bounds; this prevents
precision loss when other tools parse JSON numbers.

The document contains:

| Field | Meaning |
| --- | --- |
| `format_version` | Portable contract version, independent of database schema |
| `ledger_id` | Stable identity of the financial ledger |
| `checkpoint_id` | UUID identifying this immutable snapshot |
| `parent_checkpoint_id` | Previous accepted/exported checkpoint, null at the start |
| `exported_at` | UTC audit timestamp; never a conflict-resolution clock |
| `currency_code`, `currency_scale` | Ledger currency and minor-unit scale |
| `income_sources` | Full set of source records, including their effective stop months |
| `income_schedules` | Full set of effective-month recurring amounts |
| `income_overrides` | Full set of monthly exceptions, including zero-value skips |
| `expenses` | Full set of PLANNED, RECORDED, and CANCELLED expense records, including expected and actual dates |

Financial record fields follow the domain document. Records retain their UUIDs.
Transfer must not turn a reservation into recorded spending or reopen a
cancelled plan. Validate state/date combinations before importing, and preserve
amounts, payment methods, and optional expected dates on recorded expenses.
Income schedules, overrides, and stop months travel with the ledger. Resolve
budgets identically after import without creating monthly income rows. Reject
duplicate source/start-month schedules and source/month overrides, invalid
months, negative overrides, or nonpositive recurring amounts. Import preserves
stored expense dates; local-today defaults apply only to fresh entries.
Do not export local paths, device credentials, migration tables, or local edit
counters. Preserve the exact staged export bytes for retry and compute a file
digest for receipts. A digest identifies bytes; it does not authenticate a file.

A full snapshot represents the whole ledger. A receiver that accepts it removes
financial records absent from that snapshot. That is safe only after the
handoff checks below. Absence cannot mean deletion in a future merge protocol;
that protocol needs explicit tombstones.

## Design 1: snapshot handoff

Confirmed on 2026-10-21: automatic merging is deferred. Conflicts must produce a
warning and require the user's decision before replacing financial records.
The read-only handoff workflow below was adopted on 2026-10-31.

This is the simplest safe starting workflow when users edit on one device at a
time. Both phone and computer support exactly the same procedure:

1. Export on the active device. The application stages an immutable snapshot
   and enters Handed off state before exposing the file externally.
2. Transfer the file by the user's chosen method.
3. Import on the receiving device, inspect the preview, and continue editing.
4. Export from that device and import back to return editing to the phone.

Handed off state keeps reports readable and blocks financial edits. Re-export
reuses the same staged bytes and checkpoint. A failed or interrupted file copy
offers Retry; it must not produce a different checkpoint behind the user's back.

Persist staging and state transitions so process death cannot reopen editing
after a published handoff. One approach writes a durable app-private payload,
then commits its checkpoint, captured financial revision, and Handed off state
in a SQLite transaction. Check that the captured revision is still current
before committing, and expose the external file only after that commit.
Unreferenced private staging files from a crash can be discarded safely.

Local metadata records the current checkpoint and financial revision at that
checkpoint. Every financial change advances the local revision in the same
transaction as the change. Imported device-local revisions are never trusted.

### Acceptance rules

| Receiver state | Import behavior |
| --- | --- |
| Empty installation | Adopt the validated ledger and establish its checkpoint |
| Same checkpoint already seen | No-op; do not change editing ownership or reapply records |
| Same ledger, no local edits since checkpoint, incoming parent equals current checkpoint | Preview and import atomically; receiver becomes active |
| Local edits since current checkpoint | Refuse automatic replacement; preserve both copies |
| Incoming parent does not equal current checkpoint | Refuse automatic replacement; explain stale, skipped, or branched history |
| Different ledger or currency | Refuse replacement of the current ledger |

Track seen checkpoint IDs and their payload digests. Reusing an ID with
different bytes is an invalid file, not an idempotent replay. Checking only
the current checkpoint would allow replay of an older export accidentally.

A newly imported checkpoint can be exported without additional edits to hand
editing back. Its new checkpoint names the imported one as its parent. Merely
opening the exact same export again does not return editing to its sender.

An export that skipped intermediate checkpoints cannot automatically replace an
older receiver, even if it appears newer by date. Resend and import the missing
handoffs in order. Keep an explicit Restore into a new working copy action for
recovery, with a backup and clear confirmation of which ledger will be opened.
Do not turn an ordinary import into a hidden force-replace operation.

If a transfer was abandoned, Resume editing here is an explicit recovery action
with a warning that any recipient may now have an independent copy. Once either
copy changes, automatic replacement remains blocked by revision/parent checks.
Recovery preserves both ledgers for reconciliation.

These guards support a cooperative handoff; file copies cannot enforce global
ownership across disconnected devices. Opening one export on two computers
can create branches. The system detects incompatible histories when they meet
and must not silently choose one.

### Manual conflict decision

On incompatible histories, show the local and incoming versions, their totals,
and the added, removed, and changed records. Preserve recoverable copies of
both versions before applying a decision. Offer cancellation without mutation.

For version one, the adopted resolution is to choose an authoritative whole
ledger: Keep this device's version or Use the imported version. Explicitly warn
which local-only or imported-only changes that choice excludes. Record the
selection and reset the accepted checkpoint baseline only after an atomic
replacement succeeds. Whole-ledger selection was adopted on 2026-10-31;
choosing individual records would require additional reconciliation rules.

The user's decision can authorize replacing a valid same-ledger snapshot with
incompatible lineage. It cannot override malformed input, a checkpoint ID reused
with different bytes, or a currency mismatch. Never choose by device timestamp.

## Design 2: merge independent offline changes

Deferred alternative. Reconsider this if users need automatic reconciliation
of independent edits instead of selecting a version. Keep the same financial
model, but replace snapshot replacement with a revision-aware merge contract.

- Each financial record has a stable entity ID and immutable revision IDs.
- Revisions record their parent revision, author installation, and change ID.
  Random IDs establish identity; device time does not establish precedence.
- Export retained changes and explicit deletion tombstones, or a complete
  revision graph sufficient to identify common ancestors.
- Replayed change IDs are ignored. New independent records merge automatically.
- An incoming revision that descends from the receiver's revision can advance
  it. Divergent revisions require an explicit resolution.
- Editing versus deleting a record is a conflict. Different IDs that violate
  source/month uniqueness are also conflicts.
- Stage a merge and resolve its conflicts before updating the live ledger.
  Both original candidates remain available; cancelling keeps the live ledger
  unchanged. Never include both alternatives in expense totals.
- A resolution produces a new revision that records both parents. Tombstones
  and ancestors cannot be discarded until a defined acknowledgement/retention
  policy makes that safe for every participating installation.

This needs an installation registry, retained history, conflict UI and CLI
commands, and more compatibility checks. It avoids restricting editing to one
device. Do not use last-modified timestamps as a shortcut: clocks can disagree,
and choosing the latest timestamp can erase a valid financial change.

Do not advertise Design 1 as synchronizing simultaneous edits. Moving to Design
2 requires a portable-format upgrade and a migration plan for existing ledgers.

## Alternative transport: exchange SQLite snapshots

A single `.sqlite` file can be the portable artifact if both applications own
exactly the same schema. It is easy to inspect and avoids a separate logical
serialization step, but couples releases to database schema compatibility.

Never copy only a live SQLite main file: committed rows may still be in its WAL.
Produce a consistent snapshot using the selected platform's supported backup
mechanism, or fully close all connections after a successful checkpoint before
copying. Do not copy journal sidecars piecemeal while writers are active.

Validate an incoming database in isolation, including integrity, foreign keys,
application identity, schema version, and financial constraints. Never execute
SQL or migrations supplied by the file. Import financial records through owned
code so local transfer history and device state cannot be overwritten.

SQLite exchange still needs the same handoff or merge policy. It does not solve
conflicts. Prefer logical snapshots for format evolution and isolation from
framework-specific database metadata. The desktop working store remains SQLite
regardless of the chosen transport.

## Import safety and compatibility

1. Read a bounded-size file into staging. Bound record counts, string lengths,
   nesting, and field sizes; reject malformed or unsupported input with a useful
   error. Treat files as untrusted documents.
2. Validate version, UUIDs, dates, currency, positive amounts, overflow, enum
   values, duplicate identities, schedule/override uniqueness, and references.
3. Convert supported older portable formats using project-owned migrations.
   Reject unsupported newer versions without changing the working database.
4. Display ledger identity, currency, record counts, relevant totals, and the
   target database before the user accepts a replacement or first adoption.
5. Create a consistent recoverable backup of any existing ledger. Recheck
   checkpoint and revision guards inside the import transaction so a concurrent
   CLI command cannot invalidate the earlier preview.
6. Apply all financial records, the receipt, and checkpoint metadata in one
   SQLite transaction. Roll back on any failure. Notify the UI after commit.

Adopted on 2026-10-31: database schema versions and portable format versions
evolve separately. SQL
migrations are packaged with the application. Preserve the old working file
before a migration that requires database replacement, and verify restore
procedures before a release. Do not assume an older app can read a migrated DB.

## Adopted backup policy

Adopted on 2026-10-31. These defaults apply to both Android and the desktop CLI.
Consistent backups before import and schema migration remain part of the
existing safety design.

| Choice | Adopted first-release policy |
| --- | --- |
| Automatic timing | Before import, migration, conflict replacement, and restore; additionally after the first successful financial edit on each local day of use |
| Location | Android app-private storage; desktop per-user application-data backup directory with restrictive permissions |
| Retention | Keep the latest 10 ordinary automatic backups per ledger per installation; prune only after a new backup is safely complete |
| Conflict originals | Pin both conflicting versions until the user explicitly deletes their recovery copies; ordinary retention never removes them |
| Manual files | User-managed exports stay wherever the user saves them; Cofrinho never removes them automatically |
| Device-loss protection | User copies an export to the computer or other external storage; no automatic upload in version one |
| Android system backup | Explicitly exclude the ledger, backups, checkpoint state, and staged exports from OS cloud backup and automatic device transfer; restore through Cofrinho's own reviewed import/recovery flow |
| Recovery | Validate and preview a backup in a separate working copy; require an explicit choice before replacing the active ledger |
| Encryption | App-private backup storage, no additional backup password for version one; portable exports remain plaintext as already agreed |

Local backups protect against bad imports and accidental changes. They are not
a separate-device copy: uninstalling the app or losing the phone can remove
them. The device-loss policy must therefore be explicit in the export/recovery
interface. Keep ordinary exports available for storing a copy outside the app.

An internal backup is a consistent database snapshot for recovery, including
the schema and required local metadata. It must not invoke handoff, create a
transfer checkpoint, or change the financial revision. Never copy only a live
SQLite main file while committed records may still be in its WAL.

Restoring old metadata must not silently reactivate an earlier editor. Open the
backup in a recovery state, then let the user explicitly resume locally or
accept a returned ledger. Files already shared may have newer independent
versions. Pending export bytes and checkpoint identities must remain coherent.

If a required pre-operation backup fails, stop the destructive operation and
keep the working ledger unchanged. If a daily backup fails after an expense
was saved, keep the saved expense and report the backup failure with a retry
action. Never report a backup as complete until its bytes are durable. Do not
delete a previous recovery copy merely to claim that a new backup succeeded.

These defaults avoid a background backup service. Daily backup runs during
application use after a financial edit. Schema upgrades must first establish
that a recoverable pre-upgrade snapshot can be created; do not recreate the
database on failure. Verify Android cloud/device-transfer exclusions on the
supported Android version before describing the policy as enforced.

## Privacy and operational boundaries

The first format described here is plaintext. It contains readable amounts,
income names, and descriptions. Label that clearly in the export screen and
CLI. Files sent to a user-selected cloud document provider can leave the device;
Cofrinho itself should not upload them automatically.

Confirmed on 2026-10-21: no password or export encryption for now. Android
app-private storage protects the live phone database from ordinary other apps;
desktop file permissions and backups need equal care. If encryption is added
later, use a maintained authenticated-encryption format with a suitable password
KDF that both clients support.

The CLI opens its SQLite file through normal SQLite locking and uses bounded
busy retries or a clear retryable error. Import and financial mutation checks
run in transactions. External SQLite tools are supported for reading; direct
SQL writes bypass financial and transfer guards and are not a supported edit
path. Do not place a live working database on a file-sync service or network
share; move exported files instead.
