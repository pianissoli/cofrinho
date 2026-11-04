package cofrinho.cli.tui

import cofrinho.cli.DesktopBackupPort
import cofrinho.cli.DesktopClock
import cofrinho.cli.DesktopIdentifiers
import cofrinho.cli.DesktopLedgerSession
import cofrinho.finance.CalendarRules
import cofrinho.finance.Expense
import cofrinho.finance.Ledger
import cofrinho.finance.LedgerClock
import cofrinho.finance.MoneyRules
import cofrinho.finance.PaymentMethod
import java.nio.file.Files
import java.nio.file.Path
import java.time.YearMonth
import java.util.UUID

class CofrinhoTui(
    private val databasePath: Path,
    private val terminalFactory: () -> TerminalPort = LanternaTerminalFactory::open,
    private val clock: LedgerClock = DesktopClock,
    private val backupPort: DesktopBackupPort? = null,
) {
    private var terminal: TerminalPort? = null
    private var session: DesktopLedgerSession? = null
    private var screen: TuiScreen = TuiScreen.OVERVIEW
    private var form: ExpenseDraft? = null
    private var setup: SetupDraft? = null
    private var month: YearMonth = YearMonth.parse(clock.today().substring(0, 7))
    private var selectedExpense: Int = 0
    private var notice: String? = null
    private var fatalError: String? = null
    private var running = true

    fun run(): Unit {
        terminal = terminalFactory()
        val activeTerminal = terminal ?: return
        try {
            activeTerminal.start()
            openLedgerOrSetup()
            while (running) {
                render()
                handle(activeTerminal.readKey())
            }
        } finally {
            session?.close()
            activeTerminal.close()
        }
    }

    private fun openLedgerOrSetup() {
        try {
            if (Files.notExists(databasePath) || Files.size(databasePath) == 0L) {
                setup = SetupDraft()
                screen = TuiScreen.SETUP
                return
            }
            session = DesktopLedgerSession.open(databasePath, clock, DesktopIdentifiers, backupPort ?: defaultBackups())
            session?.store?.read()
            screen = TuiScreen.OVERVIEW
        } catch (error: Exception) {
            fatalError = error.message ?: "Unable to open ledger"
        }
    }

    private fun defaultBackups(): DesktopBackupPort = cofrinho.cli.BackupManager(databasePath)

    private fun render() {
        val activeTerminal = terminal ?: return
        activeTerminal.clear()
        val size = activeTerminal.size()
        if (size.columns < 80 || size.rows < 24) {
            line(0, "Terminal too small: target 80x24; resize to continue. Your input is kept.")
        }
        when {
            fatalError != null -> renderFatal()
            screen == TuiScreen.SETUP -> renderSetup()
            screen == TuiScreen.EXPENSE -> renderExpense()
            else -> renderOverview()
        }
        activeTerminal.flush()
    }

    private fun renderFatal() {
        line(2, "Could not open Cofrinho ledger")
        line(4, trim(fatalError ?: "Unknown error"))
        line(6, "Press q or Escape to exit.")
    }

    private fun renderSetup() {
        val draft = setup ?: return
        line(2, "Cofrinho first-run setup")
        line(4, "No ledger exists at ${databasePath.toAbsolutePath()}")
        field(6, "Currency", draft.currency, draft.focus == 0)
        field(7, "Scale", draft.scale, draft.focus == 1)
        line(9, "Create this ledger with ${draft.currency} and scale ${draft.scale}? Data is stored locally.")
        line(11, if (draft.focus == 2) "> [ Create ledger ]" else "  [ Create ledger ]")
        line(12, if (draft.focus == 3) "> [ Cancel ]" else "  [ Cancel ]")
        footer("Tab focus · Enter confirm · Escape cancel")
        if (draft.error != null) line(15, "Error: ${draft.error}")
    }

    private fun renderOverview() {
        val activeSession = session ?: return
        val summary = runCatching { activeSession.summary(month.toString()) }.getOrElse {
            fatalError = it.message ?: "Unable to read ledger"
            return
        }
        val expenses = activeSession.monthExpenses(summary.month)
        selectedExpense = selectedExpense.coerceIn(0, (expenses.size - 1).coerceAtLeast(0))
        line(2, "Cofrinho · ${summary.month}")
        line(4, "Planned income  ${money(summary.budgetMinor)}")
        line(5, "Spent           ${money(summary.spentMinor)}  ${bar(summary.spentMinor, summary.budgetMinor)}")
        line(6, "Reserved        ${money(summary.reservedMinor)}  ${bar(summary.reservedMinor, summary.budgetMinor)}")
        line(7, "Remaining       ${money(summary.remainingMinor)}")
        line(8, "Available       ${money(summary.availableMinor)}")
        line(9, "Status          ${status(summary.budgetMinor, summary.availableMinor)}")
        line(11, "Expenses")
        if (expenses.isEmpty()) line(13, "No expenses in this month.")
        expenses.take(7).forEachIndexed { index, expense -> renderExpenseRow(13 + index, expense, index == selectedExpense) }
        if (notice != null) line(20, "Notice: ${trim(notice!!)}")
        footer("↑↓ select · n new expense · PgUp/PgDown month · q quit")
    }

    private fun renderExpenseRow(row: Int, expense: Expense, selected: Boolean) {
        val date = expense.expectedOn ?: expense.occurredOn.orEmpty()
        val marker = if (selected) ">" else " "
        line(row, "$marker ${date.padEnd(10)} ${expense.status.name.lowercase().padEnd(9)} ${money(expense.amountMinor).padStart(12)} ${expense.paymentMethod.name.lowercase().padEnd(6)} ${expense.description.orEmpty()}")
    }

    private fun renderExpense() {
        val draft = form ?: return
        line(2, "New expense")
        field(4, "Amount", draft.amount, draft.focus == 0)
        field(5, "Description", draft.description, draft.focus == 1)
        field(6, "Date", draft.date, draft.focus == 2)
        field(7, "Payment", draft.payment.name.lowercase(), draft.focus == 3)
        line(9, if (draft.focus == 4) "> [ Save ]" else "  [ Save ]")
        line(10, if (draft.focus == 5) "> [ Cancel ]" else "  [ Cancel ]")
        line(12, "Default date is today at save; default payment is credit.")
        if (draft.confirmation) line(14, "Discard edited expense? Press y to discard or n to continue.")
        footer("Tab focus · Enter confirm · Escape cancel · type in focused field")
        if (draft.error != null) line(15, "Error: ${draft.error}")
    }

    private fun handle(key: TerminalKey?) {
        if (key == null || key.kind == TerminalKeyKind.EOF) return stop()
        if (fatalError != null) return handleFatal(key)
        when (screen) {
            TuiScreen.SETUP -> handleSetup(key)
            TuiScreen.EXPENSE -> handleExpense(key)
            TuiScreen.OVERVIEW -> handleOverview(key)
        }
    }

    private fun handleFatal(key: TerminalKey) {
        if (key.kind == TerminalKeyKind.ESCAPE || key.isCharacter('q')) stop()
    }

    private fun handleOverview(key: TerminalKey) {
        val activeSession = session ?: return
        when {
            key.isCharacter('q') -> stop()
            key.isCharacter('n') -> openExpense(activeSession)
            key.kind == TerminalKeyKind.PAGE_UP -> month = month.minusMonths(1)
            key.kind == TerminalKeyKind.PAGE_DOWN -> month = month.plusMonths(1)
            key.kind == TerminalKeyKind.UP -> selectedExpense = (selectedExpense - 1).coerceAtLeast(0)
            key.kind == TerminalKeyKind.DOWN -> selectedExpense++
            key.kind == TerminalKeyKind.ENTER -> notice = "Select n to record a new expense."
        }
    }

    private fun openExpense(activeSession: DesktopLedgerSession) {
        try {
            activeSession.requireEditable()
            form = ExpenseDraft(clock.today())
            screen = TuiScreen.EXPENSE
            notice = null
        } catch (error: Exception) {
            notice = error.message ?: "Ledger is read-only"
        }
    }

    private fun handleSetup(key: TerminalKey) {
        val draft = setup ?: return
        when {
            key.kind == TerminalKeyKind.TAB -> draft.focus = (draft.focus + 1) % 4
            key.kind == TerminalKeyKind.BACKTAB -> draft.focus = (draft.focus + 3) % 4
            key.kind == TerminalKeyKind.UP -> draft.focus = (draft.focus + 3) % 4
            key.kind == TerminalKeyKind.DOWN -> draft.focus = (draft.focus + 1) % 4
            key.kind == TerminalKeyKind.ESCAPE -> stop()
            key.kind == TerminalKeyKind.BACKSPACE -> draft.backspace()
            key.kind == TerminalKeyKind.CHARACTER && draft.focus < 2 -> draft.append(key.character ?: return)
            key.kind == TerminalKeyKind.ENTER && draft.focus == 2 -> createLedger(draft)
            key.kind == TerminalKeyKind.ENTER && draft.focus == 3 -> stop()
        }
    }

    private fun createLedger(draft: SetupDraft) {
        try {
            val currency = draft.currency.uppercase()
            require(currency.matches(Regex("[A-Z]{3}"))) { "Invalid currency '$currency'; expected an uppercase three-letter code." }
            val scale = draft.scale.toIntOrNull() ?: error("Invalid scale '${draft.scale}'; expected an integer from 0 to 6.")
            require(scale in 0..6) { "Invalid scale '$scale'; expected an integer from 0 to 6." }
            val opened = DesktopLedgerSession.open(databasePath, clock, DesktopIdentifiers, backupPort ?: defaultBackups())
            runCatching { opened.initialize(Ledger(UUID.randomUUID().toString(), currency, scale)) }
                .getOrElse { error -> opened.close(); throw error }
            session = opened
            setup = null
            screen = TuiScreen.OVERVIEW
        } catch (error: Exception) {
            draft.error = error.message ?: "Unable to create ledger"
        }
    }

    private fun handleExpense(key: TerminalKey) {
        val draft = form ?: return
        when {
            draft.confirmation -> handleDiscardConfirmation(draft, key)
            key.kind == TerminalKeyKind.TAB -> draft.focus = (draft.focus + 1) % 6
            key.kind == TerminalKeyKind.BACKTAB -> draft.focus = (draft.focus + 5) % 6
            key.kind == TerminalKeyKind.LEFT && draft.focus == 3 -> draft.changePayment(PaymentMethod.DEBIT)
            key.kind == TerminalKeyKind.RIGHT && draft.focus == 3 -> draft.changePayment(PaymentMethod.CREDIT)
            key.kind == TerminalKeyKind.UP -> draft.focus = (draft.focus + 5) % 6
            key.kind == TerminalKeyKind.DOWN -> draft.focus = (draft.focus + 1) % 6
            key.kind == TerminalKeyKind.ESCAPE -> requestDiscard(draft)
            key.kind == TerminalKeyKind.ENTER && draft.focus == 4 -> saveExpense(draft)
            key.kind == TerminalKeyKind.ENTER && draft.focus == 5 -> requestDiscard(draft)
            key.kind == TerminalKeyKind.BACKSPACE -> draft.backspace()
            key.kind == TerminalKeyKind.CHARACTER -> draft.append(key.character ?: return)
        }
    }

    private fun requestDiscard(draft: ExpenseDraft) {
        if (!draft.dirty) returnToOverview()
        else draft.confirmation = true
    }

    private fun handleDiscardConfirmation(draft: ExpenseDraft, key: TerminalKey) {
        if (key.isCharacter('y')) returnToOverview()
        if (key.isCharacter('n') || key.kind == TerminalKeyKind.ESCAPE) draft.confirmation = false
    }

    private fun saveExpense(draft: ExpenseDraft) {
        val activeSession = session ?: return
        try {
            val scale = activeSession.store.read().currencyScale
            val amount = runCatching { MoneyRules.parseMinor(draft.amount, scale) }
                .getOrElse { draft.focus = 0; throw it }
            MoneyRules.requirePositive(amount, "expense amount")
            val date = if (draft.dateTouched) draft.date else clock.today()
            runCatching { CalendarRules.requireDate(date) }
                .onFailure { draft.focus = 2 }
                .getOrThrow()
            val saved = activeSession.addExpense(amount, date, draft.payment, draft.description)
            month = YearMonth.parse(date.substring(0, 7))
            notice = saved.backupWarning?.let { "Expense saved; backup warning: $it" } ?: "Expense saved for $date."
            returnToOverview()
        } catch (error: Exception) {
            draft.error = error.message ?: "Invalid expense"
            if (draft.amount.isBlank()) draft.focus = 0
        }
    }

    private fun returnToOverview() {
        form = null
        screen = TuiScreen.OVERVIEW
    }

    private fun stop() {
        running = false
    }

    private fun line(row: Int, value: String) {
        val activeTerminal = terminal ?: return
        if (row >= activeTerminal.size().rows) return
        activeTerminal.write(0, row, trim(value))
    }

    private fun field(row: Int, label: String, value: String, focused: Boolean) {
        line(row, "${if (focused) ">" else " "} ${label.padEnd(13)} $value")
    }

    private fun footer(value: String) = line(22, value)

    private fun trim(value: String): String {
        val columns = terminal?.size()?.columns ?: 80
        return if (value.length <= columns) value else value.take(columns.coerceAtLeast(1))
    }

    private fun money(value: Long): String = MoneyRules.formatMinor(value, session?.store?.read()?.currencyScale ?: 2)

    private fun status(budget: Long, available: Long): String = when {
        budget == 0L -> "No budget defined"
        available < 0L -> "Overcommitted"
        else -> "OK"
    }

    private fun bar(value: Long, total: Long): String {
        if (value <= 0 || total <= 0) return ""
        return "[" + "#".repeat((value.toDouble() / total * 20).toInt().coerceIn(1, 20)) + "]"
    }
}

private enum class TuiScreen { SETUP, OVERVIEW, EXPENSE }

private class SetupDraft {
    var currency: String = "BRL"
    var scale: String = "2"
    var focus: Int = 0
    var error: String? = null

    fun append(character: Char) {
        if (focus == 0 && currency.length < 3) currency += character.uppercaseChar()
        if (focus == 1 && scale.length < 1 && character.isDigit()) scale += character
        error = null
    }

    fun backspace() {
        if (focus == 0 && currency.isNotEmpty()) currency = currency.dropLast(1)
        if (focus == 1 && scale.isNotEmpty()) scale = scale.dropLast(1)
        error = null
    }
}

private class ExpenseDraft(today: String) {
    var amount: String = ""
    var description: String = ""
    var date: String = today
    var payment: PaymentMethod = PaymentMethod.CREDIT
    var focus: Int = 0
    var dateTouched: Boolean = false
    var dirty: Boolean = false
    var confirmation: Boolean = false
    var error: String? = null

    fun append(character: Char) {
        when (focus) {
            0 -> amount += character
            1 -> description += character
            2 -> {
                date += character
                dateTouched = true
            }
            3 -> if (character.equals('d', true)) payment = PaymentMethod.DEBIT else if (character.equals('c', true)) payment = PaymentMethod.CREDIT
        }
        dirty = dirty || focus < 4
        error = null
    }

    fun changePayment(method: PaymentMethod) {
        payment = method
        dirty = true
        error = null
    }

    fun backspace() {
        when (focus) {
            0 -> amount = amount.dropLast(1)
            1 -> description = description.dropLast(1)
            2 -> {
                date = date.dropLast(1)
                dateTouched = true
            }
        }
        dirty = true
        error = null
    }
}

private fun TerminalKey.isCharacter(expected: Char): Boolean = kind == TerminalKeyKind.CHARACTER && character?.equals(expected, true) == true
