package cofrinho.cli

import cofrinho.cli.tui.CofrinhoTui
import cofrinho.cli.tui.TerminalKey
import cofrinho.cli.tui.TerminalKeyKind
import cofrinho.cli.tui.TerminalPort
import cofrinho.finance.Expense
import cofrinho.finance.ExpenseStatus
import cofrinho.finance.IncomeSchedule
import cofrinho.finance.IncomeSource
import cofrinho.finance.Ledger
import cofrinho.finance.LedgerClock
import cofrinho.finance.PaymentMethod
import cofrinho.finance.TransferState
import com.googlecode.lanterna.TerminalSize
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CofrinhoTuiTest {
    @Test
    fun cancelledSetupDoesNotCreateLedger(): Unit = withTemporaryDirectory("cofrinho-tui-setup-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        CofrinhoTui(database, { RecordingTerminal(listOf(escape())) }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertTrue(Files.notExists(database))
    }

    @Test
    fun setupAndKeyboardExpenseUseTodayAndCreditDefaults(): Unit = withTemporaryDirectory("cofrinho-tui-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        val terminal = RecordingTerminal(firstRunAndSaveKeys("10"))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        val expense = JdbcLedgerStore(database).read().expenses.single()
        assertEquals("2026-11-03", expense.occurredOn)
        assertEquals(PaymentMethod.CREDIT, expense.paymentMethod)
        assertEquals(1000L, expense.amountMinor)
        assertTrue(terminal.closed)
    }

    @Test
    fun invalidAmountRemainsVisibleAndDoesNotWrite(): Unit = withTemporaryDirectory("cofrinho-tui-invalid-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2))
        val terminal = RecordingTerminal(keysForInvalidAmount())
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertTrue(terminal.transcript().contains("Invalid amount 'bad'"))
        assertTrue(JdbcLedgerStore(database).read().expenses.isEmpty())
    }

    @Test
    fun explicitDateAndDebitPaymentArePreserved(): Unit = withTemporaryDirectory("cofrinho-tui-date-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2))
        val terminal = RecordingTerminal(keysForExplicitDateAndDebit())
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        val expense = JdbcLedgerStore(database).read().expenses.single()
        assertEquals("2026-11-01", expense.occurredOn)
        assertEquals(PaymentMethod.DEBIT, expense.paymentMethod)
    }

    @Test
    fun untouchedDateUsesClockValueAtSaveAfterMidnight(): Unit = withTemporaryDirectory("cofrinho-tui-rollover-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2))
        val clock = MutableClock("2026-11-03")
        val terminal = RecordingTerminal(setupAndSaveKeys("10")) { _, key ->
            if (key.kind == TerminalKeyKind.ENTER) clock.date = "2026-11-04"
        }
        CofrinhoTui(database, { terminal }, clock, FakeBackupPort()).run()
        assertEquals("2026-11-04", JdbcLedgerStore(database).read().expenses.single().occurredOn)
    }

    @Test
    fun dirtyFormRequiresConfirmationBeforeDiscarding(): Unit = withTemporaryDirectory("cofrinho-tui-cancel-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2))
        val keys = mutableListOf<TerminalKey>(character('n'))
        keys += chars("10")
        keys += listOf(escape(), character('n'), escape(), character('y'), character('q'))
        CofrinhoTui(database, { RecordingTerminal(keys) }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertTrue(JdbcLedgerStore(database).read().expenses.isEmpty())
    }

    @Test
    fun handoffBlocksExpenseFormButKeepsOverviewReadable(): Unit = withTemporaryDirectory("cofrinho-tui-handoff-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2), TransferState(handedOff = true))
        val terminal = RecordingTerminal(listOf(character('n'), character('q')))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertTrue(terminal.transcript().contains("Ledger is handed off"))
        assertTrue(JdbcLedgerStore(database).read().expenses.isEmpty())
    }

    @Test
    fun overviewShowsZeroBudgetAndDoesNotInventIncome(): Unit = withTemporaryDirectory("cofrinho-tui-zero-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2))
        val terminal = RecordingTerminal(listOf(character('q')))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertTrue(terminal.transcript().contains("No budget defined"))
        assertTrue(JdbcLedgerStore(database).read().sources.isEmpty())
    }

    @Test
    fun overviewSeparatesReservationsAndReportsOverspending(): Unit = withTemporaryDirectory("cofrinho-tui-summary-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        val source = IncomeSource("source", "Salary")
        val ledger = Ledger(
            "ledger", "BRL", 2,
            sources = listOf(source),
            schedules = listOf(IncomeSchedule("schedule", source.id, "2026-11", 1_000L)),
            expenses = listOf(
                Expense("recorded", 2_000L, ExpenseStatus.RECORDED, null, "2026-11-03", PaymentMethod.CREDIT, "spent"),
                Expense("planned", 100L, ExpenseStatus.PLANNED, "2026-11-20", null, PaymentMethod.CREDIT, "reserved"),
            ),
        )
        writeLedger(database, ledger)
        val terminal = RecordingTerminal(listOf(character('q')))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertTrue(terminal.transcript().contains("Reserved        1.00"))
        assertTrue(terminal.transcript().contains("Overcommitted"))
        assertTrue(terminal.transcript().contains("planned"))
    }

    @Test
    fun successfulSaveReportsBackupFailureWithoutRepeatingWrite(): Unit = withTemporaryDirectory("cofrinho-tui-backup-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2))
        val terminal = RecordingTerminal(setupAndSaveKeys("10"))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort(failAfterEdit = true)).run()
        assertTrue(terminal.transcript().contains("Expense saved; backup warning: fake backup failure"))
        assertEquals(1, JdbcLedgerStore(database).read().expenses.size)
    }

    @Test
    fun resizeKeepsFormStateAndExpenseValue(): Unit = withTemporaryDirectory("cofrinho-tui-resize-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2))
        val terminal = RecordingTerminal(setupAndSaveKeys("10"), TerminalSize(79, 23))
        terminal.onRead = { index, _ -> if (index == 2) terminal.currentSize = TerminalSize(80, 24) }
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertEquals(1000L, JdbcLedgerStore(database).read().expenses.single().amountMinor)
        assertTrue(terminal.transcript().contains("Terminal too small"))
    }

    @Test
    fun lockRejectsSecondCooperatingSessionAndReleasesAfterClose(): Unit = withTemporaryDirectory("cofrinho-lock-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        val first = LedgerLock.acquire(database)
        assertFailsWith<IllegalStateException> { LedgerLock.acquire(database) }
        first.close()
        LedgerLock.acquire(database).close()
    }
}

private class MutableClock(var date: String) : LedgerClock {
    override fun today(): String = date
    override fun nowUtc(): String = "${date}T12:00:00Z"
}

private class FakeBackupPort(private val failAfterEdit: Boolean = false) : DesktopBackupPort {
    override fun beforeOperation(kind: String): Unit = Unit

    override fun afterFinancialEdit(): Unit {
        if (failAfterEdit) throw IllegalStateException("fake backup failure")
    }

    override fun preserveConflict(contents: String, label: String): Unit = Unit
    override fun preserveLocalConflict(): Unit = Unit
    override fun ordinaryBackups(): List<Path> = emptyList()
}

private class RecordingTerminal(
    private val keys: List<TerminalKey>,
    initialSize: TerminalSize = TerminalSize(80, 24),
    var onRead: (Int, TerminalKey) -> Unit = { _, _ -> },
) : TerminalPort {
    var currentSize: TerminalSize = initialSize
    var closed: Boolean = false
    private var index: Int = 0
    private val lines: StringBuilder = StringBuilder()

    override fun start(): Unit = Unit
    override fun stop(): Unit = Unit
    override fun close(): Unit { closed = true }
    override fun clear(): Unit = Unit
    override fun size(): TerminalSize = currentSize
    override fun write(column: Int, row: Int, value: String): Unit { lines.append(value).append('\n') }
    override fun flush(): Unit = Unit

    override fun readKey(): TerminalKey? {
        val key = keys.getOrElse(index) { TerminalKey(TerminalKeyKind.EOF) }
        onRead(index++, key)
        return key
    }

    fun transcript(): String = lines.toString()
}

private fun writeLedger(path: Path, ledger: Ledger, state: TransferState = TransferState()): Unit {
    val store = JdbcLedgerStore(path)
    store.replace(ledger)
    store.writeTransferState(state.copy(revision = ledger.revision))
}

private fun setupAndSaveKeys(amount: String): List<TerminalKey> = buildList {
    add(character('n'))
    addAll(chars(amount))
    addAll(List(4) { tab() })
    add(enter())
    add(character('q'))
}

private fun firstRunAndSaveKeys(amount: String): List<TerminalKey> = buildList {
    add(tab())
    add(tab())
    add(enter())
    addAll(setupAndSaveKeys(amount))
}

private fun keysForInvalidAmount(): List<TerminalKey> = buildList {
    add(character('n'))
    addAll(chars("bad"))
    addAll(List(4) { tab() })
    add(enter())
    add(escape())
    add(character('y'))
    add(character('q'))
}

private fun keysForExplicitDateAndDebit(): List<TerminalKey> = buildList {
    add(character('n'))
    addAll(chars("10"))
    addAll(List(2) { tab() })
    addAll(List(10) { backspace() })
    addAll(chars("2026-11-01"))
    add(tab())
    add(character('d'))
    add(tab())
    add(enter())
    add(character('q'))
}

private fun chars(value: String): List<TerminalKey> = value.map(::character)
private fun character(value: Char): TerminalKey = TerminalKey(TerminalKeyKind.CHARACTER, value)
private fun tab(): TerminalKey = TerminalKey(TerminalKeyKind.TAB)
private fun enter(): TerminalKey = TerminalKey(TerminalKeyKind.ENTER)
private fun escape(): TerminalKey = TerminalKey(TerminalKeyKind.ESCAPE)
private fun backspace(): TerminalKey = TerminalKey(TerminalKeyKind.BACKSPACE)

private fun <T> withTemporaryDirectory(prefix: String, block: (Path) -> T): T {
    val directory = Files.createTempDirectory(prefix)
    return try {
        block(directory)
    } finally {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) } }
    }
}
