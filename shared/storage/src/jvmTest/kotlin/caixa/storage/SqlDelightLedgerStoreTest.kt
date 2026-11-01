package caixa.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import caixa.finance.Ledger
import caixa.finance.TransferState
import caixa.storage.db.CaixaDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlDelightLedgerStoreTest {
    /** Preserves checkpoint revisions used for conflict detection. Example: checkpointRevisionRoundTripsIndependently(). */
    @Test
    fun checkpointRevisionRoundTripsIndependently(): Unit = withMemoryStore { store, _ ->
        val ledger: Ledger = Ledger("ledger", "BRL", 2, revision = 4_294_967_297L)
        val checkpoint: TransferState = TransferState(
            checkpointId = "checkpoint",
            parentCheckpointId = "parent",
            payloadDigest = "digest",
            revision = 4_294_967_296L,
            handedOff = true,
            stagedPayload = "staged snapshot",
        )
        store.replaceWithTransferState(ledger, checkpoint)
        assertEquals(ledger, store.read())
        assertEquals(checkpoint, store.readTransferState())
    }

    /** Keeps initial checkpoint state consistent with the domain default. Example: transferRevisionDefaultsToZero(). */
    @Test
    fun transferRevisionDefaultsToZero(): Unit = withMemoryStore { store, driver ->
        driver.execute(null, "INSERT INTO transfer_state(state_id) VALUES (1)", 0)
        assertEquals(TransferState(), store.readTransferState())
    }

    private fun withMemoryStore(assertPersistedState: (SqlDelightLedgerStore, JdbcSqliteDriver) -> Unit): Unit {
        val driver: JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            CaixaDatabase.Schema.create(driver)
            assertPersistedState(SqlDelightLedgerStore(driver), driver)
        } finally {
            driver.close()
        }
    }
}
