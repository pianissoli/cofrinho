package cofrinho.cli

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import cofrinho.finance.Ledger
import cofrinho.finance.LedgerStore
import cofrinho.finance.TransferState
import cofrinho.storage.SqlDelightLedgerStore
import cofrinho.storage.db.CofrinhoDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Linux SQLite adapter around the shared SQLDelight store. Example: JdbcLedgerStore(path). */
class JdbcLedgerStore(private val path: Path) : LedgerStore {
    private val driver: JdbcSqliteDriver
    private val delegate: SqlDelightLedgerStore

    init {
        val newDatabase = !Files.exists(path) || Files.size(path) == 0L
        driver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
        if (newDatabase) CofrinhoDatabase.Schema.create(driver) else validateExistingSchema()
        delegate = SqlDelightLedgerStore(driver)
    }

    override fun read(): Ledger = delegate.read()

    override fun replace(ledger: Ledger) {
        delegate.replace(ledger)
    }

    override fun replaceWithTransferState(ledger: Ledger, state: TransferState) {
        delegate.replaceWithTransferState(ledger, state)
    }

    override fun replaceWithTransferStateAndReceipt(ledger: Ledger, state: TransferState, checkpointId: String, payloadDigest: String) {
        delegate.replaceWithTransferStateAndReceipt(ledger, state, checkpointId, payloadDigest)
    }

    override fun readTransferState(): TransferState = delegate.readTransferState()

    override fun writeTransferState(state: TransferState) {
        delegate.writeTransferState(state)
    }

    override fun readTransferReceipts(): Map<String, String> = delegate.readTransferReceipts()

    override fun writeTransferReceipt(checkpointId: String, payloadDigest: String) {
        delegate.writeTransferReceipt(checkpointId, payloadDigest)
    }

    fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").also { connection ->
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
    }

    private fun validateExistingSchema() {
        connection().use { connection ->
            val tables = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString(1)) }
                }
            }
            val required = setOf("schema_marker", "ledger_metadata", "recurring_expense", "recurring_expense_schedule", "recurring_expense_override", "expense")
            val missing = required - tables
            if (missing.isNotEmpty()) throw incompatible("missing tables ${missing.sorted()}")
            val generation = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT generation FROM schema_marker WHERE marker_id = 1").use { rows ->
                    if (!rows.next()) null else rows.getInt(1)
                }
            }
            if (generation != 3) throw incompatible("schema generation '$generation', expected 3")
        }
    }

    private fun incompatible(detail: String): IllegalStateException = IllegalStateException("Incompatible ledger database '$path'; $detail; expected a new Cofrinho schema")
}
