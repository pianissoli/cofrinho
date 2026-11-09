# Product and domain rules

This document describes the current Cofrinho model. It supersedes the earlier
planned-expense design. Planned expenses, expected dates, reservations, and
payment-confirmation states do not exist in the current release.

## Monthly budget

For a selected calendar month:

```text
income    = resolved recurring income
recurring = resolved recurring expense schedules
variable  = recorded expenses whose occurred date is in the month
used      = recurring + variable
available = income - used
```

Income and recurring expenses are schedules. Viewing a new month resolves their
amounts without creating transaction rows or changing the ledger revision.
Variable expenses are actual dated purchases and default to credit. Their date
defaults to the device's local date at save time.

Show income, recurring expenses, variable spending, used, and available as exact
amounts. Available is the prominent allowance and may be negative. At zero
income, show the exact negative amount without dividing by zero. Daily charts and
credit/debit totals include variable expenses only; recurring deductions have no
purchase date and do not appear in daily spending.

## Recurring income

An income source has a name and optional stop month. Its schedules contain a
positive amount and effective month. A monthly override replaces the scheduled
amount for one source/month; zero skips that month. Stopping takes precedence over
overrides. A new schedule is appended for an effective month, preserving earlier
months.

For source `s` and month `m`, resolve zero if stopped by `m`; otherwise use the
override for `m`, then the latest schedule starting on or before `m`, then zero.

## Recurring expenses

A recurring expense has a name and optional stop month. Its schedule contains a
positive monthly amount, effective month, and payment method. A monthly override
replaces the amount and zero skips that month. Payment-method changes are made
through a new effective schedule. Stop takes effect from the selected month.

For recurring expense `e` and month `m`, resolve zero if stopped by `m`; otherwise
find the latest schedule starting on or before `m`, then apply that month's
override if present, then use zero when no schedule applies. The resolved amount
is deducted once from `available`.

An unchanged fixed expense uses one schedule. A changed fixed expense appends an
effective-month schedule. An irregular purchase is a normal variable expense and
does not belong to a recurring schedule.

## Variable expenses

An expense contains a stable ID, positive amount in minor units, actual purchase
date, payment method, and optional description. It has no planned status and no
expected date. Editing or deleting it recomputes both affected month summaries.
Credit means the purchase consumes budget when recorded; paying a credit-card bill
does not create another expense.

## Persistence and compatibility

The database generation is 3. It includes `schema_marker` with generation 3,
recurring expense tables, income tables, and the variable `expense` table. Files
from previous releases are invalid, including empty or recorded-only old ledgers.
Clients inspect the marker before opening an existing database and reject old,
newer, malformed, or corrupt files without overwriting them. Users start a new
ledger and re-enter information.

Portable files use format 2 only. Format 1 and planned-expense fields are rejected
before replacement. New exports include all income, recurring-expense, and dated
variable-spending records. Whole-ledger import still requires matching ledger and
currency identities, checkpoint continuity, explicit conflict choice, and a
recoverable pre-replacement backup.

## Interfaces

Android, the Linux CLI, and the default Lanterna TUI share `LedgerService` and
the same SQLite/portable model. They provide:

- recurring expense add, list, effective-month change, one-month override, clear,
  and stop;
- recurring income operations with the existing schedule and override behavior;
- dated variable expense add, edit, delete, and list;
- monthly budget reports with identical totals;
- format-2 export/import and handoff safeguards.

The TUI uses Left/Right and `h`/`l` for month changes, and Down/Up and `j`/`k`
for list or focus movement outside text fields. Its expense viewport must expose
every row, amount fields accept only decimal money input, and each frame must be
bounded, clipped, and refreshed without rendering stale text.
