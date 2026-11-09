package cofrinho.cli

import cofrinho.finance.Expense
import cofrinho.finance.IdentifierSource
import cofrinho.finance.Ledger
import cofrinho.finance.LedgerClock
import cofrinho.finance.PaymentMethod
import java.nio.file.Files
import java.sql.DriverManager
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
    fun variableExpenseAndRecurringRowsSurviveStoreRestart() {
        val path = Files.createTempDirectory("cofrinho-store-").resolve("ledger.sqlite")
        val store = JdbcLedgerStore(path)
        val ledger = Ledger("ledger", "BRL", 2, expenses = listOf(Expense("expense", 100_00, "2026-10-26", PaymentMethod.DEBIT)))
        store.replace(ledger)
        assertEquals(ledger, JdbcLedgerStore(path).read())
    }

    @Test
    fun oldDatabaseIsRejectedWithoutOpeningAsNewLedger() {
        val path = Files.createTempDirectory("cofrinho-old-").resolve("ledger.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement -> statement.execute("CREATE TABLE expense(expense_id TEXT PRIMARY KEY, amount_minor INTEGER)") }
        }
        assertFailsWith<IllegalStateException> { JdbcLedgerStore(path) }
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement -> statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='schema_marker'").use { rows -> assertEquals(false, rows.next()) } }
        }
    }
}
