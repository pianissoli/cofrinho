package cofrinho.cli

import cofrinho.cli.tui.CofrinhoTui
import cofrinho.cli.tui.TerminalKey
import cofrinho.cli.tui.TerminalKeyKind
import cofrinho.cli.tui.TerminalPort
import com.googlecode.lanterna.TerminalSize
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CofrinhoTuiTest {
    @Test
    fun setupAndKeyboardExpenseUseTodayAndCreditDefaults(): Unit {
        val directory = Files.createTempDirectory("cofrinho-tui-")
        val database = directory.resolve("ledger.sqlite")
        val keys = buildList {
            addAll(listOf(tab(), tab(), enter()))
            add(character('n'))
            addAll("10".map(::character))
            addAll(List(4) { tab() })
            add(enter())
            add(character('q'))
        }
        CofrinhoTui(database, { FakeTerminal(keys) }, FixedClock("2026-11-03")).run()
        val expense = JdbcLedgerStore(database).read().expenses.single()
        assertEquals("2026-11-03", expense.occurredOn)
        assertEquals("CREDIT", expense.paymentMethod.name)
        assertEquals(1000L, expense.amountMinor)
    }

    @Test
    fun lockRejectsSecondCooperatingSessionAndReleasesAfterClose(): Unit {
        val database = Files.createTempDirectory("cofrinho-lock-").resolve("ledger.sqlite")
        val first = LedgerLock.acquire(database)
        assertFailsWith<IllegalStateException> { LedgerLock.acquire(database) }
        first.close()
        LedgerLock.acquire(database).close()
    }
}

private class FixedClock(private val date: String) : cofrinho.finance.LedgerClock {
    override fun today(): String = date
    override fun nowUtc(): String = "2026-11-03T12:00:00Z"
}

private class FakeTerminal(private val keys: List<TerminalKey>) : TerminalPort {
    private var index: Int = 0
    override fun start() = Unit
    override fun stop() = Unit
    override fun close() = Unit
    override fun clear() = Unit
    override fun size(): TerminalSize = TerminalSize(80, 24)
    override fun write(column: Int, row: Int, value: String) = Unit
    override fun flush() = Unit
    override fun readKey(): TerminalKey? = keys.getOrElse(index++) { TerminalKey(TerminalKeyKind.EOF) }
}

private fun tab(): TerminalKey = TerminalKey(TerminalKeyKind.TAB)
private fun enter(): TerminalKey = TerminalKey(TerminalKeyKind.ENTER)
private fun character(value: Char): TerminalKey = TerminalKey(TerminalKeyKind.CHARACTER, value)
