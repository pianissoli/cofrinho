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
    override fun replace(ledger: Ledger) { if (failNextWrite) { failNextWrite = false; error("named fake write failure") }; this.ledger = ledger }
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
    fun recurringIncomeAndExpenseResolveWithoutMonthlyRows() {
        val store = FakeStore(Ledger("ledger", "BRL", 2))
        val service = LedgerService(store, FakeClock("2026-10-30"), FakeIds())
        service.addIncomeSource("Salary", 300_000, "2026-10")
        service.addRecurringExpense("Rent", 100_000, "2026-10")
        service.addExpense(50_000, "2026-10-20")
        assertEquals(300_000, service.summary("2026-11").budgetMinor)
        assertEquals(100_000, service.summary("2026-11").recurringMinor)
        assertEquals(200_000, service.summary("2026-11").availableMinor)
        assertEquals(150_000, service.summary("2026-10").availableMinor)
    }

    @Test
    fun recurringOverrideReplacesOneMonthAndClears() {
        val store = FakeStore(Ledger("ledger", "BRL", 2))
        val service = LedgerService(store, FakeClock("2026-10-30"), FakeIds())
        val expense = service.addRecurringExpense("Utilities", 100_000, "2026-10")
        service.overrideRecurringExpense(expense.id, "2026-11", 120_000)
        assertEquals(120_000, service.summary("2026-11").recurringMinor)
        assertEquals(100_000, service.summary("2026-12").recurringMinor)
        service.clearRecurringExpenseOverride(expense.id, "2026-11")
        assertEquals(100_000, service.summary("2026-11").recurringMinor)
    }

    @Test
    fun zeroRecurringOverrideSkipsMonth() {
        val store = FakeStore(Ledger("ledger", "BRL", 2))
        val service = LedgerService(store, FakeClock("2026-10-30"), FakeIds())
        val expense = service.addRecurringExpense("Rent", 100_000, "2026-10")
        service.overrideRecurringExpense(expense.id, "2026-11", 0)
        assertEquals(0, service.summary("2026-11").recurringMinor)
        assertEquals(100_000, service.summary("2026-12").recurringMinor)
    }

    @Test
    fun overrideBeforeFirstScheduleIsRejected() {
        val store = FakeStore(Ledger("ledger", "BRL", 2))
        val service = LedgerService(store, FakeClock("2026-10-30"), FakeIds())
        val expense = service.addRecurringExpense("Rent", 100_000, "2026-11")
        assertFailsWith<FinanceException> { service.overrideRecurringExpense(expense.id, "2026-10", 0) }
        assertEquals(emptyList(), store.read().recurringExpenseOverrides)
    }

    @Test
    fun incomeOverrideBeforeFirstScheduleIsRejected() {
        val store = FakeStore(Ledger("ledger", "BRL", 2))
        val service = LedgerService(store, FakeClock("2026-10-30"), FakeIds())
        val source = service.addIncomeSource("Salary", 300_000, "2026-11")
        assertFailsWith<FinanceException> { service.overrideIncome(source.id, "2026-10", 0) }
        assertEquals(emptyList(), store.read().overrides)
    }

    @Test
    fun recurringChangePreservesEarlierMonthAndStopTakesEffect() {
        val store = FakeStore(Ledger("ledger", "BRL", 2))
        val service = LedgerService(store, FakeClock("2026-10-30"), FakeIds())
        val expense = service.addRecurringExpense("Rent", 100_000, "2026-10")
        service.changeRecurringExpense(expense.id, 120_000, "2026-12")
        service.stopRecurringExpense(expense.id, "2027-02")
        assertEquals(100_000, service.summary("2026-11").recurringMinor)
        assertEquals(120_000, service.summary("2026-12").recurringMinor)
        assertEquals(0, service.summary("2027-02").recurringMinor)
    }

    @Test
    fun amountOnlyChangeUsesMethodAtChosenEffectiveMonth() {
        val store = FakeStore(Ledger("ledger", "BRL", 2))
        val service = LedgerService(store, FakeClock("2026-10-30"), FakeIds())
        val expense = service.addRecurringExpense("Rent", 100_000, "2026-10", PaymentMethod.CREDIT)
        service.changeRecurringExpense(expense.id, 110_000, "2026-12", PaymentMethod.DEBIT)
        service.changeRecurringExpense(expense.id, 120_000, "2026-11")
        assertEquals(PaymentMethod.CREDIT, service.resolvedRecurringExpenses("2026-11").single().paymentMethod)
        assertEquals(PaymentMethod.DEBIT, service.resolvedRecurringExpenses("2026-12").single().paymentMethod)
    }

    @Test
    fun expenseDefaultsToInjectedLocalTodayAndUsesCredit() {
        val service = LedgerService(FakeStore(Ledger("ledger", "BRL", 2)), FakeClock("2026-10-30"), FakeIds())
        val expense = service.addExpense(12_00)
        assertEquals("2026-10-30", expense.occurredOn)
        assertEquals(PaymentMethod.CREDIT, expense.paymentMethod)
    }

    @Test
    fun paymentSplitAndDailyTotalsExcludeRecurringExpense() {
        val store = FakeStore(ledgerWithBudget())
        val service = LedgerService(store, FakeClock("2026-10-26"), FakeIds())
        service.addRecurringExpense("Rent", 100_000, "2026-10")
        service.addExpense(50_000, "2026-10-20", PaymentMethod.CREDIT)
        service.addExpense(25_000, "2026-10-20", PaymentMethod.DEBIT)
        val summary = service.summary("2026-10")
        assertEquals(50_000, summary.creditSpentMinor)
        assertEquals(25_000, summary.debitSpentMinor)
        assertEquals(listOf(DailySpending("2026-10-20", 75_000)), summary.dailySpending)
        assertEquals(175_000, summary.usedMinor)
    }

    @Test
    fun failedWriteLeavesOriginalLedgerAndRevision() {
        val store = FailingStore(ledgerWithBudget())
        val service = LedgerService(store, FakeClock("2026-10-26"), FakeIds())
        val before = store.read()
        store.failNextWrite = true
        assertFailsWith<IllegalStateException> { service.addRecurringExpense("Rent", 100_000, "2026-10") }
        assertEquals(before, store.read())
    }

    @Test
    fun overspendingAndZeroBudgetStayVisible() {
        val overspent = FakeStore(ledgerWithBudget())
        val service = LedgerService(overspent, FakeClock("2026-10-26"), FakeIds())
        service.addExpense(350_000, "2026-10-20")
        assertEquals(-50_000, service.summary("2026-10").availableMinor)
        val empty = FakeStore(Ledger("ledger", "BRL", 2))
        val emptyService = LedgerService(empty, FakeClock("2026-10-26"), FakeIds())
        emptyService.addExpense(100_000, "2026-10-20")
        assertEquals(-100_000, emptyService.summary("2026-10").availableMinor)
    }

    @Test
    fun exactMoneyRejectsExcessPrecisionAndOverflow() {
        assertFailsWith<FinanceException> { MoneyRules.parseMinor("1.001", 2) }
        assertFailsWith<FinanceException> { MoneyRules.parseMinor("92233720368547758.08", 2) }
    }

    private fun ledgerWithBudget(): Ledger = Ledger(
        id = "ledger",
        currencyCode = "BRL",
        currencyScale = 2,
        sources = listOf(IncomeSource("source", "Salary")),
        schedules = listOf(IncomeSchedule("schedule", "source", "2026-10", 300_000)),
    )
}
