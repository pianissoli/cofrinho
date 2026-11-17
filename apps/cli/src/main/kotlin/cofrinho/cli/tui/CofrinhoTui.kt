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
    private var recurringForm: RecurringDraft? = null
    private var setup: SetupDraft? = null
    private var month: YearMonth = YearMonth.parse(clock.today().substring(0, 7))
    private var selectedExpense: Int = 0
    private var expenseViewportStart: Int = 0
    private var selectedRecurring: Int = 0
    private var recurringViewportStart: Int = 0
    private lateinit var frame: TuiFrame
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
        val size = activeTerminal.size()
        frame = TuiFrame(activeTerminal, size)
        // Clear the back buffer on every frame; refresh erases stale rows without flashing the terminal.
        activeTerminal.clear()
        if (size.columns < 80 || size.rows < 24) {
            line(0, "Terminal too small: target 80x24; resize to continue. Your input is kept.")
            activeTerminal.flush()
            return
        }
        when {
            fatalError != null -> renderFatal()
            screen == TuiScreen.SETUP -> renderSetup()
            screen == TuiScreen.EXPENSE -> renderExpense()
            screen == TuiScreen.RECURRING -> renderRecurring()
            screen == TuiScreen.RECURRING_FORM -> renderRecurringForm()
            else -> renderOverview()
        }
        activeTerminal.flush()
    }
    private fun renderFatal() {
        line(2, "Could not open Cofrinho ledger")
        line(4, fatalError ?: "Unknown error")
        line(6, "Press q or Escape to exit.")
    }
    private fun renderSetup() {
        val draft = setup ?: return
        line(2, "Cofrinho first-run setup")
        line(4, "No ledger exists at ${databasePath.toAbsolutePath()}")
        field(6, "Currency", draft.currency, draft.focus == 0)
        field(7, "Scale", draft.scale, draft.focus == 1)
        line(9, "Create this ledger with ${draft.currency} and scale ${draft.scale}? Data is stored locally.")
        frame.button(11, "Create ledger", draft.focus == 2)
        frame.button(12, "Cancel", draft.focus == 3)
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
        val capacity = frame.listCapacity(13)
        expenseViewportStart = viewportStart(expenses.size, selectedExpense, expenseViewportStart, capacity)
        line(2, "Cofrinho · ${summary.month}")
        line(4, "Income          ${money(summary.budgetMinor)}")
        line(5, "Recurring       ${money(summary.recurringMinor)}  ${bar(summary.recurringMinor, summary.budgetMinor)}")
        line(6, "Variable        ${money(summary.spentMinor)}  ${bar(summary.spentMinor, summary.budgetMinor)}")
        line(7, "Used            ${money(summary.usedMinor)}")
        line(8, "Available       ${money(summary.availableMinor)}")
        line(9, "Status          ${status(summary.budgetMinor, summary.availableMinor)}")
        line(11, "Variable expenses (${if (expenses.isEmpty()) "0" else "${expenseViewportStart + 1}-${minOf(expenseViewportStart + capacity, expenses.size)}"}/${expenses.size})")
        if (expenses.isEmpty()) line(13, "No expenses in this month.")
        expenses.drop(expenseViewportStart).take(capacity).forEachIndexed { index, expense -> renderExpenseRow(13 + index, expense, index + expenseViewportStart == selectedExpense) }
        frame.notice(notice)
        footer("↑↓/jk select · ←→/hl month · n expense · r recurring · PgUp/PgDown month · q quit")
    }
    private fun renderRecurring() {
        val activeSession = session ?: return
        val recurring = activeSession.service.resolvedRecurringExpenses(month.toString())
        selectedRecurring = selectedRecurring.coerceIn(0, (recurring.size - 1).coerceAtLeast(0))
        val capacity = frame.listCapacity(6)
        recurringViewportStart = viewportStart(recurring.size, selectedRecurring, recurringViewportStart, capacity)
        line(2, "Recurring expenses · ${month}")
        line(4, "Register once; each active month reduces Available.")
        if (recurring.isEmpty()) line(6, "No recurring expenses.")
        recurring.drop(recurringViewportStart).take(capacity).forEachIndexed { index, expense ->
            val selected = index + recurringViewportStart == selectedRecurring
            val marker = if (selected) ">" else " "
            line(6 + index, "$marker ${expense.name.padEnd(20)} ${money(expense.amountMinor).padStart(12)} / month ${expense.paymentMethod.name.lowercase()}", selected)
        }
        frame.actions("a add · c change · o override month · x clear override · s stop this month")
        frame.notice(notice)
        footer("↑↓/jk select · ←→/hl month · Escape overview · q quit")
    }
    private fun renderExpenseRow(row: Int, expense: Expense, selected: Boolean) {
        val date = expense.occurredOn
        val marker = if (selected) ">" else " "
        line(row, "$marker ${date.padEnd(10)} ${money(expense.amountMinor).padStart(12)} ${expense.paymentMethod.name.lowercase().padEnd(6)} ${expense.description.orEmpty()}", selected)
    }
    private fun renderExpense() {
        val draft = form ?: return
        line(2, "New expense")
        field(4, "Amount", draft.amount, draft.focus == 0)
        field(5, "Description", draft.description, draft.focus == 1)
        field(6, "Date", draft.date, draft.focus == 2)
        field(7, "Payment", draft.payment.name.lowercase(), draft.focus == 3)
        frame.button(9, "Save", draft.focus == 4)
        frame.button(10, "Cancel", draft.focus == 5)
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
            TuiScreen.RECURRING -> handleRecurring(key)
            TuiScreen.RECURRING_FORM -> handleRecurringForm(key)
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
            key.isCharacter('r') -> { screen = TuiScreen.RECURRING; notice = null }
            key.isCharacter('h') || key.kind == TerminalKeyKind.LEFT -> month = month.minusMonths(1)
            key.isCharacter('l') || key.kind == TerminalKeyKind.RIGHT -> month = month.plusMonths(1)
            key.kind == TerminalKeyKind.PAGE_UP -> month = month.minusMonths(1)
            key.kind == TerminalKeyKind.PAGE_DOWN -> month = month.plusMonths(1)
            key.isCharacter('k') || key.kind == TerminalKeyKind.UP -> selectedExpense = (selectedExpense - 1).coerceAtLeast(0)
            key.isCharacter('j') || key.kind == TerminalKeyKind.DOWN -> selectedExpense++
            key.kind == TerminalKeyKind.ENTER -> notice = "Select n to record a new expense."
        }
    }

    private fun handleRecurring(key: TerminalKey) {
        val activeSession = session ?: return
        val recurring = activeSession.service.resolvedRecurringExpenses(month.toString())
        when {
            key.isCharacter('q') -> stop()
            key.kind == TerminalKeyKind.ESCAPE -> screen = TuiScreen.OVERVIEW
            key.isCharacter('h') || key.kind == TerminalKeyKind.LEFT -> month = month.minusMonths(1)
            key.isCharacter('l') || key.kind == TerminalKeyKind.RIGHT -> month = month.plusMonths(1)
            key.kind == TerminalKeyKind.PAGE_UP -> month = month.minusMonths(1)
            key.kind == TerminalKeyKind.PAGE_DOWN -> month = month.plusMonths(1)
            key.isCharacter('a') -> openRecurringForm()
            key.isCharacter('k') || key.kind == TerminalKeyKind.UP -> selectedRecurring = (selectedRecurring - 1).coerceAtLeast(0)
            key.isCharacter('j') || key.kind == TerminalKeyKind.DOWN -> selectedRecurring = (selectedRecurring + 1).coerceAtMost((recurring.size - 1).coerceAtLeast(0))
            key.isCharacter('c') -> openRecurringChange(recurring)
            key.isCharacter('o') -> openRecurringOverride(recurring)
            key.isCharacter('x') -> clearRecurringOverride(recurring)
            key.isCharacter('s') -> stopRecurring(recurring)
        }
    }

    private fun openRecurringForm() {
        try {
            session?.requireEditable() ?: return
            recurringForm = RecurringDraft(RecurringFormMode.ADD, null, month.toString())
            screen = TuiScreen.RECURRING_FORM
        } catch (error: Exception) {
            notice = error.message ?: "Ledger is read-only"
        }
    }

    private fun openRecurringChange(items: List<cofrinho.finance.ResolvedRecurringExpense>) {
        val selected = items.getOrNull(selectedRecurring) ?: return
        try {
            val activeSession = session ?: return
            activeSession.requireEditable()
            recurringForm = RecurringDraft(
                RecurringFormMode.CHANGE,
                selected.id,
                month.toString(),
                selected.amountMinor.takeIf { it > 0 }?.let { MoneyRules.formatMinor(it, activeSession.store.read().currencyScale) }.orEmpty(),
                selected.paymentMethod,
            )
            screen = TuiScreen.RECURRING_FORM
        } catch (error: Exception) {
            notice = error.message ?: "Ledger is read-only"
        }
    }

    private fun openRecurringOverride(items: List<cofrinho.finance.ResolvedRecurringExpense>) {
        val selected = items.getOrNull(selectedRecurring) ?: return
        try {
            val activeSession = session ?: return
            activeSession.requireEditable()
            recurringForm = RecurringDraft(
                RecurringFormMode.OVERRIDE,
                selected.id,
                month.toString(),
                MoneyRules.formatMinor(selected.amountMinor, activeSession.store.read().currencyScale),
            )
            screen = TuiScreen.RECURRING_FORM
        } catch (error: Exception) {
            notice = error.message ?: "Ledger is read-only"
        }
    }

    private fun clearRecurringOverride(items: List<cofrinho.finance.ResolvedRecurringExpense>) {
        val selected = items.getOrNull(selectedRecurring) ?: return
        runRecurringMutation("Override cleared for ${month}; scheduled amount restored") { service ->
            service.clearRecurringExpenseOverride(selected.id, month.toString())
        }
    }

    private fun stopRecurring(items: List<cofrinho.finance.ResolvedRecurringExpense>) {
        val selected = items.getOrNull(selectedRecurring) ?: return
        val fromMonth = month.toString()
        runRecurringMutation("Recurring expense stopped from $fromMonth") { service ->
            service.stopRecurringExpense(selected.id, fromMonth)
        }
    }

    private fun runRecurringMutation(message: String, mutation: (cofrinho.finance.LedgerService) -> Unit) {
        try {
            val activeSession = session ?: return
            activeSession.requireEditable()
            mutation(activeSession.service)
            notice = backupNotice(activeSession, message)
        } catch (error: Exception) {
            notice = error.message ?: "Unable to update recurring expense"
        }
    }

    private fun backupNotice(activeSession: DesktopLedgerSession, message: String): String {
        val warning = runCatching { activeSession.backups.afterFinancialEdit() }.exceptionOrNull()?.message
        return if (warning == null) message else "$message; backup warning: $warning"
    }

    private fun renderRecurringForm() {
        val draft = recurringForm ?: return
        line(2, when (draft.mode) { RecurringFormMode.ADD -> "New recurring expense"; RecurringFormMode.CHANGE -> "Change recurring expense"; RecurringFormMode.OVERRIDE -> "Override one month" })
        if (draft.mode == RecurringFormMode.ADD) field(4, "Name", draft.name, draft.focus == 0)
        field(5, "Amount", draft.amount, draft.focus == if (draft.mode == RecurringFormMode.ADD) 1 else 0)
        field(6, if (draft.mode == RecurringFormMode.OVERRIDE) "Budget month" else "Starting month", draft.fromMonth, draft.focus == if (draft.mode == RecurringFormMode.ADD) 2 else 1)
        if (draft.mode != RecurringFormMode.OVERRIDE) field(7, "Payment", draft.payment.name.lowercase(), draft.focus == if (draft.mode == RecurringFormMode.ADD) 3 else 2)
        val saveFocus = draft.focusCount - 2
        val cancelFocus = draft.focusCount - 1
        frame.button(9, "Save", draft.focus == saveFocus)
        frame.button(10, "Cancel", draft.focus == cancelFocus)
        line(12, if (draft.mode == RecurringFormMode.ADD) "Enter the monthly amount." else "Type to replace amount · Backspace to edit · Override 0 skips this month.")
        footer("Tab focus · Enter confirm · Escape cancel · type in focused field")
        if (draft.error != null) line(15, "Error: ${draft.error}")
    }

    private fun handleRecurringForm(key: TerminalKey) {
        val draft = recurringForm ?: return
        val paymentFocus = if (draft.mode == RecurringFormMode.OVERRIDE) -1 else if (draft.mode == RecurringFormMode.ADD) 3 else 2
        val saveFocus = draft.focusCount - 2
        val cancelFocus = draft.focusCount - 1
        val navigationFocus = if (paymentFocus >= 0) paymentFocus else saveFocus
        when {
            key.kind == TerminalKeyKind.TAB -> draft.focus = (draft.focus + 1) % draft.focusCount
            key.kind == TerminalKeyKind.BACKTAB -> draft.focus = (draft.focus + draft.focusCount - 1) % draft.focusCount
            key.kind == TerminalKeyKind.LEFT && draft.focus == paymentFocus -> { draft.payment = PaymentMethod.DEBIT; draft.paymentTouched = true }
            key.kind == TerminalKeyKind.RIGHT && draft.focus == paymentFocus -> { draft.payment = PaymentMethod.CREDIT; draft.paymentTouched = true }
            key.isCharacter('h') && draft.focus == paymentFocus -> { draft.payment = PaymentMethod.DEBIT; draft.paymentTouched = true }
            key.isCharacter('l') && draft.focus == paymentFocus -> { draft.payment = PaymentMethod.CREDIT; draft.paymentTouched = true }
            key.isCharacter('k') && draft.focus >= navigationFocus -> draft.focus = (draft.focus + draft.focusCount - 1) % draft.focusCount
            key.isCharacter('j') && draft.focus >= navigationFocus -> draft.focus = (draft.focus + 1) % draft.focusCount
            key.kind == TerminalKeyKind.UP -> draft.focus = (draft.focus + draft.focusCount - 1) % draft.focusCount
            key.kind == TerminalKeyKind.DOWN -> draft.focus = (draft.focus + 1) % draft.focusCount
            key.kind == TerminalKeyKind.ESCAPE -> { recurringForm = null; screen = TuiScreen.RECURRING }
            key.kind == TerminalKeyKind.ENTER && draft.focus == saveFocus -> saveRecurring(draft)
            key.kind == TerminalKeyKind.ENTER && draft.focus == cancelFocus -> { recurringForm = null; screen = TuiScreen.RECURRING }
            key.kind == TerminalKeyKind.BACKSPACE -> draft.backspace()
            key.kind == TerminalKeyKind.CHARACTER -> draft.append(key.character ?: return, session?.store?.read()?.currencyScale ?: 2)
        }
    }

    private fun saveRecurring(draft: RecurringDraft) {
        try {
            val activeSession = session ?: return
            activeSession.requireEditable()
            val amount = MoneyRules.parseMinor(draft.amount, activeSession.store.read().currencyScale)
            CalendarRules.requireMonth(draft.fromMonth)
            when (draft.mode) {
                RecurringFormMode.ADD -> { MoneyRules.requirePositive(amount, "recurring expense amount"); activeSession.service.addRecurringExpense(draft.name, amount, draft.fromMonth, draft.payment) }
                RecurringFormMode.CHANGE -> activeSession.service.changeRecurringExpense(draft.recurringExpenseId ?: error("Missing recurring expense ID"), amount, draft.fromMonth, draft.payment.takeIf { draft.paymentTouched })
                RecurringFormMode.OVERRIDE -> activeSession.service.overrideRecurringExpense(draft.recurringExpenseId ?: error("Missing recurring expense ID"), draft.fromMonth, amount)
            }
            val message = if (draft.mode == RecurringFormMode.OVERRIDE) "Override saved for ${draft.fromMonth}." else "Recurring expense saved."
            notice = backupNotice(activeSession, message)
            recurringForm = null
            screen = TuiScreen.RECURRING
        } catch (error: Exception) {
            draft.error = error.message ?: "Invalid recurring expense"
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
            key.isCharacter('h') && draft.focus == 3 -> draft.changePayment(PaymentMethod.DEBIT)
            key.isCharacter('l') && draft.focus == 3 -> draft.changePayment(PaymentMethod.CREDIT)
            key.isCharacter('k') && draft.focus >= 3 -> draft.focus = (draft.focus + 5) % 6
            key.isCharacter('j') && draft.focus >= 3 -> draft.focus = (draft.focus + 1) % 6
            key.kind == TerminalKeyKind.UP -> draft.focus = (draft.focus + 5) % 6
            key.kind == TerminalKeyKind.DOWN -> draft.focus = (draft.focus + 1) % 6
            key.kind == TerminalKeyKind.ESCAPE -> requestDiscard(draft)
            key.kind == TerminalKeyKind.ENTER && draft.focus == 4 -> saveExpense(draft)
            key.kind == TerminalKeyKind.ENTER && draft.focus == 5 -> requestDiscard(draft)
            key.kind == TerminalKeyKind.BACKSPACE -> draft.backspace()
            key.kind == TerminalKeyKind.CHARACTER -> draft.append(key.character ?: return, session?.store?.read()?.currencyScale ?: 2)
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

    private fun line(row: Int, value: String, bold: Boolean = false): Unit = frame.line(row, value, bold)

    private fun field(row: Int, label: String, value: String, focused: Boolean) {
        frame.field(row, label, value, focused)
    }

    private fun footer(value: String): Unit = frame.footer(value)

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

    private fun viewportStart(size: Int, selected: Int, current: Int, capacity: Int): Int {
        if (size <= capacity) return 0
        if (selected < current) return selected
        if (selected >= current + capacity) return selected - capacity + 1
        return current.coerceIn(0, size - capacity)
    }
}
