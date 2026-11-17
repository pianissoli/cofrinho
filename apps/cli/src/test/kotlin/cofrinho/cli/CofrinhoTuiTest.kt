package cofrinho.cli

import cofrinho.cli.tui.CofrinhoTui
import cofrinho.cli.tui.TerminalKey
import cofrinho.cli.tui.TerminalKeyKind
import cofrinho.cli.tui.TerminalPort
import cofrinho.finance.Expense
import cofrinho.finance.Ledger
import cofrinho.finance.LedgerClock
import cofrinho.finance.PaymentMethod
import cofrinho.finance.RecurringExpense
import cofrinho.finance.RecurringExpenseSchedule
import cofrinho.finance.TransferState
import com.googlecode.lanterna.TerminalSize
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun switchingScreensErasesPreviousRows() = withTemporaryDirectory("cofrinho-tui-redraw-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, Ledger("ledger", "BRL", 2))
        val terminal = RecordingTerminal(listOf(character('n'), escape(), character('r'), character('q')))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        val expenseFrame = terminal.frames.first { it.contains("New expense") }
        val recurringFrame = terminal.frames.first { it.contains("Recurring expenses ·") }
        assertFalse(expenseFrame.contains("Available"))
        assertFalse(recurringFrame.contains("Default date"))
        assertFalse(recurringFrame.contains("Variable expenses"))
    }

    @Test
    fun emptyMonthErasesAllPreviousExpenseRows() = withTemporaryDirectory("cofrinho-tui-empty-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        val expenses = (0..7).map { Expense("id-$it", 100, "2026-11-03", PaymentMethod.CREDIT, "old-row-$it") }
        writeLedger(database, Ledger("ledger", "BRL", 2, expenses = expenses))
        val terminal = RecordingTerminal(listOf(arrowRight(), character('q')))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        val emptyFrame = terminal.frames.last()
        assertTrue(emptyFrame.contains("No expenses in this month."))
        assertFalse(emptyFrame.contains("old-row-"))
    }

    @Test
    fun selectedFieldsAndButtonsAreBold() = withTemporaryDirectory("cofrinho-tui-focus-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        val terminal = RecordingTerminal(listOf(tab(), tab(), enter(), character('n'), tab(), tab(), tab(), tab()))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertTrue(terminal.frames.first().boldRows.contains(6))
        assertTrue(terminal.frames.first { it.contains("> [ Create ledger ]") }.boldRows.contains(11))
        assertTrue(terminal.frames.first { it.contains("New expense") }.boldRows.contains(4))
        assertEquals(setOf(9), terminal.frames.last().boldRows)
    }

    @Test
    fun recurringSelectionMovesBoldToSelectedRow() = withTemporaryDirectory("cofrinho-tui-selection-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, recurringLedger())
        val terminal = RecordingTerminal(listOf(character('r'), character('j'), character('q')))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertEquals(setOf(6), terminal.frames.first { it.contains("Recurring expenses ·") }.boldRows)
        assertEquals(setOf(7), terminal.frames.last().boldRows)
    }

    @Test
    fun variableSelectionMovesBoldToSelectedRow() = withTemporaryDirectory("cofrinho-tui-variable-focus-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        val expenses = (0..1).map { Expense("id-$it", 100, "2026-11-03", PaymentMethod.CREDIT) }
        writeLedger(database, Ledger("ledger", "BRL", 2, expenses = expenses))
        val terminal = RecordingTerminal(listOf(character('j'), character('q')))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertEquals(setOf(13), terminal.frames.first().boldRows)
        assertEquals(setOf(14), terminal.frames.last().boldRows)
    }

    @Test
    fun tallRecurringListDoesNotOverwriteItsActionsOrSelection() = withTemporaryDirectory("cofrinho-tui-tall-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        val recurring = (0..29).map { RecurringExpense("item-$it", "item-$it") }
        val schedules = recurring.map { RecurringExpenseSchedule("schedule-${it.id}", it.id, "2026-11", 100) }
        writeLedger(database, Ledger("ledger", "BRL", 2, recurringExpenses = recurring, recurringExpenseSchedules = schedules))
        val keys = buildList { add(character('r')); repeat(29) { add(character('j')) }; add(character('q')) }
        val terminal = RecordingTerminal(keys, TerminalSize(100, 40))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        val last = terminal.frames.last()
        (1..29).forEach { assertTrue(last.rows.getValue(5 + it).contains("item-$it ")) }
        assertTrue(last.rows.getValue(35).contains("o override month"))
        assertEquals(setOf(34), last.boldRows)
    }

    @Test
    fun recurringOverrideClearAndStopUpdateSelectedExpenseWithVisibleFeedback() = withTemporaryDirectory("cofrinho-tui-actions-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, recurringLedger())
        val keys = listOf(character('r'), character('j'), character('o'), character('0'), tab(), tab(), enter(), character('x'), character('s'), arrowRight(), arrowLeft(), arrowLeft(), character('q'))
        val terminal = RecordingTerminal(keys)
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        val ledger = JdbcLedgerStore(database).read()
        assertTrue(terminal.frames.any { it.contains("Override saved for 2026-11") && it.rows.getValue(7).contains("0.00") })
        assertTrue(terminal.frames.any { it.contains("Override cleared for 2026-11") && it.rows.getValue(7).contains("20.00") })
        assertTrue(terminal.frames.last().contains("Recurring expense stopped from 2026-11"))
        assertTrue(terminal.frames.any { it.contains("Recurring expenses · 2026-12") && it.rows.getValue(7).contains("0.00") })
        assertTrue(terminal.frames.last().rows.getValue(7).contains("20.00"))
        assertEquals("2026-11", ledger.recurringExpenses.single { it.id == "utilities" }.stoppedFromMonth)
        assertEquals(null, ledger.recurringExpenses.single { it.id == "rent" }.stoppedFromMonth)
        assertTrue(ledger.recurringExpenseOverrides.isEmpty())
    }

    @Test
    fun overrideAcceptsReplacementAmountAndPersistsOnlyThatMonth() = withTemporaryDirectory("cofrinho-tui-override-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        writeLedger(database, recurringLedger())
        val keys = listOf(character('r'), character('o'), character('2'), character('5'), tab(), tab(), enter(), character('q'))
        CofrinhoTui(database, { RecordingTerminal(keys) }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        val ledger = JdbcLedgerStore(database).read()
        val saved = ledger.recurringExpenseOverrides.single()
        assertEquals("rent", saved.recurringExpenseId)
        assertEquals("2026-11", saved.budgetMonth)
        assertEquals(2500L, saved.amountMinor)
        assertEquals(10000L, ledger.recurringExpenseSchedules.single { it.recurringExpenseId == "rent" }.amountMinor)
    }

    @Test
    fun recurringActionsExplainReadOnlyRefusal() = withTemporaryDirectory("cofrinho-tui-blocked-") { directory ->
        val database = directory.resolve("ledger.sqlite")
        val ledger = recurringLedger()
        writeLedger(database, ledger, TransferState(handedOff = true))
        val terminal = RecordingTerminal(listOf(character('r'), character('o'), character('x'), character('s'), character('q')))
        CofrinhoTui(database, { terminal }, MutableClock("2026-11-03"), FakeBackupPort()).run()
        assertTrue(terminal.frames.takeLast(3).all { it.contains("Ledger is handed off") })
        assertEquals(ledger, JdbcLedgerStore(database).read())
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
    private val rows: MutableMap<Int, String> = mutableMapOf()
    private val boldRows: MutableSet<Int> = mutableSetOf()
    val frames: MutableList<RecordedFrame> = mutableListOf()
    override fun start(): Unit = Unit
    override fun stop(): Unit = Unit
    override fun clear() { rows.clear(); boldRows.clear() }
    override fun size(): TerminalSize = currentSize
    override fun write(column: Int, row: Int, value: String, bold: Boolean) {
        output.append(value).append('\n')
        rows[row] = value
        if (bold) boldRows.add(row) else boldRows.remove(row)
    }
    override fun flush() { frames.add(RecordedFrame(rows.toMap(), boldRows.toSet())) }
    override fun readKey(): TerminalKey = keys.getOrElse(index++) { TerminalKey(TerminalKeyKind.EOF) }
    override fun close(): Unit = Unit
    fun transcript(): String = output.toString()
}

private data class RecordedFrame(val rows: Map<Int, String>, val boldRows: Set<Int>) {
    fun contains(value: String): Boolean = rows.values.any { it.contains(value) }
}

private fun recurringLedger(): Ledger = Ledger(
    "ledger", "BRL", 2,
    recurringExpenses = listOf(RecurringExpense("rent", "Rent"), RecurringExpense("utilities", "Utilities")),
    recurringExpenseSchedules = listOf(
        RecurringExpenseSchedule("rent-schedule", "rent", "2026-10", 10000),
        RecurringExpenseSchedule("utilities-schedule", "utilities", "2026-10", 2000),
    ),
)

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
private fun escape(): TerminalKey = TerminalKey(TerminalKeyKind.ESCAPE)

private fun <T> withTemporaryDirectory(prefix: String, block: (Path) -> T): T {
    val directory = Files.createTempDirectory(prefix)
    return try { block(directory) } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
}
