package caixa.transfer

import caixa.finance.CalendarRules
import caixa.finance.Expense
import caixa.finance.ExpenseStatus
import caixa.finance.FinanceException
import caixa.finance.IncomeOverride
import caixa.finance.IncomeSchedule
import caixa.finance.IncomeSource
import caixa.finance.Ledger
import caixa.finance.PaymentMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PortableSource(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("stopped_from_month")
    val stoppedFromMonth: String? = null,
)

@Serializable
data class PortableSchedule(
    val id: String,
    @SerialName("source_id")
    val sourceId: String,
    @SerialName("starts_month")
    val startsMonth: String,
    @SerialName("amount_minor")
    val amountMinor: String,
)

@Serializable
data class PortableOverride(
    val id: String,
    @SerialName("source_id")
    val sourceId: String,
    @SerialName("budget_month")
    val budgetMonth: String,
    @SerialName("amount_minor")
    val amountMinor: String,
)

@Serializable
data class PortableExpense(
    val id: String,
    @SerialName("amount_minor")
    val amountMinor: String,
    val status: String,
    @SerialName("expected_on")
    val expectedOn: String? = null,
    @SerialName("occurred_on")
    val occurredOn: String? = null,
    @SerialName("payment_method")
    val paymentMethod: String,
    val description: String? = null,
)

@Serializable
data class PortableLedger(
    @SerialName("format_version")
    val formatVersion: Int,
    @SerialName("ledger_id")
    val ledgerId: String,
    @SerialName("checkpoint_id")
    val checkpointId: String,
    @SerialName("parent_checkpoint_id")
    val parentCheckpointId: String? = null,
    @SerialName("exported_at")
    val exportedAt: String,
    @SerialName("currency_code")
    val currencyCode: String,
    @SerialName("currency_scale")
    val currencyScale: Int,
    @SerialName("income_sources")
    val sources: List<PortableSource>,
    @SerialName("income_schedules")
    val schedules: List<PortableSchedule>,
    @SerialName("income_overrides")
    val overrides: List<PortableOverride>,
    val expenses: List<PortableExpense>,
)

object PortableLedgerCodec {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(snapshot: PortableLedger): String {
        validate(snapshot)
        return json.encodeToString(snapshot)
    }

    fun decode(contents: String): PortableLedger = try {
        if (contents.length > MAX_DOCUMENT_CHARS) throw FinanceException("Import is too large '${contents.length}'; expected at most $MAX_DOCUMENT_CHARS characters")
        json.decodeFromString<PortableLedger>(contents).also(::validate)
    } catch (error: Exception) {
        if (error is FinanceException) throw error
        throw FinanceException("Invalid .caixa file '${error.message}'; expected a valid versioned ledger document")
    }

    fun snapshot(ledger: Ledger, checkpointId: String, parentCheckpointId: String?, exportedAt: String): PortableLedger {
        return PortableLedger(1, ledger.id, checkpointId, parentCheckpointId, exportedAt, ledger.currencyCode, ledger.currencyScale, ledger.sources.map(::source), ledger.schedules.map(::schedule), ledger.overrides.map(::override), ledger.expenses.map(::expense))
    }

    fun ledger(snapshot: PortableLedger): Ledger {
        validate(snapshot)
        val sources = snapshot.sources.map { IncomeSource(it.id, it.name, it.stoppedFromMonth) }
        val schedules = snapshot.schedules.map { IncomeSchedule(it.id, it.sourceId, it.startsMonth, it.amountMinor.toLongExact("income schedule ${it.id}")) }
        val overrides = snapshot.overrides.map { IncomeOverride(it.id, it.sourceId, it.budgetMonth, it.amountMinor.toLongExact("income override ${it.id}")) }
        val expenses = snapshot.expenses.map { parseExpense(it) }
        return Ledger(snapshot.ledgerId, snapshot.currencyCode, snapshot.currencyScale, sources, schedules, overrides, expenses)
    }

    private fun validate(snapshot: PortableLedger) {
        if (snapshot.formatVersion != 1) throw FinanceException("Unsupported format version '${snapshot.formatVersion}'; expected 1")
        if (snapshot.ledgerId.isBlank()) throw FinanceException("Invalid ledger ID '${snapshot.ledgerId}'; expected a nonempty ID")
        if (snapshot.checkpointId.isBlank()) throw FinanceException("Invalid checkpoint ID '${snapshot.checkpointId}'; expected a nonempty ID")
        if (!snapshot.currencyCode.matches(Regex("[A-Z]{3}"))) throw FinanceException("Invalid currency '${snapshot.currencyCode}'; expected an uppercase three-letter code")
        if (snapshot.currencyScale !in 0..6) throw FinanceException("Invalid currency scale '${snapshot.currencyScale}'; expected 0-6")
        validateCounts(snapshot)
        validateSources(snapshot.sources)
        validateSchedules(snapshot)
        validateOverrides(snapshot)
        snapshot.expenses.forEach(::validateExpense)
        validateReferences(snapshot)
    }

    private fun validateCounts(snapshot: PortableLedger) {
        requireCount(snapshot.sources.size, "income sources")
        requireCount(snapshot.schedules.size, "income schedules")
        requireCount(snapshot.overrides.size, "income overrides")
        requireCount(snapshot.expenses.size, "expenses")
    }

    private fun validateSources(sources: List<PortableSource>) {
        sources.forEach { source -> requireIdentifier(source.id, "income source"); if (source.name.isBlank()) throw FinanceException("Invalid income source '${source.id}'; expected a nonempty name"); source.stoppedFromMonth?.let { validateMonth(it, "source ${source.id} stop") } }
        requireUnique(sources.map { it.id }, "source IDs")
    }

    private fun validateSchedules(snapshot: PortableLedger) {
        snapshot.schedules.forEach { schedule ->
            requireIdentifier(schedule.id, "income schedule")
            validateMonth(schedule.startsMonth, "schedule ${schedule.id}")
            schedule.amountMinor.toLongExact("schedule ${schedule.id}").requirePositive("schedule ${schedule.id}")
        }
        requireUnique(snapshot.schedules.map { it.id }, "schedule IDs")
        requireUnique(snapshot.schedules.map { "${it.sourceId}:${it.startsMonth}" }, "income schedule source/month")
    }

    private fun validateOverrides(snapshot: PortableLedger) {
        snapshot.overrides.forEach { override -> requireIdentifier(override.id, "income override"); validateMonth(override.budgetMonth, "override ${override.id}"); override.amountMinor.toLongExact("income override ${override.id}").requireNonNegative("income override ${override.id}") }
        requireUnique(snapshot.overrides.map { it.id }, "override IDs")
        requireUnique(snapshot.overrides.map { "${it.sourceId}:${it.budgetMonth}" }, "income override source/month")
    }

    private fun validateReferences(snapshot: PortableLedger) {
        requireUnique(snapshot.expenses.map { it.id }, "expense IDs")
        snapshot.schedules.forEach { requireSource(snapshot.sources, it.sourceId, "schedule ${it.id}") }
        snapshot.overrides.forEach { requireSource(snapshot.sources, it.sourceId, "override ${it.id}") }
    }

    private fun validateExpense(expense: PortableExpense) {
        requireIdentifier(expense.id, "expense")
        expense.amountMinor.toLongExact("expense ${expense.id}").requirePositive("expense ${expense.id}")
        val status = runCatching { ExpenseStatus.valueOf(expense.status) }.getOrElse { throw FinanceException("Invalid expense status '${expense.status}'; expected PLANNED, RECORDED, or CANCELLED") }
        runCatching { PaymentMethod.valueOf(expense.paymentMethod) }.getOrElse { throw FinanceException("Invalid payment method '${expense.paymentMethod}'; expected CREDIT or DEBIT") }
        expense.expectedOn?.let { CalendarRules.requireDate(it) }
        expense.occurredOn?.let { CalendarRules.requireDate(it) }
        if (expense.description != null && expense.description.length > MAX_DESCRIPTION_CHARS) throw FinanceException("Invalid expense description for '${expense.id}'; expected at most $MAX_DESCRIPTION_CHARS characters")
        val valid = when (status) {
            ExpenseStatus.PLANNED, ExpenseStatus.CANCELLED -> expense.expectedOn != null && expense.occurredOn == null
            ExpenseStatus.RECORDED -> expense.occurredOn != null
        }
        if (!valid) throw FinanceException("Invalid expense '${expense.id}'; expected dates matching status $status")
    }

    private fun parseExpense(expense: PortableExpense): Expense = Expense(expense.id, expense.amountMinor.toLongExact("expense ${expense.id}"), ExpenseStatus.valueOf(expense.status), expense.expectedOn, expense.occurredOn, PaymentMethod.valueOf(expense.paymentMethod), expense.description)

    private fun source(source: IncomeSource) = PortableSource(source.id, source.name, source.stoppedFromMonth)
    private fun schedule(schedule: IncomeSchedule) = PortableSchedule(schedule.id, schedule.sourceId, schedule.startsMonth, schedule.amountMinor.toString())
    private fun override(override: IncomeOverride) = PortableOverride(override.id, override.sourceId, override.budgetMonth, override.amountMinor.toString())
    private fun expense(expense: Expense) = PortableExpense(expense.id, expense.amountMinor.toString(), expense.status.name, expense.expectedOn, expense.occurredOn, expense.paymentMethod.name, expense.description)

    private fun validateMonth(value: String, label: String) { try { CalendarRules.requireMonth(value) } catch (error: FinanceException) { throw FinanceException("Invalid $label '$value'; expected YYYY-MM") } }

    private fun requireSource(sources: List<PortableSource>, id: String, label: String) { if (sources.none { it.id == id }) throw FinanceException("Invalid $label source '$id'; expected a source in the same ledger") }

    private fun requireUnique(values: List<String>, label: String) { if (values.size != values.toSet().size) throw FinanceException("Duplicate $label; expected unique identities") }

    private fun requireIdentifier(value: String, label: String) { if (value.isBlank() || value.length > MAX_IDENTIFIER_CHARS) throw FinanceException("Invalid $label ID '$value'; expected a nonempty ID of at most $MAX_IDENTIFIER_CHARS characters") }

    private fun requireCount(value: Int, label: String) { if (value > MAX_RECORDS) throw FinanceException("Too many $label '$value'; expected at most $MAX_RECORDS records") }

    private fun String.toLongExact(label: String): Long = toLongOrNull() ?: throw FinanceException("Invalid amount '$this' for $label; expected signed 64-bit minor units")

    private fun Long.requirePositive(label: String) { if (this <= 0) throw FinanceException("Invalid amount '$this' for $label; expected positive minor units") }
    private fun Long.requireNonNegative(label: String) { if (this < 0) throw FinanceException("Invalid amount '$this' for $label; expected zero or positive minor units") }

    private const val MAX_DOCUMENT_CHARS = 4_000_000
    private const val MAX_RECORDS = 10_000
    private const val MAX_IDENTIFIER_CHARS = 128
    private const val MAX_DESCRIPTION_CHARS = 1_000
}
