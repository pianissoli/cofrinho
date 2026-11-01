package cofrinho.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class FakeStore(private var ledger: Ledger) : LedgerStore {
    override fun read(): Ledger = ledger
    override fun replace(ledger: Ledger) { this.ledger = ledger }
}

private class FailingStore(private var ledger: Ledger) : LedgerStore {
    var failNextWrite: Boolean = false
    override fun read(): Ledger = ledger
    override fun replace(ledger: Ledger) {
        if (failNextWrite) {
            failNextWrite = false
            error("named fake write failure")
        }
        this.ledger = ledger
    }
}

private class FakeClock(private val date: String) : LedgerClock {
    override fun today(): String = date
    override fun nowUtc(): String = "2026-10-30T12:00:00Z"
}

private class FakeIds : IdentifierSource {
    private var next = 0
    override fun next(): String = "id-${++next}"
}

class LedgerServiceTest {
    @Test
    fun recurringIncomeResolvesWithoutMonthlyRows() {
        val store = FakeStore(Ledger("ledger", "BRL", 2))
        val service = LedgerService(store, FakeClock("2026-10-30"), FakeIds())
        service.addIncomeSource("Salary", 250_000, "2026-10")
        assertEquals(250_000, service.summary("2026-12").budgetMinor)
    }

    @Test
    fun overrideReplacesRecurringAmountForOneMonth() {
        val store = FakeStore(Ledger("ledger", "BRL", 2))
        val service = LedgerService(store, FakeClock("2026-10-30"), FakeIds())
        val source = service.addIncomeSource("Salary", 250_000, "2026-10")
        service.overrideIncome(source.id, "2026-11", 240_000)
        assertEquals(240_000, service.summary("2026-11").budgetMinor)
        assertEquals(250_000, service.summary("2026-12").budgetMinor)
    }

    @Test
    fun expenseDefaultsToInjectedLocalToday() {
        val service = LedgerService(FakeStore(Ledger("ledger", "BRL", 2)), FakeClock("2026-10-30"), FakeIds())
        val expense = service.addExpense(12_00)
        assertEquals("2026-10-30", expense.occurredOn)
        assertEquals(PaymentMethod.CREDIT, expense.paymentMethod)
    }

    @Test
    fun recordingPlanReleasesReservationWithoutDoubleCounting() {
        val store = FakeStore(Ledger("ledger", "BRL", 2))
        val service = LedgerService(store, FakeClock("2026-10-30"), FakeIds())
        service.addIncomeSource("Salary", 300_000, "2026-10")
        service.addExpense(50_000, "2026-10-20")
        val plan = service.planExpense(100_000, "2026-10-31")
        service.recordPlanned(plan.id, 95_000)
        val summary = service.summary("2026-10")
        assertEquals(145_000, summary.spentMinor)
        assertEquals(0, summary.reservedMinor)
        assertEquals(155_000, summary.availableMinor)
        assertFailsWith<FinanceException> { service.recordPlanned(plan.id, 95_000) }
        assertEquals(145_000, service.summary("2026-10").spentMinor)
    }

    @Test
    fun plannedAmountCanChangeWithoutIncreasingSpent() {
        val store = FakeStore(ledgerWithBudget())
        val service = LedgerService(store, FakeClock("2026-10-26"), FakeIds())
        service.addExpense(50_000, "2026-10-20")
        val plan = service.planExpense(100_000, "2026-10-28")
        service.editPlannedExpense(plan.id, amountMinor = 120_000)
        val summary = service.summary("2026-10")
        assertEquals(50_000, summary.spentMinor)
        assertEquals(120_000, summary.reservedMinor)
        assertEquals(130_000, summary.availableMinor)
    }

    @Test
    fun cancellingPlanReleasesReservationAndKeepsIdentity() {
        val store = FakeStore(ledgerWithBudget())
        val service = LedgerService(store, FakeClock("2026-10-26"), FakeIds())
        val plan = service.planExpense(100_000, "2026-10-28")
        service.cancelPlan(plan.id)
        val saved = store.read().expenses.single()
        assertEquals(ExpenseStatus.CANCELLED, saved.status)
        assertEquals(0, service.summary("2026-10").reservedMinor)
    }

    @Test
    fun rescheduleMovesReservationToNewMonth() {
        val store = FakeStore(ledgerWithBudget())
        val service = LedgerService(store, FakeClock("2026-10-26"), FakeIds())
        val plan = service.planExpense(100_000, "2026-10-28")
        service.reschedulePlan(plan.id, "2026-11-02")
        assertEquals(0, service.summary("2026-10").reservedMinor)
        assertEquals(100_000, service.summary("2026-11").reservedMinor)
    }

    @Test
    fun reopeningRecordedPlanRequiresNewReservationDetails() {
        val store = FakeStore(ledgerWithBudget())
        val service = LedgerService(store, FakeClock("2026-10-26"), FakeIds())
        val plan = service.planExpense(100_000, "2026-10-28")
        service.recordPlanned(plan.id, 95_000, "2026-11-01")
        service.reopenRecordedExpense(plan.id, 80_000, "2026-11-05")
        val saved = store.read().expenses.single()
        assertEquals(ExpenseStatus.PLANNED, saved.status)
        assertEquals(80_000, service.summary("2026-11").reservedMinor)
        assertEquals(0, service.summary("2026-11").spentMinor)
    }

    @Test
    fun deleteRecordedPlanDoesNotReopenReservation() {
        val store = FakeStore(ledgerWithBudget())
        val service = LedgerService(store, FakeClock("2026-10-26"), FakeIds())
        val plan = service.planExpense(100_000, "2026-10-28")
        service.recordPlanned(plan.id, 95_000)
        service.deleteExpense(plan.id)
        assertEquals(0, service.summary("2026-10").spentMinor)
        assertEquals(0, service.summary("2026-10").reservedMinor)
    }

    @Test
    fun paymentSplitAndDailyTotalsAreExact() {
        val store = FakeStore(ledgerWithBudget())
        val service = LedgerService(store, FakeClock("2026-10-26"), FakeIds())
        service.addExpense(50_000, "2026-10-20", PaymentMethod.CREDIT)
        service.addExpense(25_000, "2026-10-20", PaymentMethod.DEBIT)
        val summary = service.summary("2026-10")
        assertEquals(50_000, summary.creditSpentMinor)
        assertEquals(25_000, summary.debitSpentMinor)
        assertEquals(listOf(DailySpending("2026-10-20", 75_000)), summary.dailySpending)
    }

    @Test
    fun failedWriteLeavesOriginalPlanAndRevision() {
        val store = FailingStore(ledgerWithBudget())
        val service = LedgerService(store, FakeClock("2026-10-26"), FakeIds())
        val plan = service.planExpense(100_000, "2026-10-28")
        val before = store.read()
        store.failNextWrite = true
        assertFailsWith<IllegalStateException> { service.recordPlanned(plan.id, 95_000) }
        assertEquals(before, store.read())
    }

    @Test
    fun overspendingAndZeroBudgetStayVisible() {
        val overspent = FakeStore(ledgerWithBudget())
        val overspentService = LedgerService(overspent, FakeClock("2026-10-26"), FakeIds())
        overspentService.addExpense(350_000, "2026-10-20")
        assertEquals(-50_000, overspentService.summary("2026-10").availableMinor)
        val empty = FakeStore(Ledger("ledger", "BRL", 2))
        val emptyService = LedgerService(empty, FakeClock("2026-10-26"), FakeIds())
        emptyService.planExpense(100_000, "2026-10-28")
        assertEquals(-100_000, emptyService.summary("2026-10").availableMinor)
    }

    @Test
    fun exactMoneyRejectsExcessPrecisionAndOverflow() {
        assertFailsWith<FinanceException> { MoneyRules.parseMinor("1.001", 2) }
        assertFailsWith<FinanceException> { MoneyRules.parseMinor("92233720368547758.08", 2) }
    }

    private fun ledgerWithBudget(): Ledger = Ledger(
        "ledger",
        "BRL",
        2,
        sources = listOf(IncomeSource("source", "Salary")),
        schedules = listOf(IncomeSchedule("schedule", "source", "2026-10", 300_000)),
    )
}
