package cofrinho.finance

class LedgerService(
    private val store: LedgerStore,
    private val clock: LedgerClock,
    private val ids: IdentifierSource,
) {
    fun summary(month: String): BudgetSummary {
        CalendarRules.requireMonth(month)
        val ledger = store.read()
        val recorded = expensesForMonth(ledger, month)
        val spent = sumAmounts(recorded.map { it.amountMinor })
        val recurring = sumAmounts(resolvedRecurringExpenses(ledger, month).map { it.amountMinor })
        val daily = dailySpending(recorded)
        val credit = sumAmounts(recorded.filter { it.paymentMethod == PaymentMethod.CREDIT }.map { it.amountMinor })
        val debit = sumAmounts(recorded.filter { it.paymentMethod == PaymentMethod.DEBIT }.map { it.amountMinor })
        return BudgetSummary(month, resolveBudget(ledger, month), spent, recurring, daily, credit, debit)
    }

    fun addExpense(amountMinor: Long, occurredOn: String? = null, method: PaymentMethod = PaymentMethod.CREDIT, description: String? = null): Expense {
        val date = occurredOn ?: clock.today()
        CalendarRules.requireDate(date)
        MoneyRules.requirePositive(amountMinor, "expense amount")
        val expense = Expense(ids.next(), amountMinor, date, method, description.clean())
        update { it.copy(expenses = it.expenses + expense) }
        return expense
    }

    fun editExpense(id: String, amountMinor: Long? = null, occurredOn: String? = null, method: PaymentMethod? = null, description: String? = null): Expense {
        var result: Expense? = null
        update { ledger ->
            val current = ledger.expenses.findById(id)
            val amount = amountMinor ?: current.amountMinor
            MoneyRules.requirePositive(amount, "expense amount")
            val date = occurredOn ?: current.occurredOn
            CalendarRules.requireDate(date)
            val updated = current.copy(amountMinor = amount, occurredOn = date, paymentMethod = method ?: current.paymentMethod, description = description.clean() ?: current.description)
            result = updated
            ledger.copy(expenses = ledger.expenses.replace(updated))
        }
        return result ?: error("Expense was not edited")
    }

    fun deleteExpense(id: String) {
        update { ledger ->
            ledger.expenses.findById(id)
            ledger.copy(expenses = ledger.expenses.filterNot { it.id == id })
        }
    }

    fun addRecurringExpense(name: String, amountMinor: Long, startsMonth: String, method: PaymentMethod = PaymentMethod.CREDIT): RecurringExpense {
        requireName(name, "recurring expense")
        CalendarRules.requireMonth(startsMonth)
        MoneyRules.requirePositive(amountMinor, "recurring expense amount")
        val recurring = RecurringExpense(ids.next(), name.trim())
        val schedule = RecurringExpenseSchedule(ids.next(), recurring.id, startsMonth, amountMinor, method)
        update { it.copy(recurringExpenses = it.recurringExpenses + recurring, recurringExpenseSchedules = it.recurringExpenseSchedules + schedule) }
        return recurring
    }

    fun changeRecurringExpense(id: String, amountMinor: Long, startsMonth: String, method: PaymentMethod? = null): RecurringExpenseSchedule {
        CalendarRules.requireMonth(startsMonth)
        MoneyRules.requirePositive(amountMinor, "recurring expense amount")
        val ledger = store.read()
        requireRecurringExpense(ledger, id)
        val selectedMethod = method ?: resolveRecurringSchedule(ledger, id, startsMonth)?.paymentMethod ?: PaymentMethod.CREDIT
        val schedule = RecurringExpenseSchedule(ids.next(), id, startsMonth, amountMinor, selectedMethod)
        update { current -> current.copy(recurringExpenseSchedules = current.recurringExpenseSchedules.replaceByExpenseMonth(schedule)) }
        return schedule
    }

    fun overrideRecurringExpense(id: String, month: String, amountMinor: Long): RecurringExpenseOverride {
        CalendarRules.requireMonth(month)
        if (amountMinor < 0) throw FinanceException("Invalid recurring expense override '$amountMinor'; expected zero or positive minor units")
        val ledger = store.read()
        requireRecurringExpense(ledger, id)
        if (resolveRecurringSchedule(ledger, id, month) == null) {
            throw FinanceException("Invalid recurring expense override month '$month'; expected a month on or after the first schedule")
        }
        val override = RecurringExpenseOverride(ids.next(), id, month, amountMinor)
        update { current -> current.copy(recurringExpenseOverrides = current.recurringExpenseOverrides.replaceByExpenseMonth(override)) }
        return override
    }

    fun clearRecurringExpenseOverride(id: String, month: String) {
        CalendarRules.requireMonth(month)
        requireRecurringExpense(store.read(), id)
        update { ledger -> ledger.copy(recurringExpenseOverrides = ledger.recurringExpenseOverrides.filterNot { it.recurringExpenseId == id && it.budgetMonth == month }) }
    }

    fun stopRecurringExpense(id: String, fromMonth: String) {
        CalendarRules.requireMonth(fromMonth)
        requireRecurringExpense(store.read(), id)
        update { ledger -> ledger.copy(recurringExpenses = ledger.recurringExpenses.map { if (it.id == id) it.copy(stoppedFromMonth = fromMonth) else it }) }
    }

    fun resolvedRecurringExpenses(month: String): List<ResolvedRecurringExpense> {
        CalendarRules.requireMonth(month)
        val ledger = store.read()
        return resolvedRecurringExpenses(ledger, month)
    }

    fun addIncomeSource(name: String, amountMinor: Long, startsMonth: String): IncomeSource {
        requireName(name, "income source")
        CalendarRules.requireMonth(startsMonth)
        MoneyRules.requirePositive(amountMinor, "recurring income amount")
        val source = IncomeSource(ids.next(), name.trim())
        val schedule = IncomeSchedule(ids.next(), source.id, startsMonth, amountMinor)
        update { it.copy(sources = it.sources + source, schedules = it.schedules + schedule) }
        return source
    }

    fun changeRecurringIncome(sourceId: String, amountMinor: Long, startsMonth: String): IncomeSchedule {
        CalendarRules.requireMonth(startsMonth)
        MoneyRules.requirePositive(amountMinor, "recurring income amount")
        requireSource(store.read(), sourceId)
        val schedule = IncomeSchedule(ids.next(), sourceId, startsMonth, amountMinor)
        update { ledger -> ledger.copy(schedules = ledger.schedules.replaceBySourceMonth(schedule)) }
        return schedule
    }

    fun overrideIncome(sourceId: String, month: String, amountMinor: Long): IncomeOverride {
        CalendarRules.requireMonth(month)
        if (amountMinor < 0) throw FinanceException("Invalid income override '$amountMinor'; expected zero or positive minor units")
        val ledger = store.read()
        requireSource(ledger, sourceId)
        if (ledger.schedules.none { it.sourceId == sourceId && CalendarRules.compareMonths(it.startsMonth, month) <= 0 }) {
            throw FinanceException("Invalid income override month '$month'; expected a month on or after the first schedule")
        }
        val override = IncomeOverride(ids.next(), sourceId, month, amountMinor)
        update { ledger -> ledger.copy(overrides = ledger.overrides.replaceBySourceMonth(override)) }
        return override
    }

    fun clearIncomeOverride(sourceId: String, month: String) {
        CalendarRules.requireMonth(month)
        requireSource(store.read(), sourceId)
        update { ledger -> ledger.copy(overrides = ledger.overrides.filterNot { it.sourceId == sourceId && it.budgetMonth == month }) }
    }

    fun stopIncome(sourceId: String, fromMonth: String) {
        CalendarRules.requireMonth(fromMonth)
        requireSource(store.read(), sourceId)
        update { ledger -> ledger.copy(sources = ledger.sources.map { if (it.id == sourceId) it.copy(stoppedFromMonth = fromMonth) else it }) }
    }

    private fun resolveBudget(ledger: Ledger, month: String): Long = ledger.sources.fold(0L) { total, source -> checkedAdd(total, resolveSource(ledger, source, month)) }

    private fun resolveSource(ledger: Ledger, source: IncomeSource, month: String): Long {
        if (source.stoppedFromMonth != null && CalendarRules.compareMonths(month, source.stoppedFromMonth) >= 0) return 0
        val schedule = ledger.schedules.filter { it.sourceId == source.id && CalendarRules.compareMonths(it.startsMonth, month) <= 0 }.maxByOrNull { it.startsMonth } ?: return 0
        val override = ledger.overrides.find { it.sourceId == source.id && it.budgetMonth == month }
        if (override != null) return override.amountMinor
        return schedule.amountMinor
    }

    private fun resolvedRecurringExpenses(ledger: Ledger, month: String): List<ResolvedRecurringExpense> = ledger.recurringExpenses.map { expense ->
        val amount = resolveRecurringAmount(ledger, expense.id, month)
        val schedule = resolveRecurringSchedule(ledger, expense.id, month)
        ResolvedRecurringExpense(expense.id, expense.name, month, amount, schedule?.paymentMethod ?: PaymentMethod.CREDIT)
    }

    private fun resolveRecurringAmount(ledger: Ledger, id: String, month: String): Long {
        val expense = ledger.recurringExpenses.find { it.id == id } ?: return 0
        if (expense.stoppedFromMonth != null && CalendarRules.compareMonths(month, expense.stoppedFromMonth) >= 0) return 0
        val schedule = resolveRecurringSchedule(ledger, id, month) ?: return 0
        val override = ledger.recurringExpenseOverrides.find { it.recurringExpenseId == id && it.budgetMonth == month }
        if (override != null) return override.amountMinor
        return schedule.amountMinor
    }

    private fun resolveRecurringSchedule(ledger: Ledger, id: String, month: String): RecurringExpenseSchedule? = ledger.recurringExpenseSchedules
        .filter { it.recurringExpenseId == id && CalendarRules.compareMonths(it.startsMonth, month) <= 0 }
        .maxByOrNull { it.startsMonth }

    private fun expensesForMonth(ledger: Ledger, month: String): List<Expense> = ledger.expenses.filter { CalendarRules.monthOf(it.occurredOn) == month }

    private fun dailySpending(expenses: List<Expense>): List<DailySpending> = expenses.groupBy { it.occurredOn }.toSortedMap().map { (date, entries) -> DailySpending(date, sumAmounts(entries.map { it.amountMinor })) }

    private fun sumAmounts(amounts: List<Long>): Long = amounts.fold(0L, ::checkedAdd)

    private fun update(change: (Ledger) -> Ledger) {
        val current = store.read()
        store.replace(change(current).copy(revision = checkedAdd(current.revision, 1L)))
    }

    private fun requireSource(ledger: Ledger, sourceId: String) {
        if (ledger.sources.none { it.id == sourceId }) throw FinanceException("Unknown income source '$sourceId'; expected a registered source ID")
    }

    private fun requireRecurringExpense(ledger: Ledger, id: String) {
        if (ledger.recurringExpenses.none { it.id == id }) throw FinanceException("Unknown recurring expense '$id'; expected a registered recurring expense ID")
    }

    private fun requireName(name: String, label: String) {
        if (name.trim().isEmpty()) throw FinanceException("Invalid $label '$name'; expected a nonempty name")
    }

    private fun List<Expense>.findById(id: String): Expense = find { it.id == id } ?: throw FinanceException("Unknown expense '$id'; expected an existing expense ID")

    private fun List<Expense>.replace(updated: Expense): List<Expense> = map { if (it.id == updated.id) updated else it }

    private fun List<IncomeOverride>.replaceBySourceMonth(updated: IncomeOverride): List<IncomeOverride> = filterNot { it.sourceId == updated.sourceId && it.budgetMonth == updated.budgetMonth } + updated

    private fun List<IncomeSchedule>.replaceBySourceMonth(updated: IncomeSchedule): List<IncomeSchedule> = filterNot { it.sourceId == updated.sourceId && it.startsMonth == updated.startsMonth } + updated

    private fun List<RecurringExpenseSchedule>.replaceByExpenseMonth(updated: RecurringExpenseSchedule): List<RecurringExpenseSchedule> = filterNot { it.recurringExpenseId == updated.recurringExpenseId && it.startsMonth == updated.startsMonth } + updated

    private fun List<RecurringExpenseOverride>.replaceByExpenseMonth(updated: RecurringExpenseOverride): List<RecurringExpenseOverride> = filterNot { it.recurringExpenseId == updated.recurringExpenseId && it.budgetMonth == updated.budgetMonth } + updated

    private fun checkedAdd(left: Long, right: Long): Long {
        if (right > 0 && left > Long.MAX_VALUE - right) throw FinanceException("Amount overflow '$left + $right'; expected signed 64-bit minor units")
        return left + right
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
