package caixa.transfer

import caixa.finance.Ledger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransferPolicyTest {
    @Test
    fun matchingCheckpointIsIdempotent() {
        val ledger = Ledger("ledger", "BRL", 2, revision = 1)
        val snapshot = PortableLedgerCodec.snapshot(ledger, "checkpoint", null, "2026-10-30T12:00:00Z")
        val digest = "digest"
        val inspection = TransferPolicy().inspect(ledger, Checkpoint("checkpoint", null, digest, 0), snapshot, digest)
        assertEquals(ImportDecision.NO_OP, inspection.decision)
    }

    @Test
    fun previouslySeenCheckpointIsIdempotent() {
        val current = Ledger("ledger", "BRL", 2, revision = 1)
        val snapshot = PortableLedgerCodec.snapshot(current, "old", null, "2026-10-30T12:00:00Z")
        val inspection = TransferPolicy().inspect(current, null, snapshot, "digest", mapOf("old" to "digest"))
        assertEquals(ImportDecision.NO_OP, inspection.decision)
    }

    @Test
    fun previouslySeenCheckpointWithChangedBytesFails() {
        val current = Ledger("ledger", "BRL", 2, revision = 1)
        val snapshot = PortableLedgerCodec.snapshot(current, "old", null, "2026-10-30T12:00:00Z")
        assertFailsWith<Exception> { TransferPolicy().inspect(current, null, snapshot, "changed", mapOf("old" to "digest")) }
    }

    @Test
    fun branchedCheckpointRequiresChoice() {
        val ledger = Ledger("ledger", "BRL", 2, revision = 1)
        val snapshot = PortableLedgerCodec.snapshot(ledger, "incoming", "other", "2026-10-30T12:00:00Z")
        val inspection = TransferPolicy().inspect(ledger, Checkpoint("local", null, "digest", 0), snapshot, "incoming-digest")
        assertEquals(ImportDecision.CONFLICT, inspection.decision)
    }

    @Test
    fun differentLedgerIsRejected() {
        val current = Ledger("local", "BRL", 2, revision = 1)
        val incoming = PortableLedgerCodec.snapshot(Ledger("other", "BRL", 2), "checkpoint", null, "2026-10-30T12:00:00Z")
        assertFailsWith<Exception> { TransferPolicy().inspect(current, null, incoming, "digest") }
    }
}
