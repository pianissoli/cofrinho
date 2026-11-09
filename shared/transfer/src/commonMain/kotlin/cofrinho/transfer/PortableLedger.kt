package cofrinho.transfer

import cofrinho.finance.CalendarRules
import cofrinho.finance.Expense
import cofrinho.finance.FinanceException
import cofrinho.finance.IncomeOverride
import cofrinho.finance.IncomeSchedule
import cofrinho.finance.IncomeSource
import cofrinho.finance.Ledger
import cofrinho.finance.PaymentMethod
import cofrinho.finance.RecurringExpense
import cofrinho.finance.RecurringExpenseOverride
import cofrinho.finance.RecurringExpenseSchedule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PortableSource(
    val id: String,
    val name: String,
    @SerialName("stopped_from_month") val stoppedFromMonth: String? = null,
)

@Serializable
data class PortableSchedule(
    val id: String,
    @SerialName("source_id") val sourceId: String,
    @SerialName("starts_month") val startsMonth: String,
    @SerialName("amount_minor") val amountMinor: String,
)

@Serializable
data class PortableOverride(
    val id: String,
    @SerialName("source_id") val sourceId: String,
    @SerialName("budget_month") val budgetMonth: String,
    @SerialName("amount_minor") val amountMinor: String,
)

@Serializable
data class PortableRecurringExpense(
    val id: String,
    val name: String,
    @SerialName("stopped_from_month") val stoppedFromMonth: String? = null,
)

@Serializable
data class PortableRecurringExpenseSchedule(
    val id: String,
    @SerialName("recurring_expense_id") val recurringExpenseId: String,
    @SerialName("starts_month") val startsMonth: String,
    @SerialName("amount_minor") val amountMinor: String,
    @SerialName("payment_method") val paymentMethod: String,
)

@Serializable
data class PortableRecurringExpenseOverride(
    val id: String,
    @SerialName("recurring_expense_id") val recurringExpenseId: String,
    @SerialName("budget_month") val budgetMonth: String,
    @SerialName("amount_minor") val amountMinor: String,
)

@Serializable
data class PortableExpense(
    val id: String,
    @SerialName("amount_minor") val amountMinor: String,
    @SerialName("occurred_on") val occurredOn: String,
    @SerialName("payment_method") val paymentMethod: String,
    val description: String? = null,
)

@Serializable
data class PortableLedger(
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("ledger_id") val ledgerId: String,
    @SerialName("checkpoint_id") val checkpointId: String,
    @SerialName("parent_checkpoint_id") val parentCheckpointId: String? = null,
    @SerialName("exported_at") val exportedAt: String,
    @SerialName("currency_code") val currencyCode: String,
    @SerialName("currency_scale") val currencyScale: Int,
    @SerialName("income_sources") val sources: List<PortableSource>,
    @SerialName("income_schedules") val schedules: List<PortableSchedule>,
    @SerialName("income_overrides") val overrides: List<PortableOverride>,
    @SerialName("recurring_expenses") val recurringExpenses: List<PortableRecurringExpense>,
    @SerialName("recurring_expense_schedules") val recurringExpenseSchedules: List<PortableRecurringExpenseSchedule>,
    @SerialName("recurring_expense_overrides") val recurringExpenseOverrides: List<PortableRecurringExpenseOverride>,
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
        throw FinanceException("Invalid .cofrinho file '${error.message}'; expected format version 2")
    }

    fun snapshot(ledger: Ledger, checkpointId: String, parentCheckpointId: String?, exportedAt: String): PortableLedger = PortableLedger(
        formatVersion = 2,
        ledgerId = ledger.id,
        checkpointId = checkpointId,
        parentCheckpointId = parentCheckpointId,
        exportedAt = exportedAt,
        currencyCode = ledger.currencyCode,
        currencyScale = ledger.currencyScale,
        sources = ledger.sources.map(::source),
        schedules = ledger.schedules.map(::schedule),
        overrides = ledger.overrides.map(::override),
        recurringExpenses = ledger.recurringExpenses.map(::recurringExpense),
        recurringExpenseSchedules = ledger.recurringExpenseSchedules.map(::recurringSchedule),
        recurringExpenseOverrides = ledger.recurringExpenseOverrides.map(::recurringOverride),
        expenses = ledger.expenses.map(::expense),
    )

    fun ledger(snapshot: PortableLedger): Ledger {
        validate(snapshot)
        return Ledger(
            id = snapshot.ledgerId,
            currencyCode = snapshot.currencyCode,
            currencyScale = snapshot.currencyScale,
            sources = snapshot.sources.map { IncomeSource(it.id, it.name, it.stoppedFromMonth) },
            schedules = snapshot.schedules.map { IncomeSchedule(it.id, it.sourceId, it.startsMonth, it.amountMinor.toLongExact("income schedule ${it.id}")) },
            overrides = snapshot.overrides.map { IncomeOverride(it.id, it.sourceId, it.budgetMonth, it.amountMinor.toLongExact("income override ${it.id}")) },
            recurringExpenses = snapshot.recurringExpenses.map { RecurringExpense(it.id, it.name, it.stoppedFromMonth) },
            recurringExpenseSchedules = snapshot.recurringExpenseSchedules.map { RecurringExpenseSchedule(it.id, it.recurringExpenseId, it.startsMonth, it.amountMinor.toLongExact("recurring expense schedule ${it.id}"), payment(it.paymentMethod)) },
            recurringExpenseOverrides = snapshot.recurringExpenseOverrides.map { RecurringExpenseOverride(it.id, it.recurringExpenseId, it.budgetMonth, it.amountMinor.toLongExact("recurring expense override ${it.id}")) },
            expenses = snapshot.expenses.map { Expense(it.id, it.amountMinor.toLongExact("expense ${it.id}"), it.occurredOn, payment(it.paymentMethod), it.description) },
        )
    }

    private fun validate(snapshot: PortableLedger) {
        if (snapshot.formatVersion != 2) throw FinanceException("Unsupported format version '${snapshot.formatVersion}'; expected 2")
        if (snapshot.ledgerId.isBlank()) throw FinanceException("Invalid ledger ID '${snapshot.ledgerId}'; expected a nonempty ID")
        if (snapshot.checkpointId.isBlank()) throw FinanceException("Invalid checkpoint ID '${snapshot.checkpointId}'; expected a nonempty ID")
        if (!snapshot.currencyCode.matches(Regex("[A-Z]{3}"))) throw FinanceException("Invalid currency '${snapshot.currencyCode}'; expected an uppercase three-letter code")
        if (snapshot.currencyScale !in 0..6) throw FinanceException("Invalid currency scale '${snapshot.currencyScale}'; expected 0-6")
        validateSources(snapshot.sources)
        validateIncomeSchedules(snapshot)
        validateIncomeOverrides(snapshot)
        validateRecurringExpenses(snapshot)
        validateRecurringSchedules(snapshot)
        validateRecurringOverrides(snapshot)
        snapshot.expenses.forEach(::validateExpense)
        validateReferences(snapshot)
        validateCounts(snapshot)
    }

    private fun validateCounts(snapshot: PortableLedger) {
        requireCount(snapshot.sources.size, "income sources")
        requireCount(snapshot.schedules.size, "income schedules")
        requireCount(snapshot.overrides.size, "income overrides")
        requireCount(snapshot.recurringExpenses.size, "recurring expenses")
        requireCount(snapshot.recurringExpenseSchedules.size, "recurring expense schedules")
        requireCount(snapshot.recurringExpenseOverrides.size, "recurring expense overrides")
        requireCount(snapshot.expenses.size, "expenses")
    }

    private fun validateSources(sources: List<PortableSource>) {
        sources.forEach { source -> requireIdentifier(source.id, "income source"); if (source.name.isBlank()) throw FinanceException("Invalid income source '${source.id}'; expected a nonempty name"); source.stoppedFromMonth?.let { validateMonth(it, "source ${source.id} stop") } }
        requireUnique(sources.map { it.id }, "source IDs")
    }

    private fun validateIncomeSchedules(snapshot: PortableLedger) {
        snapshot.schedules.forEach { schedule -> requireIdentifier(schedule.id, "income schedule"); validateMonth(schedule.startsMonth, "schedule ${schedule.id}"); schedule.amountMinor.toLongExact("schedule ${schedule.id}").requirePositive("schedule ${schedule.id}") }
        requireUnique(snapshot.schedules.map { it.id }, "income schedule IDs")
        requireUnique(snapshot.schedules.map { "${it.sourceId}:${it.startsMonth}" }, "income schedule source/month")
    }

    private fun validateIncomeOverrides(snapshot: PortableLedger) {
        snapshot.overrides.forEach { override ->
            requireIdentifier(override.id, "income override")
            validateMonth(override.budgetMonth, "override ${override.id}")
            override.amountMinor.toLongExact("income override ${override.id}").requireNonNegative("income override ${override.id}")
            if (snapshot.schedules.none { it.sourceId == override.sourceId && CalendarRules.compareMonths(it.startsMonth, override.budgetMonth) <= 0 }) {
                throw FinanceException("Invalid income override month '${override.budgetMonth}'; expected a month on or after the first schedule")
            }
        }
        requireUnique(snapshot.overrides.map { it.id }, "income override IDs")
        requireUnique(snapshot.overrides.map { "${it.sourceId}:${it.budgetMonth}" }, "income override source/month")
    }

    private fun validateRecurringExpenses(snapshot: PortableLedger) {
        snapshot.recurringExpenses.forEach { expense -> requireIdentifier(expense.id, "recurring expense"); if (expense.name.isBlank()) throw FinanceException("Invalid recurring expense '${expense.id}'; expected a nonempty name"); expense.stoppedFromMonth?.let { validateMonth(it, "recurring expense ${expense.id} stop") } }
        requireUnique(snapshot.recurringExpenses.map { it.id }, "recurring expense IDs")
    }

    private fun validateRecurringSchedules(snapshot: PortableLedger) {
        snapshot.recurringExpenseSchedules.forEach { schedule -> requireIdentifier(schedule.id, "recurring expense schedule"); validateMonth(schedule.startsMonth, "schedule ${schedule.id}"); schedule.amountMinor.toLongExact("schedule ${schedule.id}").requirePositive("schedule ${schedule.id}"); payment(schedule.paymentMethod) }
        requireUnique(snapshot.recurringExpenseSchedules.map { it.id }, "recurring expense schedule IDs")
        requireUnique(snapshot.recurringExpenseSchedules.map { "${it.recurringExpenseId}:${it.startsMonth}" }, "recurring expense schedule/month")
    }

    private fun validateRecurringOverrides(snapshot: PortableLedger) {
        snapshot.recurringExpenseOverrides.forEach { override ->
            requireIdentifier(override.id, "recurring expense override")
            validateMonth(override.budgetMonth, "override ${override.id}")
            override.amountMinor.toLongExact("recurring expense override ${override.id}").requireNonNegative("recurring expense override ${override.id}")
            if (snapshot.recurringExpenseSchedules.none { it.recurringExpenseId == override.recurringExpenseId && CalendarRules.compareMonths(it.startsMonth, override.budgetMonth) <= 0 }) {
                throw FinanceException("Invalid recurring expense override month '${override.budgetMonth}'; expected a month on or after the first schedule")
            }
        }
        requireUnique(snapshot.recurringExpenseOverrides.map { it.id }, "recurring expense override IDs")
        requireUnique(snapshot.recurringExpenseOverrides.map { "${it.recurringExpenseId}:${it.budgetMonth}" }, "recurring expense override/month")
    }

    private fun validateReferences(snapshot: PortableLedger) {
        snapshot.schedules.forEach { requireSource(snapshot.sources, it.sourceId, "schedule ${it.id}") }
        snapshot.overrides.forEach { requireSource(snapshot.sources, it.sourceId, "override ${it.id}") }
        snapshot.recurringExpenseSchedules.forEach { requireRecurringExpense(snapshot.recurringExpenses, it.recurringExpenseId, "schedule ${it.id}") }
        snapshot.recurringExpenseOverrides.forEach { requireRecurringExpense(snapshot.recurringExpenses, it.recurringExpenseId, "override ${it.id}") }
        requireUnique(snapshot.expenses.map { it.id }, "expense IDs")
    }

    private fun validateExpense(expense: PortableExpense) {
        requireIdentifier(expense.id, "expense")
        expense.amountMinor.toLongExact("expense ${expense.id}").requirePositive("expense ${expense.id}")
        CalendarRules.requireDate(expense.occurredOn)
        payment(expense.paymentMethod)
        if (expense.description != null && expense.description.length > MAX_DESCRIPTION_CHARS) throw FinanceException("Invalid expense description for '${expense.id}'; expected at most $MAX_DESCRIPTION_CHARS characters")
    }

    private fun source(source: IncomeSource) = PortableSource(source.id, source.name, source.stoppedFromMonth)
    private fun schedule(schedule: IncomeSchedule) = PortableSchedule(schedule.id, schedule.sourceId, schedule.startsMonth, schedule.amountMinor.toString())
    private fun override(override: IncomeOverride) = PortableOverride(override.id, override.sourceId, override.budgetMonth, override.amountMinor.toString())
    private fun recurringExpense(expense: RecurringExpense) = PortableRecurringExpense(expense.id, expense.name, expense.stoppedFromMonth)
    private fun recurringSchedule(schedule: RecurringExpenseSchedule) = PortableRecurringExpenseSchedule(schedule.id, schedule.recurringExpenseId, schedule.startsMonth, schedule.amountMinor.toString(), schedule.paymentMethod.name)
    private fun recurringOverride(override: RecurringExpenseOverride) = PortableRecurringExpenseOverride(override.id, override.recurringExpenseId, override.budgetMonth, override.amountMinor.toString())
    private fun expense(expense: Expense) = PortableExpense(expense.id, expense.amountMinor.toString(), expense.occurredOn, expense.paymentMethod.name, expense.description)

    private fun payment(value: String): PaymentMethod = runCatching { PaymentMethod.valueOf(value) }.getOrElse { throw FinanceException("Invalid payment method '$value'; expected CREDIT or DEBIT") }
    private fun validateMonth(value: String, label: String) { try { CalendarRules.requireMonth(value) } catch (error: FinanceException) { throw FinanceException("Invalid $label '$value'; expected YYYY-MM") } }
    private fun requireSource(sources: List<PortableSource>, id: String, label: String) { if (sources.none { it.id == id }) throw FinanceException("Invalid $label source '$id'; expected a source in the same ledger") }
    private fun requireRecurringExpense(expenses: List<PortableRecurringExpense>, id: String, label: String) { if (expenses.none { it.id == id }) throw FinanceException("Invalid $label recurring expense '$id'; expected a recurring expense in the same ledger") }
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
