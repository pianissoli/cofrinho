package cofrinho.cli

import cofrinho.finance.CalendarRules
import cofrinho.finance.Ledger
import cofrinho.finance.LedgerService
import cofrinho.finance.MoneyRules
import cofrinho.finance.PaymentMethod
import cofrinho.finance.TransferState
import cofrinho.transfer.Checkpoint
import cofrinho.transfer.ImportDecision
import cofrinho.transfer.PortableLedgerCodec
import cofrinho.transfer.TransferPolicy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

class CofrinhoCli(private val databasePath: Path) {
    fun run(arguments: List<String>) {
        val args = arguments.toMutableList()
        val db = option(args, "--db")?.let(Path::of) ?: databasePath
        val command = args.removeFirstOrNull() ?: return printHelp()
        val commands = setOf("setup", "budget", "expense", "recurring", "income", "transfer", "backup")
        if (command !in commands) throw CliException("Unknown command '$command'; expected setup, budget, expense, recurring, income, transfer, or backup")
        Files.createDirectories(db.toAbsolutePath().parent)
        if (command == "setup") return setup(db, args)
        DesktopLedgerSession.open(db).use { session ->
            when (command) {
                "budget" -> showBudget(session, args)
                "expense" -> expense(session, args)
                "recurring" -> recurring(session, args)
                "income" -> income(session.store, session.backups, args)
                "transfer" -> transfer(session.store, session.backups, args)
                "backup" -> backup(session.store, session.backups, args)
            }
        }
    }

    private fun setup(db: Path, args: MutableList<String>) {
        val currency = requireOption(args, "--currency").uppercase()
        val scale = option(args, "--scale")?.toIntOrNull() ?: 2
        if (!currency.matches(Regex("[A-Z]{3}"))) throw CliException("Invalid currency '$currency'; expected an uppercase three-letter code")
        if (scale !in 0..6) throw CliException("Invalid currency scale '$scale'; expected 0-6")
        DesktopLedgerSession.open(db).use { session ->
            if (runCatching { session.store.read() }.isSuccess) throw CliException("Ledger '$db' already exists; expected a new or uninitialized database")
            session.initialize(Ledger(UUID.randomUUID().toString(), currency, scale))
        }
        println("Created Cofrinho ledger at ${db.toAbsolutePath()} ($currency, scale $scale)")
    }

    private fun showBudget(session: DesktopLedgerSession, args: MutableList<String>) {
        requireCommand(args, "show")
        val month = requireOption(args, "--month")
        val summary = session.summary(month)
        val scale = session.store.read().currencyScale
        if (args.remove("--json")) return printBudgetJson(summary, scale)
        println("Budget for $month")
        println("  income          ${money(summary.budgetMinor, scale)}")
        println("  recurring       ${money(summary.recurringMinor, scale)}")
        println("  variable        ${money(summary.spentMinor, scale)}")
        println("  used            ${money(summary.usedMinor, scale)}")
        println("  available       ${money(summary.availableMinor, scale)}")
        if (summary.budgetMinor == 0L) println("  status          No budget defined")
        if (summary.availableMinor < 0L) println("  status          Overcommitted")
        println("  credit          ${money(summary.creditSpentMinor, scale)}")
        println("  debit           ${money(summary.debitSpentMinor, scale)}")
        summary.dailySpending.forEach { day -> println("  ${day.date} ${bar(day.amountMinor, summary.spentMinor)} ${money(day.amountMinor, scale)}") }
    }

    private fun expense(session: DesktopLedgerSession, args: MutableList<String>) {
        val operation = args.removeFirstOrNull() ?: throw CliException("Missing expense operation; expected add, edit, delete, or list")
        if (operation == "list") return listExpenses(session, args)
        session.requireEditable()
        when (operation) {
            "add" -> {
                val saved = session.addExpense(amount(args, session.store), option(args, "--date"), payment(args), option(args, "--description"))
                reportSavedBackupWarning(saved.backupWarning)
                println("expense ${saved.value.id} · ${money(saved.value.amountMinor, session.store.read().currencyScale)}")
            }
            "edit" -> editExpense(session, args)
            "delete" -> {
                session.service.deleteExpense(requireOption(args, "--id"))
                reportBackup(session.backups)
                println("Expense deleted")
            }
            else -> throw CliException("Unknown expense operation '$operation'; expected add, edit, delete, or list")
        }
    }

    private fun editExpense(session: DesktopLedgerSession, args: MutableList<String>) {
        val id = requireOption(args, "--id")
        val amount = option(args, "--amount")?.let { MoneyRules.parseMinor(it, session.store.read().currencyScale) }
        val updated = session.service.editExpense(id, amount, option(args, "--date"), optionalPayment(args), option(args, "--description"))
        reportBackup(session.backups)
        println("expense ${updated.id} · ${money(updated.amountMinor, session.store.read().currencyScale)}")
    }

    private fun listExpenses(session: DesktopLedgerSession, args: MutableList<String>) {
        val month = requireOption(args, "--month")
        CalendarRules.requireMonth(month)
        val ledger = session.store.read()
        session.monthExpenses(month).forEach { expense ->
            println("${expense.id} ${money(expense.amountMinor, ledger.currencyScale)} ${expense.occurredOn} ${expense.paymentMethod.name.lowercase()} ${expense.description.orEmpty()}")
        }
    }

    private fun recurring(session: DesktopLedgerSession, args: MutableList<String>) {
        val operation = args.removeFirstOrNull() ?: throw CliException("Missing recurring operation; expected add, list, change, override, clear-override, or archive")
        if (operation == "list") return listRecurring(session, args)
        session.requireEditable()
        val service = session.service
        var savedId: String? = null
        when (operation) {
            "add" -> savedId = service.addRecurringExpense(requireOption(args, "--name"), amount(args, session.store), requireOption(args, "--from"), payment(args)).id
            "change" -> savedId = service.changeRecurringExpense(requireOption(args, "--id"), amount(args, session.store), requireOption(args, "--from"), optionalPayment(args)).id
            "override" -> savedId = service.overrideRecurringExpense(requireOption(args, "--id"), requireOption(args, "--month"), amount(args, session.store, allowZero = true)).id
            "clear-override" -> service.clearRecurringExpenseOverride(requireOption(args, "--id"), requireOption(args, "--month"))
            "archive" -> service.stopRecurringExpense(requireOption(args, "--id"), requireOption(args, "--from"))
            else -> throw CliException("Unknown recurring operation '$operation'; expected add, list, change, override, clear-override, or archive")
        }
        reportBackup(session.backups)
        println("Recurring expense $operation saved${savedId?.let { " · $it" }.orEmpty()}")
    }

    private fun listRecurring(session: DesktopLedgerSession, args: MutableList<String>) {
        val month = requireOption(args, "--month")
        val scale = session.store.read().currencyScale
        session.service.resolvedRecurringExpenses(month).forEach { expense ->
            println("${expense.id} ${expense.name} ${money(expense.amountMinor, scale)} ${expense.paymentMethod.name.lowercase()} $month")
        }
    }

    private fun income(store: JdbcLedgerStore, backups: DesktopBackupPort, args: MutableList<String>) {
        requireEditable(store)
        val operation = args.removeFirstOrNull() ?: throw CliException("Missing income operation; expected add, change, override, clear-override, or archive")
        val service = LedgerService(store, DesktopClock, DesktopIdentifiers)
        when (operation) {
            "add" -> service.addIncomeSource(requireOption(args, "--name"), amount(args, store), requireOption(args, "--from"))
            "change" -> service.changeRecurringIncome(requireOption(args, "--id"), amount(args, store), requireOption(args, "--from"))
            "override" -> service.overrideIncome(requireOption(args, "--id"), requireOption(args, "--month"), amount(args, store, allowZero = true))
            "clear-override" -> service.clearIncomeOverride(requireOption(args, "--id"), requireOption(args, "--month"))
            "archive" -> service.stopIncome(requireOption(args, "--id"), requireOption(args, "--from"))
            else -> throw CliException("Unknown income operation '$operation'; expected add, change, override, clear-override, or archive")
        }
        reportBackup(backups)
        println("Income $operation saved")
    }

    private fun transfer(store: JdbcLedgerStore, backups: DesktopBackupPort, args: MutableList<String>) {
        when (args.removeFirstOrNull()) {
            "export" -> exportLedger(store, args)
            "import" -> importLedger(store, backups, args)
            "resume" -> resumeEditing(store)
            else -> throw CliException("Missing transfer operation; expected export, import, or resume")
        }
    }

    private fun backup(store: JdbcLedgerStore, backups: DesktopBackupPort, args: MutableList<String>) {
        when (args.removeFirstOrNull()) {
            "list" -> backups.ordinaryBackups().forEach(::println)
            "retry" -> { backups.afterFinancialEdit(); println("Daily backup complete") }
            "restore" -> restoreBackup(store, backups, Path.of(requireOption(args, "--file")), args.remove("--use-backup"))
            else -> throw CliException("Missing backup operation; expected list, retry, or restore --file PATH --use-backup")
        }
    }

    private fun restoreBackup(store: JdbcLedgerStore, backups: DesktopBackupPort, source: Path, confirmed: Boolean) {
        if (!confirmed) throw CliException("Restoring backup '$source' requires --use-backup after review")
        if (!Files.isRegularFile(source)) throw CliException("Backup '$source' does not exist; expected a readable SQLite backup file")
        val restored = JdbcLedgerStore(source).read()
        backups.beforeOperation("restore")
        store.replaceWithTransferState(restored, TransferState(revision = restored.revision, handedOff = true))
        println("Restored ${source.toAbsolutePath()}; run transfer resume after confirming the recovery warning")
    }

    private fun exportLedger(store: JdbcLedgerStore, args: MutableList<String>) {
        val destination = Path.of(requireOption(args, "--file"))
        val state = store.readTransferState()
        val contents = if (state.handedOff && state.stagedPayload != null) state.stagedPayload else {
            requireEditable(store)
            val ledger = store.read()
            val snapshot = PortableLedgerCodec.snapshot(ledger, UUID.randomUUID().toString(), state.checkpointId, DesktopClock.nowUtc())
            PortableLedgerCodec.encode(snapshot).also { payload ->
                val digest = sha256(payload)
                store.writeTransferState(TransferState(snapshot.checkpointId, snapshot.parentCheckpointId, digest, ledger.revision, true, payload))
                store.writeTransferReceipt(snapshot.checkpointId, digest)
            }
        }
        Files.createDirectories(destination.toAbsolutePath().parent)
        Files.writeString(destination, contents)
        println("Exported ${destination.toAbsolutePath()}")
    }

    private fun importLedger(store: JdbcLedgerStore, backups: DesktopBackupPort, args: MutableList<String>) {
        val source = Path.of(requireOption(args, "--file"))
        val useImport = args.remove("--use-import")
        val keepLocal = args.remove("--keep-local")
        val contents = readImport(source)
        val incoming = PortableLedgerCodec.decode(contents)
        val state = store.readTransferState()
        val digest = sha256(contents)
        rejectCheckpointReuse(state, incoming.checkpointId, digest)
        val checkpoint = state.checkpointId?.let { Checkpoint(it, state.parentCheckpointId, state.payloadDigest.orEmpty(), state.revision) }
        val current = store.read()
        val inspection = TransferPolicy().inspect(current, checkpoint, incoming, digest, store.readTransferReceipts())
        if (inspection.decision == ImportDecision.NO_OP) return println("This checkpoint was already imported; no changes made")
        requireChoice(inspection.decision, useImport, keepLocal)
        if (keepLocal) {
            if (inspection.decision == ImportDecision.CONFLICT) backups.preserveLocalConflict()
            backups.preserveConflict(contents, "incoming")
            return println("Kept this device's ledger; incoming file was preserved")
        }
        if (inspection.decision == ImportDecision.CONFLICT) {
            backups.preserveLocalConflict()
            backups.preserveConflict(contents, "incoming")
        }
        backups.beforeOperation("import")
        val imported = inspection.incoming.copy(revision = nextRevision(current.revision))
        store.replaceWithTransferStateAndReceipt(imported, TransferState(incoming.checkpointId, incoming.parentCheckpointId, digest, imported.revision, false, null), incoming.checkpointId, digest)
        println("Imported ${incoming.checkpointId}")
    }

    private fun readImport(source: Path): String {
        if (Files.size(source) > 8_000_000L) throw CliException("Import file '$source' is too large; expected at most 8000000 bytes")
        return Files.readString(source)
    }

    private fun rejectCheckpointReuse(state: TransferState, checkpointId: String, digest: String) {
        if (state.checkpointId == checkpointId && state.payloadDigest != null && state.payloadDigest != digest) throw CliException("Checkpoint '$checkpointId' was reused with different bytes; expected the original payload")
    }

    private fun requireChoice(decision: ImportDecision, useImport: Boolean, keepLocal: Boolean) {
        if (decision == ImportDecision.CONFLICT && !useImport && !keepLocal) throw CliException("Conflicting ledger history; review the file, then rerun with --use-import or --keep-local")
    }

    private fun resumeEditing(store: JdbcLedgerStore) {
        val state = store.readTransferState()
        if (!state.handedOff) return println("This ledger is already editable")
        store.writeTransferState(state.copy(handedOff = false, stagedPayload = null))
        println("Editing resumed on this device; future imports may require a conflict choice")
    }

    private fun requireEditable(store: JdbcLedgerStore) {
        if (store.readTransferState().handedOff) throw CliException("Ledger is handed off; import the returned file or run transfer resume after confirming the warning")
    }

    private fun amount(args: MutableList<String>, store: JdbcLedgerStore, allowZero: Boolean = false): Long {
        val value = MoneyRules.parseMinor(requireOption(args, "--amount"), store.read().currencyScale)
        if (!allowZero) MoneyRules.requirePositive(value, "amount")
        return value
    }

    private fun payment(args: MutableList<String>): PaymentMethod = optionalPayment(args) ?: PaymentMethod.CREDIT
    private fun optionalPayment(args: MutableList<String>): PaymentMethod? = option(args, "--payment")?.uppercase()?.let { runCatching { PaymentMethod.valueOf(it) }.getOrElse { throw CliException("Invalid payment method '$it'; expected credit or debit") } }

    private fun option(args: MutableList<String>, name: String): String? {
        val index = args.indexOf(name)
        if (index < 0) return null
        if (index + 1 >= args.size) throw CliException("Missing value after '$name'; expected an argument")
        args.removeAt(index)
        return args.removeAt(index)
    }

    private fun requireOption(args: MutableList<String>, name: String): String = option(args, name) ?: throw CliException("Missing '$name'; expected a value")
    private fun requireCommand(args: MutableList<String>, expected: String) { if (args.removeFirstOrNull() != expected) throw CliException("Expected command '$expected'") }
    private fun money(value: Long, scale: Int): String = MoneyRules.formatMinor(value, scale)

    private fun nextRevision(current: Long): Long {
        if (current == Long.MAX_VALUE) throw CliException("Revision '$current' cannot advance; expected a lower signed 64-bit revision")
        return current + 1L
    }

    private fun bar(value: Long, total: Long): String {
        if (value <= 0 || total <= 0) return ""
        return "#".repeat((value / total.toDouble() * 20).toInt().coerceIn(1, 20))
    }

    private fun printBudgetJson(summary: cofrinho.finance.BudgetSummary, scale: Int) {
        println(buildJsonObject {
            put("report_version", 2)
            put("month", summary.month)
            put("budget", money(summary.budgetMinor, scale))
            put("spent", money(summary.spentMinor, scale))
            put("recurring", money(summary.recurringMinor, scale))
            put("used", money(summary.usedMinor, scale))
            put("available", money(summary.availableMinor, scale))
            put("credit", money(summary.creditSpentMinor, scale))
            put("debit", money(summary.debitSpentMinor, scale))
            put("status", when { summary.budgetMinor == 0L -> "NO_BUDGET"; summary.availableMinor < 0L -> "OVERCOMMITTED"; else -> "OK" })
        })
    }
}

private class CliException(message: String) : IllegalArgumentException(message)

fun printHelp() = println("""
Cofrinho offline ledger
  cofrinho                         open the interactive terminal interface
  cofrinho --db PATH               open PATH in the interactive interface
  cofrinho --help                  show this help without opening a ledger
  setup --currency BRL [--scale 2]
  budget show --month YYYY-MM [--json]
  expense add --amount 42.90 [--date YYYY-MM-DD] [--payment credit|debit] [--description text]
  expense edit --id ID [--amount 42.90] [--date YYYY-MM-DD] [--payment credit|debit]
  expense delete --id ID
  expense list --month YYYY-MM
  recurring add --name Rent --amount 1000 --from YYYY-MM [--payment credit|debit]
  recurring list --month YYYY-MM
  recurring change --id ID --amount 1100 --from YYYY-MM [--payment credit|debit]
  recurring override --id ID --month YYYY-MM --amount 0
  recurring clear-override --id ID --month YYYY-MM
  recurring archive --id ID --from YYYY-MM
  income add --name Salary --amount 3000 --from YYYY-MM
  income change --id ID --amount 3100 --from YYYY-MM
  income override --id ID --month YYYY-MM --amount 0
  income clear-override --id ID --month YYYY-MM
  income archive --id ID --from YYYY-MM
  transfer export --file ledger.cofrinho
  transfer import --file ledger.cofrinho [--use-import|--keep-local]
  transfer resume
  backup list
  backup retry
  backup restore --file PATH --use-backup
""".trimIndent())

private fun reportBackup(backups: DesktopBackupPort) {
    runCatching { backups.afterFinancialEdit() }.onFailure { reportBackupFailure(it.message) }
}

private fun reportSavedBackupWarning(warning: String?) {
    if (warning != null) reportBackupFailure(warning)
}

private fun reportBackupFailure(warning: String?) {
    println("Warning: financial edit saved, but backup failed: ${warning ?: "unknown backup error"}")
}

private fun sha256(contents: String): String = MessageDigest.getInstance("SHA-256").digest(contents.toByteArray()).joinToString("") { "%02x".format(it) }
