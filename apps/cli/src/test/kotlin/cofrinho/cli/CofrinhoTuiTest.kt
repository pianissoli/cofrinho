package cofrinho.cli

import cofrinho.cli.tui.CofrinhoTui
import cofrinho.cli.tui.TerminalKey
import cofrinho.cli.tui.TerminalKeyKind
import cofrinho.cli.tui.TerminalPort
import cofrinho.finance.Expense
import cofrinho.finance.Ledger
import cofrinho.finance.LedgerClock
import cofrinho.finance.PaymentMethod
import cofrinho.finance.TransferState
import com.googlecode.lanterna.TerminalSize
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CofrinhoTuiTest {
    @Test
    fun setupAndKeyboardExpenseUseTodayAndCreditDefaults() = withTemporaryDirectory("cofrinho-tui-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        val terminal = RecordingTerminal(setupAndSaveKeys("10"))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        val expense = JdbcLedgerStore(database).read().expenses.single()
        assertEquals("2026-11-03", expense.occurredOn)
        assertEquals(PaymentMethod.CREDIT, expense.paymentMethod)
        assertEquals(1000L, expense.amountMinor)
    }

    @Test
    fun amountRejectsLettersWithoutWritingExpense() = withTemporaryDirectory("cofrinho-tui-invalid-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2))
        val terminal = RecordingTerminal(listOf(character('n'), character('b'), character('a'), character('d'), tab(), tab(), tab(), tab(), enter(), character('q')))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertTrue(JdbcLedgerStore(database).read().expenses.isEmpty())
    }

    @Test
    fun vimAndArrowMonthNavigationChangeSelectedMonth() = withTemporaryDirectory("cofrinho-tui-month-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2))
        val terminal = RecordingTerminal(listOf(character('h'), arrowRight(), character('l'), arrowLeft(), character('q')))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertTrue(terminal.transcript().contains("Cofrinho · 2026-10"))
    }

    @Test
    fun scrollingReachesExpensesBeyondFirstViewport() = withTemporaryDirectory("cofrinho-tui-scroll-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        val expenses = (0 until 12).map { index -> Expense("expense-$index", 100L, "2026-11-03", PaymentMethod.CREDIT, "expense-$index") }
        writeLedger(database, Ledger("ledger", "BRL", 2, expenses = expenses))
        val keys = buildList { repeat(11) { add(character('j')) }; add(character('q')) }
        val terminal = RecordingTerminal(keys)
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertTrue(terminal.transcript().contains("expense-11"))
    }

    @Test
    fun recurringScreenRegistersAndOverridesSelectedMonth() = withTemporaryDirectory("cofrinho-tui-recurring-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2))
        val keys = buildList {
            add(character('r')); add(character('a'))
            "rent".forEach { add(character(it)) }
            add(tab()); add(character('1')); add(character('0')); repeat(3) { add(tab()) }
            add(enter()); add(character('o')); repeat(2) { add(tab()) }; add(enter()); add(character('q'))
        }
        CofrinhoTui(database, { RecordingTerminal(keys) }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        val ledger = JdbcLedgerStore(database).read()
        assertEquals(1, ledger.recurringExpenses.size)
        assertEquals(1, ledger.recurringExpenseOverrides.size)
        assertEquals("2026-11", ledger.recurringExpenseOverrides.single().budgetMonth)
    }

    @Test
    fun handoffBlocksExpenseForm() = withTemporaryDirectory("cofrinho-tui-handoff-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2), TransferState(handedOff = true))
        val terminal = RecordingTerminal(listOf(character('n'), character('q')))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertTrue(terminal.transcript().contains("Ledger is handed off"))
    }
}

private class MutableClock(private val date: String) : LedgerClock {
    override fun today(): String = date
    override fun nowUtc(): String = "${date}T12:00:00Z"
}

private class FakeBackupPort : DesktopBackupPort {
    override fun beforeOperation(kind: String): Unit = Unit
    override fun afterFinancialEdit(): Unit = Unit
    override fun preserveConflict(contents: String, label: String): Unit = Unit
    override fun preserveLocalConflict(): Unit = Unit
    override fun ordinaryBackups(): List<Path> = emptyList()
}

private class RecordingTerminal(
    private val keys: List<TerminalKey>,
    private var currentSize: TerminalSize = TerminalSize(80, 24),
) : TerminalPort {
    private var index = 0
    private val output = StringBuilder()
    override fun start(): Unit = Unit
    override fun stop(): Unit = Unit
    override fun clear(): Unit = Unit
    override fun size(): TerminalSize = currentSize
    override fun write(column: Int, row: Int, value: String): Unit { output.append(value).append('\n') }
    override fun flush(): Unit = Unit
    override fun readKey(): TerminalKey = keys.getOrElse(index++) { TerminalKey(TerminalKeyKind.EOF) }
    override fun close(): Unit = Unit
    fun transcript(): String = output.toString()
}

private fun writeLedger(path: Path, ledger: Ledger, state: TransferState = TransferState()) {
    val store = JdbcLedgerStore(path)
    store.replace(ledger)
    store.writeTransferState(state.copy(revision = ledger.revision))
}

private fun setupAndSaveKeys(amount: String): List<TerminalKey> = buildList {
    add(tab()); add(tab()); add(enter()); add(character('n')); amount.forEach { add(character(it)) }; repeat(4) { add(tab()) }; add(enter()); add(character('q'))
}

private fun character(value: Char): TerminalKey = TerminalKey(TerminalKeyKind.CHARACTER, value)
private fun tab(): TerminalKey = TerminalKey(TerminalKeyKind.TAB)
private fun enter(): TerminalKey = TerminalKey(TerminalKeyKind.ENTER)
private fun arrowRight(): TerminalKey = TerminalKey(TerminalKeyKind.RIGHT)
private fun arrowLeft(): TerminalKey = TerminalKey(TerminalKeyKind.LEFT)

private fun <T> withTemporaryDirectory(prefix: String, block: (Path) -> T): T {
    val directory = Files.createTempDirectory(prefix)
    return try { block(directory) } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
}
