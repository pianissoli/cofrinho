package cofrinho.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import cofrinho.finance.Ledger
import cofrinho.finance.LedgerStore
import cofrinho.finance.TransferState
import cofrinho.storage.SqlDelightLedgerStore
import cofrinho.storage.db.CofrinhoDatabase

class AndroidLedgerStore(context: Context) : LedgerStore {
    private val driver = createDriver(context.applicationContext)
    private val delegate = SqlDelightLedgerStore(driver)

    private fun createDriver(context: Context): AndroidSqliteDriver {
        validateExistingDatabase(context)
        return AndroidSqliteDriver(CofrinhoDatabase.Schema, context, "cofrinho.sqlite")
    }

    private fun validateExistingDatabase(context: Context) {
        val file = context.getDatabasePath("cofrinho.sqlite")
        if (!file.isFile || file.length() == 0L) return
        val database = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (error: Exception) {
            throw incompatible(file.path, error.message ?: "cannot open database read-only")
        }
        try {
            try {
                val cursor = database.rawQuery("SELECT generation FROM schema_marker WHERE marker_id = 1", null)
                val generation = try {
                    if (!cursor.moveToFirst()) null else cursor.getInt(0)
                } finally {
                    cursor.close()
                }
                if (generation != 3) throw incompatible(file.path, "schema generation '$generation'")
            } catch (error: IllegalStateException) {
                throw error
            } catch (error: Exception) {
                throw incompatible(file.path, error.message ?: "missing schema marker")
            }
        } finally {
            database.close()
        }
    }

    private fun incompatible(path: String, detail: String): IllegalStateException =
        IllegalStateException("Incompatible ledger database '$path'; $detail; expected schema generation 3")

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
}
