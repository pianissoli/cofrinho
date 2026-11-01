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

/** Linux SQLite adapter around the shared SQLDelight store. Example: JdbcLedgerStore(path).read(). */
class JdbcLedgerStore(private val path: Path) : LedgerStore {
    private val driver: JdbcSqliteDriver
    private val delegate: SqlDelightLedgerStore

    init {
        val newDatabase = !Files.exists(path) || Files.size(path) == 0L
        driver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
        if (newDatabase) CofrinhoDatabase.Schema.create(driver) else migrateLegacySchema()
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

    private fun migrateLegacySchema() {
        connection().use { connection ->
            connection.createStatement().use { statement -> statement.execute("PRAGMA foreign_keys = ON") }
            addStagedPayloadColumn(connection)
            connection.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS transfer_receipt(checkpoint_id TEXT PRIMARY KEY, payload_digest TEXT NOT NULL)") }
            createIndexes(connection)
        }
    }

    private fun addStagedPayloadColumn(connection: Connection) {
        val columns = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(transfer_state)").use { rows ->
                buildSet { while (rows.next()) add(rows.getString("name")) }
            }
        }
        if ("staged_payload" !in columns) connection.createStatement().use { it.execute("ALTER TABLE transfer_state ADD COLUMN staged_payload TEXT") }
    }

    private fun createIndexes(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE INDEX IF NOT EXISTS expense_expected_on_index ON expense(expected_on)")
            statement.execute("CREATE INDEX IF NOT EXISTS expense_occurred_on_index ON expense(occurred_on)")
            statement.execute("CREATE INDEX IF NOT EXISTS income_schedule_starts_month_index ON income_schedule(starts_month)")
            statement.execute("CREATE INDEX IF NOT EXISTS income_override_budget_month_index ON income_override(budget_month)")
        }
    }
}
