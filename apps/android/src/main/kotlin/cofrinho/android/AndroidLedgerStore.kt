package cofrinho.android

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import cofrinho.finance.Ledger
import cofrinho.finance.LedgerStore
import cofrinho.finance.TransferState
import cofrinho.storage.SqlDelightLedgerStore
import cofrinho.storage.db.CofrinhoDatabase

class AndroidLedgerStore(context: Context) : LedgerStore {
    private val driver = AndroidSqliteDriver(CofrinhoDatabase.Schema, context.applicationContext, "cofrinho.sqlite")
    private val delegate = SqlDelightLedgerStore(driver)

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
