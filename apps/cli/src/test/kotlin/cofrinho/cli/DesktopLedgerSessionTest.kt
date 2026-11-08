package cofrinho.cli

import cofrinho.finance.IdentifierSource
import cofrinho.finance.Ledger
import cofrinho.finance.LedgerClock
import cofrinho.finance.PaymentMethod
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopLedgerSessionTest {
    @Test
    fun expenseActionUsesInjectedIdentifiersAndBackupPort(): Unit {
        val directory = Files.createTempDirectory("cofrinho-session-")
        val database = directory.resolve("ledger.sqlite")
        val identifiers = SessionIdentifierFake()
        try {
            DesktopLedgerSession.open(database, SessionClockFake(), identifiers, SessionBackupFake()).use { session ->
                session.initialize(Ledger("ledger", "BRL", 2))
                val saved = session.addExpense(100L, null, PaymentMethod.CREDIT, "coffee")
                assertEquals("fake-id-1", saved.value.id)
                assertNull(saved.backupWarning)
            }
        } finally {
            deleteDirectory(directory)
        }
    }
}

private class SessionClockFake : LedgerClock {
    override fun today(): String = "2026-11-07"
    override fun nowUtc(): String = "2026-11-07T12:00:00Z"
}

private class SessionIdentifierFake : IdentifierSource {
    private var index: Int = 0
    override fun next(): String = "fake-id-${++index}"
}

private class SessionBackupFake : DesktopBackupPort {
    override fun beforeOperation(kind: String): Unit = Unit
    override fun afterFinancialEdit(): Unit = Unit
    override fun preserveConflict(contents: String, label: String): Unit = Unit
    override fun preserveLocalConflict(): Unit = Unit
    override fun ordinaryBackups(): List<Path> = emptyList()
}

private fun deleteDirectory(directory: Path): Unit {
    Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
}
