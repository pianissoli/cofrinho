package caixa.transfer

import caixa.finance.ExpenseStatus
import caixa.finance.IncomeSource
import caixa.finance.Ledger
import caixa.finance.PaymentMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortableLedgerTest {
    @Test
    fun plannedAndRecurringRecordsRoundTrip() {
        val ledger = Ledger("ledger", "BRL", 2, listOf(IncomeSource("source", "Salary")), schedules = listOf(caixa.finance.IncomeSchedule("schedule", "source", "2026-10", 250_000)), expenses = listOf(caixa.finance.Expense("expense", 100_000, ExpenseStatus.PLANNED, "2026-10-31", null, PaymentMethod.CREDIT, "Rent")))
        val snapshot = PortableLedgerCodec.snapshot(ledger, "checkpoint", null, "2026-10-30T12:00:00Z")
        val restored = PortableLedgerCodec.ledger(PortableLedgerCodec.decode(PortableLedgerCodec.encode(snapshot)))
        assertEquals(ledger.sources, restored.sources)
        assertEquals(ledger.schedules, restored.schedules)
        assertEquals(ledger.expenses, restored.expenses)
        assertTrue(PortableLedgerCodec.encode(snapshot).contains("\"format_version\""))
    }

    @Test
    fun invalidExpenseDatesFailBeforeImport() {
        val ledger = PortableLedger(1, "ledger", "checkpoint", null, "2026-10-30T12:00:00Z", "BRL", 2, emptyList(), emptyList(), emptyList(), listOf(PortableExpense("expense", "100", "PLANNED", null, null, "CREDIT")))
        assertFailsWith<Exception> { PortableLedgerCodec.decode(PortableLedgerCodec.encode(ledger)) }
    }

    @Test
    fun cancelledAndRecordedPlanStatesRoundTrip() {
        val expenses = listOf(
            caixa.finance.Expense("planned", 100, ExpenseStatus.CANCELLED, "2026-10-31", null, PaymentMethod.CREDIT, null),
            caixa.finance.Expense("recorded", 200, ExpenseStatus.RECORDED, "2026-10-31", "2026-11-01", PaymentMethod.DEBIT, "paid"),
        )
        val snapshot = PortableLedgerCodec.snapshot(Ledger("ledger", "BRL", 2, expenses = expenses), "checkpoint", null, "2026-10-30T12:00:00Z")
        assertEquals(expenses, PortableLedgerCodec.ledger(PortableLedgerCodec.decode(PortableLedgerCodec.encode(snapshot))).expenses)
    }

    @Test
    fun duplicateScheduleIdentityFailsValidation() {
        val snapshot = PortableLedger(1, "ledger", "checkpoint", null, "2026-10-30T12:00:00Z", "BRL", 2, listOf(PortableSource("source", "Salary")), listOf(PortableSchedule("same", "source", "2026-10", "100"), PortableSchedule("same", "source", "2026-11", "100")), emptyList(), emptyList())
        assertFailsWith<Exception> { PortableLedgerCodec.encode(snapshot) }
    }
}
