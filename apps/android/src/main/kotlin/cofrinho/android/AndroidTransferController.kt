package cofrinho.android

import android.content.Context
import android.net.Uri
import cofrinho.finance.TransferState
import cofrinho.transfer.Checkpoint
import cofrinho.transfer.ImportDecision
import cofrinho.transfer.ImportInspection
import cofrinho.transfer.PortableLedger
import cofrinho.transfer.PortableLedgerCodec
import cofrinho.transfer.TransferPolicy
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class PendingAndroidImport(
    val inspection: ImportInspection,
    val snapshot: PortableLedger,
    val digest: String,
)

class AndroidTransferController(private val context: Context, private val store: AndroidLedgerStore, private val backups: AndroidBackupManager) {
    fun export(uri: Uri) {
        val state = store.readTransferState()
        val stagedPayload: String? = state.stagedPayload
        val contents: String = if (state.handedOff && stagedPayload != null) {
            stagedPayload
        } else {
            if (state.handedOff) error("Ledger is already handed off; import the returned file or resume editing first")
            val ledger = store.read()
            val snapshot = PortableLedgerCodec.snapshot(ledger, UUID.randomUUID().toString(), state.checkpointId, Instant.now().toString())
            PortableLedgerCodec.encode(snapshot).also { payload ->
                val payloadDigest = digest(payload)
                store.writeTransferState(TransferState(snapshot.checkpointId, snapshot.parentCheckpointId, payloadDigest, ledger.revision, true, payload))
                store.writeTransferReceipt(snapshot.checkpointId, payloadDigest)
            }
        }
        context.contentResolver.openOutputStream(uri)?.use { it.write(contents.toByteArray()) } ?: error("Cannot write export '$uri'; expected a writable document URI")
    }

    fun preview(uri: Uri): PendingAndroidImport {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream -> stream.readNBytes(MAX_IMPORT_BYTES + 1) } ?: error("Cannot read import '$uri'; expected a readable document URI")
        if (bytes.size > MAX_IMPORT_BYTES) error("Import file '$uri' is too large; expected at most $MAX_IMPORT_BYTES bytes")
        val contents = bytes.decodeToString()
        val snapshot = PortableLedgerCodec.decode(contents)
        val state = store.readTransferState()
        val checkpoint = state.checkpointId?.let { Checkpoint(it, state.parentCheckpointId, state.payloadDigest.orEmpty(), state.revision) }
        val payloadDigest = digest(contents)
        val inspection = TransferPolicy().inspect(store.read(), checkpoint, snapshot, payloadDigest, store.readTransferReceipts())
        return PendingAndroidImport(inspection, snapshot, payloadDigest)
    }

    fun apply(pending: PendingAndroidImport, useIncoming: Boolean) {
        if (!useIncoming && pending.inspection.decision == ImportDecision.CONFLICT) return
        if (pending.inspection.decision == ImportDecision.NO_OP) return
        if (pending.inspection.decision == ImportDecision.CONFLICT) backups.preserveLocalConflict()
        backups.beforeOperation("import")
        val current = store.read()
        if (current.revision == Long.MAX_VALUE) error("Revision '${current.revision}' cannot advance; expected a lower signed 64-bit revision")
        val imported = pending.inspection.incoming.copy(revision = current.revision + 1L)
        store.replaceWithTransferStateAndReceipt(imported, TransferState(pending.snapshot.checkpointId, pending.snapshot.parentCheckpointId, pending.digest, imported.revision, false, null), pending.snapshot.checkpointId, pending.digest)
    }

    /** Preserves both versions for a later conflict decision. Example: controller.preserveConflict(pending). */
    fun preserveConflict(pending: PendingAndroidImport) {
        backups.preserveLocalConflict()
        backups.preserveConflict(pending.snapshot, pending.digest)
    }

    fun resumeEditing() {
        val state = store.readTransferState()
        if (state.handedOff) store.writeTransferState(state.copy(handedOff = false, stagedPayload = null))
    }

    private fun digest(contents: String): String = MessageDigest.getInstance("SHA-256").digest(contents.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object { const val MAX_IMPORT_BYTES = 8_000_000 }
}
