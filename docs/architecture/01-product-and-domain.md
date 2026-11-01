# Product experience and financial rules

## Main Android flow

The home screen opens on the current calendar month and shows budget, spent,
reserved, and available amounts. A prominent Add expense action opens a compact form.
Recent expenses appear below the summary so a mistaken entry is easy to edit.

An expense requires an amount and a date. A fresh form starts with today's date
in the device's local calendar, even when viewing another budget month. Read
the clock when opening the form, not just when starting the application.
If an untouched default crosses midnight before save, refresh it at save time;
preserve any date explicitly chosen by the user. Editing and importing preserve
stored dates. Recording a plan also defaults its actual date to today while
retaining its expected date. The CLI uses local today when `--date` is omitted.
Payment method starts as Credit every time a new form
opens; choosing Debit for one expense does not change the next form's default.
An optional description helps identify the purchase. Save commits locally and
returns to the updated summary. No network or account step interrupts entry.

Show Credit and Debit as visible choices, rather than hiding them behind another
screen. Editing preserves the saved choice. Charts must have text labels and
exact amounts, work with a screen reader, and communicate overspending through
text as well as color.

Use a budget progress bar and a daily spending chart initially. Include the
credit/debit split as labeled totals. Categories are a possible later feature;
the brief does not require a category taxonomy to enter an expense.

The Income screen lists named sources and their resolved amounts for the
selected month. Register an amount and starting month once; monthly recurrence
is the default. Future monthly budgets include it automatically until changed
or stopped. The source represents expected income, not confirmation of deposits.
Offer Change recurring amount and Change this month only as separate actions.

## Recurring planned income

Adopted on 2026-10-30. Monthly income requires no repeated entry, confirmation,
copy action, background job, or network connection. Resolve each month's amount
from persisted source schedules whenever that month's budget is queried.

- A source has a positive monthly amount effective from a starting month,
  defaulting to the current month. It contributes nothing before that month.
- A recurring change creates an amount effective from a chosen month. Earlier
  months keep the amounts that applied to them; do not overwrite old schedules.
- A month-specific override replaces the scheduled amount for that source and
  month. Zero explicitly skips that month. Removing an override restores the
  scheduled amount; it does not remove the source or stop recurrence.
- A one-off income source can have just one monthly override and no schedule.
- Archiving/stopping takes effect from a chosen month, defaulting to next month.
  That source contributes zero from then onward, including any later overrides.
  Preserve earlier schedules and overrides for history and transfer.
- Show a preview before changes that reduce a month's budget. Historical edits
  require an explicit effective month; changing today's default must not rewrite
  earlier monthly budgets silently. Month-based schedules have no daily proration.

For source `s` and month `m`, its resolved amount is zero if stopped by `m`;
otherwise use the explicit override for `m`, if present; otherwise use the
latest schedule starting on or before `m`; otherwise zero. Sum one resolved
amount per source. Never add an override on top of its scheduled amount.

For example, register Salary at 2,500.00 starting in October. October, November,
and later months each include 2,500.00 without further input. Change it to
2,700.00 from December: earlier months retain 2,500.00. An override of 2,400.00
for November affects November alone. Opening the app after several months or
importing on another device computes the same result without generating rows.

The Transfer screen explains the direction of travel, the last transfer, whether
this installation can currently edit, and any changes that prevent import.
Use Android's system document picker for selecting or saving portable files.

## Budget meaning

For a selected month:

```text
budget    = sum(resolved income source amounts for that month)
spent     = sum(RECORDED expenses whose purchase date is in that month)
reserved  = sum(PLANNED expenses whose expected date is in that month)
remaining = budget - spent
available = budget - spent - reserved
```

With a budget of 3,000.00, a credit expense of 150.00 and a debit expense of
50.00, spent is 200.00 and remaining is 2,800.00. A planned expense of 1,000.00
reserves that amount, leaving 1,800.00 available. Paying the credit card bill
must not create another expense for the original purchases.

When budget is positive, percent spent is `100 × spent / budget`. Round only
for display. At zero budget, display No budget defined and the exact spent,
reserved, and available amounts; do not divide by zero. When spending exceeds budget, show
the full percentage and negative remaining amount. A progress bar may cap its
fill, but its text must still show the excess.

Remaining budget is a planning measure, not a bank balance or available credit.
Monthly installments, refunds, account balances, actual income receipts, card
statements, transfers between accounts, and multiple currencies need explicit
rules if added later. They are outside the proposed first release.

## Planned expenses

Adopted on 2026-10-26 for the first release. Treat an upcoming expense as a
reservation rather than recording it as money already spent.

| Summary | Example amount |
| --- | --- |
| Planned income / budget | 3,000.00 |
| Already spent | 500.00 |
| Reserved for upcoming expenses | 1,000.00 |
| Remaining before reservations | 2,500.00 |
| Available after reservations | 1,500.00 |

Make Available after reservations the prominent spending allowance. Show spent,
reserved, and available as separate chart segments and exact labeled amounts.
Keep spent percentage based on recorded expenses; planned commitments must not
be presented as completed purchases. Available can be negative even when spent
alone is below budget, revealing an overcommitted month.

Add a Plan expense action alongside the existing quick-entry flow. It collects
an expected amount, expected date, optional description, and intended payment
method, defaulting to credit. Ordinary Add expense still records an actual
purchase immediately. Both mobile and CLI provide these operations.

Use the same expense identity through three states:

- PLANNED: reserves its expected amount in its expected month; contributes
  nothing to spent.
- RECORDED: contributes its confirmed actual amount to spent in its actual
  purchase month; contributes nothing to reserved.
- CANCELLED: contributes to neither total.

Record expense on a plan asks the user to confirm the actual amount, date, and
payment method. In one transaction, switch its state and replace the estimated
amount with the actual amount. Keep its ID and expected date. Retrying the
action must not create another expense or consume the budget a second time.
For example, recording a planned 1,000.00 expense at 950.00 releases 1,000.00
of reservations and adds 950.00 to spent, making another 50.00 available.

Reaching the expected date does not automatically record the purchase. An
overdue plan remains visible and reserved in its original expected month until
the user records, reschedules, or cancels it. A plan in a future month reserves
that month's budget only. If the actual purchase happens in another month,
release the original reservation and charge the actual month atomically.

For credit, Record expense means the charge happened, not that a later card
bill was paid. Cancelling a plan releases its reservation. Removing or reverting
a recorded expense that originated as a plan must explicitly ask whether the
reservation should reopen; it must not silently reserve or discard money.
If reopening is selected, confirm the expected amount and date again because
the recorded amount may differ from the original estimate.

Store `status`, nullable `expected_on`, and nullable `occurred_on` on `expense`.
PLANNED and CANCELLED require `expected_on` and no `occurred_on`; RECORDED
requires `occurred_on` and may retain `expected_on` from its plan. CANCELLED
retains planning details but is excluded from totals. `amount_minor` remains positive. The
confirmation replaces the estimate; estimate-versus-actual history is outside
the first-release scope. Normal manual entries default to RECORDED.

These states must survive SQLite storage and portable files. Add state/date
validation, export compatibility, summary queries, and shared fixtures before
enabling the feature. A future-date recorded expense is not a substitute for a
plan: its status determines whether it is spent or reserved. Recurring plans
can be considered separately; one-off planning does not require recurrence.

## Money, dates, and identities

- Store amounts as positive signed 64-bit integers in minor units. For the
  first-release two-decimal currency, 150.25 is stored as 15025.
- Store one currency code and scale on the ledger. Do not change currency after
  amounts exist. Never combine different ledger currencies in a report.
- Reject zero or negative expense and recurring-income amounts. Monthly income
  overrides allow zero to skip a month, but never a negative amount. Use checked
  arithmetic for totals and reject overflow before committing an operation.
- Parse localized input exactly into minor units. Reject excess fractional
  digits instead of silently rounding a user's entry.
- Store purchase dates as validated `YYYY-MM-DD` calendar dates and budget
  months as validated `YYYY-MM`. A travel or timezone change must not move an
  existing purchase to another month.
- UTC creation and modification timestamps are audit information. They do not
  order transfers or decide which conflicting financial record wins.
- Generate stable random UUIDs for the ledger and financial records. Labels
  and positions in lists are never identities.
- A new expense's payment method is CREDIT by default. Imported records must
  contain an explicit valid method; missing values are validation errors.

Currency and the first month are explicit during initial setup. Locale can
suggest a two-decimal currency, but the user confirms it before entering amounts.
The interface launches in English; dates and amounts use locale-aware formatting.
Calendar-month budgets have no automatic carryover of unused budget.

## Logical financial schema

This is a logical model. Final DDL depends on the selected persistence option.

| Table | Fields and constraints |
| --- | --- |
| `ledger` | One row: `ledger_id`, `currency_code`, `currency_scale` |
| `income_source` | `source_id`, nonempty `name`, nullable `stopped_from_month`; names need not be unique |
| `income_schedule` | `schedule_id`, `source_id`, `starts_month`, positive `amount_minor`; unique source and starting month |
| `income_override` | `override_id`, `source_id`, `budget_month`, nonnegative `amount_minor`; unique source and month |
| `expense` | `expense_id`, positive `amount_minor`, `status`, nullable `expected_on` and `occurred_on` constrained by status, `payment_method`, optional `description` |

Foreign keys connect schedules and overrides to sources. Archiving a source
keeps earlier monthly budgets and hides it from new-income suggestions after
its stop month. Schedule/override changes and the local financial revision
advance in one transaction. Simply viewing a new month performs no financial
write, so independent month navigation cannot create duplicate income or dirty
transfer histories. Do not cascade-delete financial history.

Changing a recorded purchase date or a plan's expected date can move its amount
between months. Derive month membership from the relevant date and status; do
not maintain another editable month field. One save updates the expense and
invalidates affected summary observations together.

Add indexes for purchase dates, expected dates, schedule starts, and override months. Enforce
foreign keys, positive amounts, valid statuses, state/date combinations, and
CREDIT/DEBIT membership in SQLite as well as validating them at application
boundaries. Real calendar-date validation belongs in the
shared financial code; a text pattern alone cannot validate February 30.

Transfer checkpoints, schema migrations, pending exports, import receipts, and
local edit counters are operational metadata. They are not expenses or income
and must not affect budget totals.

## Shared behavior and CLI parity

Both interfaces must offer the following behavior:

| User operation | Required result |
| --- | --- |
| Add expense | Validate amount/date; default missing interactive payment choice to credit; persist once |
| Plan expense | Reserve the expected amount in its expected month; default payment to credit |
| Record planned expense | Confirm actual amount/date/method; release reservation and record spending atomically |
| Reschedule or cancel plan | Move or release its reservation without changing recorded spending |
| Edit or delete expense | Recompute affected month totals atomically |
| Register income source | Default to monthly recurrence; amount contributes from its starting month onward |
| Change recurring income | Add a schedule effective from the chosen month; preserve prior months |
| Override one month | Replace or skip that source/month only; removing the override restores recurrence |
| Archive income source | Stop contributions from the effective month; keep earlier history |
| View month | Same budget, spent, reserved, remaining, available, daily totals, and recorded payment split |
| Export or import | Same validation, transfer guards, and financial results |

Illustrative CLI commands, to be finalized with the chosen command library:

```text
cofrinho expense add --amount 42.90 --date 2026-10-19 --payment credit
cofrinho expense plan --amount 1000.00 --expected-date 2026-10-28
cofrinho expense record <expense-id> --amount 950.00 --date 2026-10-28
cofrinho expense reschedule <expense-id> --expected-date 2026-11-02
cofrinho expense cancel <expense-id>
cofrinho expense list --month 2026-10
cofrinho expense edit <expense-id> --payment debit
cofrinho expense delete <expense-id>
cofrinho income add --name Salary --amount 3000.00 --from 2026-10
cofrinho income change <source-id> --amount 3100.00 --from 2026-12
cofrinho income override <source-id> --month 2026-11 --amount 2800.00
cofrinho income clear-override <source-id> --month 2026-11
cofrinho income archive <source-id> --from 2027-01
cofrinho budget show --month 2026-10
cofrinho transfer export ./ledger.cofrinho
cofrinho transfer import ./ledger.cofrinho
```

The CLI accepts a database path and has a documented default under the user's
application-data directory. It prints readable tables and text charts. Machine
consumers can request JSON output. Errors go to stderr with a nonzero exit
code; diagnostic logs use structured JSON and omit financial descriptions.

Every public input path uses the same validation rules. Validation errors name
the offending value and expected shape, while logs avoid exposing ledger
contents or encryption secrets. Preserve a partially completed form after a
validation or storage failure so users can correct or retry it.
