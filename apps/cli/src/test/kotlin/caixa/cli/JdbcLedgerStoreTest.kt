package caixa.cli

import caixa.finance.ExpenseStatus
import caixa.finance.IdentifierSource
import caixa.finance.Ledger
import caixa.finance.LedgerClock
import caixa.finance.LedgerService
import caixa.finance.PaymentMethod
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class TestClock : LedgerClock {
    override fun today(): String = "2026-10-26"
    override fun nowUtc(): String = "2026-10-26T12:00:00Z"
}

private class TestIds : IdentifierSource {
    private var number = 0
    override fun next(): String = "test-${++number}"
}

class JdbcLedgerStoreTest {
    @Test
    fun plannedStateSurvivesStoreRestart() {
        val directory = Files.createTempDirectory("caixa-store-")
        val path = directory.resolve("ledger.sqlite")
        JdbcLedgerStore(path).replace(Ledger("ledger", "BRL", 2))
        LedgerService(JdbcLedgerStore(path), TestClock(), TestIds()).planExpense(100_00, "2026-10-28", PaymentMethod.DEBIT)
        val expense = JdbcLedgerStore(path).read().expenses.single()
        assertEquals(ExpenseStatus.PLANNED, expense.status)
        assertEquals(PaymentMethod.DEBIT, expense.paymentMethod)
    }

    @Test
    fun invalidStateIsRejectedBySqliteConstraint() {
        val path = Files.createTempDirectory("caixa-constraint-").resolve("ledger.sqlite")
        JdbcLedgerStore(path).replace(Ledger("ledger", "BRL", 2))
        val store = JdbcLedgerStore(path)
        assertFailsWith<Exception> { store.connection().createStatement().execute("INSERT INTO expense VALUES ('bad', 100, 'PLANNED', NULL, NULL, 'CREDIT', NULL)") }
    }
}
