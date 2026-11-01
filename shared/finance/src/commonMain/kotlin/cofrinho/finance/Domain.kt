package cofrinho.finance

enum class PaymentMethod { CREDIT, DEBIT }

enum class ExpenseStatus { PLANNED, RECORDED, CANCELLED }

data class IncomeSource(
    val id: String,
    val name: String,
    val stoppedFromMonth: String? = null,
)

data class IncomeSchedule(
    val id: String,
    val sourceId: String,
    val startsMonth: String,
    val amountMinor: Long,
)

data class IncomeOverride(
    val id: String,
    val sourceId: String,
    val budgetMonth: String,
    val amountMinor: Long,
)

data class Expense(
    val id: String,
    val amountMinor: Long,
    val status: ExpenseStatus,
    val expectedOn: String?,
    val occurredOn: String?,
    val paymentMethod: PaymentMethod,
    val description: String?,
)

data class Ledger(
    val id: String,
    val currencyCode: String,
    val currencyScale: Int,
    val sources: List<IncomeSource> = emptyList(),
    val schedules: List<IncomeSchedule> = emptyList(),
    val overrides: List<IncomeOverride> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val revision: Long = 0,
)

data class BudgetSummary(
    val month: String,
    val budgetMinor: Long,
    val spentMinor: Long,
    val reservedMinor: Long,
    val dailySpending: List<DailySpending> = emptyList(),
    val creditSpentMinor: Long = 0,
    val debitSpentMinor: Long = 0,
) {
    val remainingMinor: Long get() = checkedSubtract(budgetMinor, spentMinor)
    val availableMinor: Long get() = checkedSubtract(remainingMinor, reservedMinor)

    private fun checkedSubtract(left: Long, right: Long): Long {
        if (right > 0 && left < Long.MIN_VALUE + right) throw FinanceException("Amount underflow '$left - $right'; expected signed 64-bit minor units")
        return left - right
    }
}

data class DailySpending(
    val date: String,
    val amountMinor: Long,
)

data class TransferState(
    val checkpointId: String? = null,
    val parentCheckpointId: String? = null,
    val payloadDigest: String? = null,
    val revision: Long = 0,
    val handedOff: Boolean = false,
    val stagedPayload: String? = null,
)

interface LedgerStore {
    fun read(): Ledger
    fun replace(ledger: Ledger)
    fun readTransferState(): TransferState = TransferState()
    fun writeTransferState(state: TransferState) = Unit
    fun readTransferReceipts(): Map<String, String> = emptyMap()
    fun writeTransferReceipt(checkpointId: String, payloadDigest: String) = Unit
    /** Replaces financial rows and transfer metadata as one storage operation. Example: store.replaceWithTransferState(imported, state). */
    fun replaceWithTransferState(ledger: Ledger, state: TransferState) {
        replace(ledger)
        writeTransferState(state)
    }
    fun replaceWithTransferStateAndReceipt(ledger: Ledger, state: TransferState, checkpointId: String, payloadDigest: String) {
        replaceWithTransferState(ledger, state)
        writeTransferReceipt(checkpointId, payloadDigest)
    }
}

interface LedgerClock {
    fun today(): String
    fun nowUtc(): String
}

interface IdentifierSource {
    fun next(): String
}

class FinanceException(message: String) : IllegalArgumentException(message)
