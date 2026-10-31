package caixa.storage

import app.cash.sqldelight.db.SqlDriver
import caixa.finance.Expense
import caixa.finance.ExpenseStatus
import caixa.finance.IncomeOverride
import caixa.finance.IncomeSchedule
import caixa.finance.IncomeSource
import caixa.finance.Ledger
import caixa.finance.LedgerStore
import caixa.finance.PaymentMethod
import caixa.finance.TransferState
import caixa.storage.db.CaixaDatabase

/** SQLDelight-backed ledger store. Example: SqlDelightLedgerStore(driver).read(). */
class SqlDelightLedgerStore(driver: SqlDriver) : LedgerStore {
    private val database = CaixaDatabase(driver)
    private val queries = database.caixaDatabaseQueries

    init {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    }

    override fun read(): Ledger {
        val metadata = queries.selectLedger().executeAsOneOrNull() ?: error("Ledger is not initialized; complete setup first")
        return Ledger(metadata.ledger_id, metadata.currency_code, metadata.currency_scale.toInt(), readSources(), readSchedules(), readOverrides(), readExpenses(), metadata.revision)
    }

    override fun replace(ledger: Ledger) {
        database.transaction {
            replaceRows(ledger)
        }
    }

    override fun replaceWithTransferState(ledger: Ledger, state: TransferState) {
        database.transaction {
            replaceRows(ledger)
            writeTransferStateRows(state)
        }
    }

    override fun replaceWithTransferStateAndReceipt(ledger: Ledger, state: TransferState, checkpointId: String, payloadDigest: String) {
        database.transaction {
            replaceRows(ledger)
            writeTransferStateRows(state)
            queries.insertTransferReceipt(checkpointId, payloadDigest)
        }
    }

    override fun readTransferState(): TransferState {
        val state = queries.selectTransferState().executeAsOneOrNull() ?: return TransferState()
        return TransferState(state.checkpoint_id, state.parent_checkpoint_id, state.payload_digest, state.revision, state.handed_off == 1L, state.staged_payload)
    }

    override fun writeTransferState(state: TransferState) {
        database.transaction {
            writeTransferStateRows(state)
        }
    }

    override fun readTransferReceipts(): Map<String, String> = queries.selectTransferReceipts().executeAsList().associate { it.checkpoint_id to it.payload_digest }

    override fun writeTransferReceipt(checkpointId: String, payloadDigest: String) {
        queries.insertTransferReceipt(checkpointId, payloadDigest)
    }

    private fun replaceRows(ledger: Ledger) {
        queries.deleteExpenses()
        queries.deleteOverrides()
        queries.deleteSchedules()
        queries.deleteSources()
        queries.deleteLedgerMetadata()
        queries.insertLedger(ledger.id, ledger.currencyCode, ledger.currencyScale.toLong(), ledger.revision)
        ledger.sources.forEach { queries.insertSource(it.id, it.name, it.stoppedFromMonth) }
        ledger.schedules.forEach { queries.insertSchedule(it.id, it.sourceId, it.startsMonth, it.amountMinor) }
        ledger.overrides.forEach { queries.insertOverride(it.id, it.sourceId, it.budgetMonth, it.amountMinor) }
        ledger.expenses.forEach { expense -> queries.insertExpense(expense.id, expense.amountMinor, expense.status.name, expense.expectedOn, expense.occurredOn, expense.paymentMethod.name, expense.description) }
    }

    private fun writeTransferStateRows(state: TransferState) {
        queries.deleteTransferState()
        queries.insertTransferState(state.checkpointId, state.parentCheckpointId, state.payloadDigest, state.revision, if (state.handedOff) 1L else 0L, state.stagedPayload)
    }

    private fun readSources(): List<IncomeSource> = queries.selectSources().executeAsList().map { IncomeSource(it.source_id, it.name, it.stopped_from_month) }

    private fun readSchedules(): List<IncomeSchedule> = queries.selectSchedules().executeAsList().map { IncomeSchedule(it.schedule_id, it.source_id, it.starts_month, it.amount_minor) }

    private fun readOverrides(): List<IncomeOverride> = queries.selectOverrides().executeAsList().map { IncomeOverride(it.override_id, it.source_id, it.budget_month, it.amount_minor) }

    private fun readExpenses(): List<Expense> = queries.selectExpenses().executeAsList().map { Expense(it.expense_id, it.amount_minor, ExpenseStatus.valueOf(it.status), it.expected_on, it.occurred_on, PaymentMethod.valueOf(it.payment_method), it.description) }
}
