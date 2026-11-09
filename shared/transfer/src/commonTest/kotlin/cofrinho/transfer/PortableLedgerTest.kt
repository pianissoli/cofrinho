package cofrinho.transfer

import cofrinho.finance.Expense
import cofrinho.finance.IncomeSchedule
import cofrinho.finance.IncomeSource
import cofrinho.finance.Ledger
import cofrinho.finance.PaymentMethod
import cofrinho.finance.RecurringExpense
import cofrinho.finance.RecurringExpenseOverride
import cofrinho.finance.RecurringExpenseSchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortableLedgerTest {
    @Test
    fun recurringAndVariableRecordsRoundTrip() {
        val source = IncomeSource("source", "Salary")
        val recurring = RecurringExpense("rent", "Rent")
        val ledger = Ledger(
            id = "ledger",
            currencyCode = "BRL",
            currencyScale = 2,
            sources = listOf(source),
            schedules = listOf(IncomeSchedule("schedule", source.id, "2026-10", 250_000)),
            recurringExpenses = listOf(recurring),
            recurringExpenseSchedules = listOf(RecurringExpenseSchedule("rent-schedule", recurring.id, "2026-10", 100_000)),
            expenses = listOf(Expense("expense", 10_000, "2026-10-31", PaymentMethod.CREDIT, "Market")),
        )
        val snapshot = PortableLedgerCodec.snapshot(ledger, "checkpoint", null, "2026-10-30T12:00:00Z")
        val restoredSnapshot = PortableLedgerCodec.decode(PortableLedgerCodec.encode(snapshot))
        val restored = PortableLedgerCodec.ledger(restoredSnapshot)
        assertEquals(2, restoredSnapshot.formatVersion)
        assertEquals(ledger.sources, restored.sources)
        assertEquals(ledger.recurringExpenses, restored.recurringExpenses)
        assertEquals(ledger.recurringExpenseSchedules, restored.recurringExpenseSchedules)
        assertEquals(ledger.expenses, restored.expenses)
        assertTrue(PortableLedgerCodec.encode(snapshot).contains("\"format_version\""))
    }

    @Test
    fun formatOneIsRejected() {
        val contents = """{"format_version":1,"ledger_id":"ledger","checkpoint_id":"checkpoint","exported_at":"2026-10-30T12:00:00Z","currency_code":"BRL","currency_scale":2,"income_sources":[],"income_schedules":[],"income_overrides":[],"recurring_expenses":[],"recurring_expense_schedules":[],"recurring_expense_overrides":[],"expenses":[]}"""
        assertFailsWith<Exception> { PortableLedgerCodec.decode(contents) }
    }

    @Test
    fun invalidExpenseDatesFailBeforeImport() {
        val ledger = Ledger("ledger", "BRL", 2, expenses = listOf(Expense("expense", 100, "2026-02-30", PaymentMethod.CREDIT)))
        assertFailsWith<Exception> { PortableLedgerCodec.encode(PortableLedgerCodec.snapshot(ledger, "checkpoint", null, "2026-10-30T12:00:00Z")) }
    }

    @Test
    fun duplicateRecurringScheduleIdentityFailsValidation() {
        val recurring = RecurringExpense("rent", "Rent")
        val ledger = Ledger(
            "ledger", "BRL", 2,
            recurringExpenses = listOf(recurring),
            recurringExpenseSchedules = listOf(
                RecurringExpenseSchedule("first", recurring.id, "2026-10", 100),
                RecurringExpenseSchedule("second", recurring.id, "2026-10", 200),
            ),
        )
        assertFailsWith<Exception> { PortableLedgerCodec.encode(PortableLedgerCodec.snapshot(ledger, "checkpoint", null, "2026-10-30T12:00:00Z")) }
    }

    @Test
    fun recurringOverrideBeforeFirstScheduleFailsValidation() {
        val recurring = RecurringExpense("rent", "Rent")
        val ledger = Ledger(
            "ledger", "BRL", 2,
            recurringExpenses = listOf(recurring),
            recurringExpenseSchedules = listOf(RecurringExpenseSchedule("schedule", recurring.id, "2026-11", 100)),
            recurringExpenseOverrides = listOf(RecurringExpenseOverride("override", recurring.id, "2026-10", 0)),
        )
        assertFailsWith<Exception> { PortableLedgerCodec.encode(PortableLedgerCodec.snapshot(ledger, "checkpoint", null, "2026-10-30T12:00:00Z")) }
    }
}
