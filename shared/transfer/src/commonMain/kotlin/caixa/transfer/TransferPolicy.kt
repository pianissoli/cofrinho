package caixa.transfer

import caixa.finance.FinanceException
import caixa.finance.Ledger

enum class ImportDecision { ADOPT, NO_OP, REPLACE, CONFLICT }

data class Checkpoint(
    val checkpointId: String,
    val parentCheckpointId: String?,
    val payloadDigest: String,
    val revision: Long,
)

data class ImportInspection(
    val decision: ImportDecision,
    val reason: String,
    val incoming: Ledger,
)

class TransferPolicy {
    fun inspect(current: Ledger?, checkpoint: Checkpoint?, incoming: PortableLedger, incomingDigest: String, seenDigests: Map<String, String> = emptyMap()): ImportInspection {
        val ledger = PortableLedgerCodec.ledger(incoming)
        val seenDigest = seenDigests[incoming.checkpointId]
        if (seenDigest != null) {
            if (seenDigest != incomingDigest) throw FinanceException("Checkpoint '${incoming.checkpointId}' was reused with different bytes; expected the original payload")
            return ImportInspection(ImportDecision.NO_OP, "Checkpoint already imported", ledger)
        }
        if (current == null) return ImportInspection(ImportDecision.ADOPT, "Empty installation", ledger)
        if (current.sources.isEmpty() && current.expenses.isEmpty() && current.revision == 0L) return ImportInspection(ImportDecision.ADOPT, "Empty ledger", ledger)
        if (current.id != incoming.ledgerId) throw FinanceException("Ledger '${incoming.ledgerId}' does not match current ledger '${current.id}'; expected the same ledger ID")
        if (current.currencyCode != incoming.currencyCode || current.currencyScale != incoming.currencyScale) throw FinanceException("Currency '${incoming.currencyCode}/${incoming.currencyScale}' does not match current ledger; expected ${current.currencyCode}/${current.currencyScale}")
        if (checkpoint?.checkpointId == incoming.checkpointId) {
            if (checkpoint.payloadDigest != incomingDigest) throw FinanceException("Checkpoint '${incoming.checkpointId}' was reused with different bytes; expected the original payload")
            return ImportInspection(ImportDecision.NO_OP, "Checkpoint already imported", ledger)
        }
        if (checkpoint == null) return ImportInspection(ImportDecision.CONFLICT, "No accepted checkpoint matches this ledger", ledger)
        if (current.revision != checkpoint.revision || incoming.parentCheckpointId != checkpoint.checkpointId) return ImportInspection(ImportDecision.CONFLICT, "Local or incoming history diverged", ledger)
        return ImportInspection(ImportDecision.REPLACE, "Incoming checkpoint continues the accepted history", ledger)
    }
}
