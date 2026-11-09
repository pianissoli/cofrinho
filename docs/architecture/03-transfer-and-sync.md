# Portable files and bidirectional transfer

Cofrinho transfers one logical ledger snapshot through a user-selected file.
Android uses the system document picker; the Linux CLI uses a path. The file is
plaintext JSON with the `.cofrinho` extension and format version 2.

## Format 2

The snapshot contains `ledger_id`, checkpoint metadata, currency, complete income
source/schedule/override arrays, complete recurring-expense/schedule/override
arrays, and dated variable `expenses`. Amounts are decimal strings in minor units.
Variable expenses contain `id`, `amount_minor`, `occurred_on`, `payment_method`,
and optional `description`. Planned statuses and expected dates are invalid fields.

Format 1 and unsupported versions are rejected before reading or replacing the
working ledger. Unknown fields, malformed dates, invalid methods, negative or
overflowing amounts, duplicate identities, and broken references are rejected.

## Handoff

Export stages exact bytes, records a checkpoint and digest, and puts the exporting
installation in read-only handoff mode. Repeated export retries the same bytes.
The receiver validates the snapshot, ledger identity, currency, parent checkpoint,
and revision. A compatible continuation imports atomically. A diverged history
preserves both copies and requires an explicit whole-ledger choice.

Reports remain readable while handed off. Resume editing is explicit and warns
that another device may now have a branch. Every accepted import creates a backup,
updates financial rows and transfer metadata in one transaction, and records the
digest for idempotent retry.

An empty installation may adopt a valid new-format snapshot. A ledger containing
only recurring expenses is not empty. Checkpoint IDs reused with different bytes,
currency mismatches, malformed files, and old format-1 files can never be forced
through the conflict choice.

## Database compatibility

The current SQLite schema generation is 3. Files from previous releases are
invalid and are rejected without migration or overwrite. New clients validate the
generation marker before opening an existing database. Old databases and old
backups must be opened with the old binary if their records are needed; create a
new ledger before using this release.

Backups are created before import, conflict replacement, restore, and any new
format operation that can replace financial rows. Daily backups remain app-private
on Android and per-user on desktop. Failed backup or validation leaves the live
ledger unchanged. The existing handoff and conflict rules apply equally to
Android and CLI.
