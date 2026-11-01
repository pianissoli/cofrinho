package cofrinho.finance

class LedgerService(
    private val store: LedgerStore,
    private val clock: LedgerClock,
    private val ids: IdentifierSource,
) {
    fun summary(month: String): BudgetSummary {
        CalendarRules.requireMonth(month)
        val ledger = store.read()
        val budget = resolveBudget(ledger, month)
        val recorded = expensesForMonth(ledger, month, ExpenseStatus.RECORDED)
        val spent = recorded.fold(0L) { total, expense -> checkedAdd(total, expense.amountMinor) }
        val reserved = sumExpenses(ledger, month, ExpenseStatus.PLANNED)
        val daily = recorded.groupBy { it.occurredOn!! }.toSortedMap().map { (date, expenses) ->
            DailySpending(date, expenses.fold(0L) { total, expense -> checkedAdd(total, expense.amountMinor) })
        }
        val credit = recorded.filter { it.paymentMethod == PaymentMethod.CREDIT }.fold(0L) { total, expense -> checkedAdd(total, expense.amountMinor) }
        val debit = recorded.filter { it.paymentMethod == PaymentMethod.DEBIT }.fold(0L) { total, expense -> checkedAdd(total, expense.amountMinor) }
        return BudgetSummary(month, budget, spent, reserved, daily, credit, debit)
    }

    fun addExpense(amountMinor: Long, occurredOn: String? = null, method: PaymentMethod = PaymentMethod.CREDIT, description: String? = null): Expense {
        val date = occurredOn ?: clock.today()
        CalendarRules.requireDate(date)
        MoneyRules.requirePositive(amountMinor, "expense amount")
        val expense = Expense(ids.next(), amountMinor, ExpenseStatus.RECORDED, null, date, method, description.clean())
        update { it.copy(expenses = it.expenses + expense) }
        return expense
    }

    fun planExpense(amountMinor: Long, expectedOn: String, method: PaymentMethod = PaymentMethod.CREDIT, description: String? = null): Expense {
        CalendarRules.requireDate(expectedOn)
        MoneyRules.requirePositive(amountMinor, "planned expense amount")
        val expense = Expense(ids.next(), amountMinor, ExpenseStatus.PLANNED, expectedOn, null, method, description.clean())
        update { it.copy(expenses = it.expenses + expense) }
        return expense
    }

    fun recordPlanned(id: String, amountMinor: Long, occurredOn: String? = null, method: PaymentMethod? = null): Expense {
        val date = occurredOn ?: clock.today()
        CalendarRules.requireDate(date)
        MoneyRules.requirePositive(amountMinor, "recorded expense amount")
        var result: Expense? = null
        update { ledger ->
            val current = ledger.expenses.findById(id)
            requireStatus(current, ExpenseStatus.PLANNED)
            val updated = current.copy(status = ExpenseStatus.RECORDED, amountMinor = amountMinor, occurredOn = date, paymentMethod = method ?: current.paymentMethod)
            result = updated
            ledger.copy(expenses = ledger.expenses.replace(updated))
        }
        return result ?: error("Recorded expense was not produced")
    }

    fun reschedulePlan(id: String, expectedOn: String): Expense = mutatePlan(id) { it.copy(expectedOn = expectedOn) }

    fun cancelPlan(id: String): Expense = mutatePlan(id) { it.copy(status = ExpenseStatus.CANCELLED) }

    fun deleteExpense(id: String) {
        update { ledger ->
            ledger.expenses.findById(id)
            ledger.copy(expenses = ledger.expenses.filterNot { it.id == id })
        }
    }

    fun editExpense(id: String, amountMinor: Long? = null, occurredOn: String? = null, method: PaymentMethod? = null, description: String? = null): Expense {
        var result: Expense? = null
        update { ledger ->
            val current = ledger.expenses.findById(id)
            requireStatus(current, ExpenseStatus.RECORDED)
            val amount = amountMinor ?: current.amountMinor
            MoneyRules.requirePositive(amount, "expense amount")
            val date = occurredOn ?: current.occurredOn!!
            CalendarRules.requireDate(date)
            val updated = current.copy(amountMinor = amount, occurredOn = date, paymentMethod = method ?: current.paymentMethod, description = description.clean() ?: current.description)
            result = updated
            ledger.copy(expenses = ledger.expenses.replace(updated))
        }
        return result ?: error("Expense was not edited")
    }

    /** Changes a reservation without charging spending. Example: service.editPlannedExpense(id, 12000, "2026-11-01"). */
    fun editPlannedExpense(id: String, amountMinor: Long? = null, expectedOn: String? = null, method: PaymentMethod? = null, description: String? = null): Expense {
        var result: Expense? = null
        update { ledger ->
            val current = ledger.expenses.findById(id)
            requireStatus(current, ExpenseStatus.PLANNED)
            val amount = amountMinor ?: current.amountMinor
            MoneyRules.requirePositive(amount, "planned expense amount")
            val date = expectedOn ?: current.expectedOn!!
            CalendarRules.requireDate(date)
            val updated = current.copy(amountMinor = amount, expectedOn = date, paymentMethod = method ?: current.paymentMethod, description = description.clean() ?: current.description)
            result = updated
            ledger.copy(expenses = ledger.expenses.replace(updated))
        }
        return result ?: error("Planned expense was not edited")
    }

    /** Reopens a recorded plan with confirmed reservation details. Example: service.reopenRecordedExpense(id, 10000, "2026-11-01"). */
    fun reopenRecordedExpense(id: String, amountMinor: Long, expectedOn: String, method: PaymentMethod? = null): Expense {
        CalendarRules.requireDate(expectedOn)
        MoneyRules.requirePositive(amountMinor, "reopened planned expense amount")
        var result: Expense? = null
        update { ledger ->
            val current = ledger.expenses.findById(id)
            requireStatus(current, ExpenseStatus.RECORDED)
            val updated = current.copy(status = ExpenseStatus.PLANNED, amountMinor = amountMinor, expectedOn = expectedOn, occurredOn = null, paymentMethod = method ?: current.paymentMethod)
            result = updated
            ledger.copy(expenses = ledger.expenses.replace(updated))
        }
        return result ?: error("Recorded expense was not reopened")
    }

    fun addIncomeSource(name: String, amountMinor: Long, startsMonth: String): IncomeSource {
        requireName(name)
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
        requireSource(store.read(), sourceId)
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
        val override = ledger.overrides.find { it.sourceId == source.id && it.budgetMonth == month }
        if (override != null) return override.amountMinor
        return ledger.schedules.filter { it.sourceId == source.id && CalendarRules.compareMonths(it.startsMonth, month) <= 0 }.maxByOrNull { it.startsMonth }?.amountMinor ?: 0
    }

    private fun sumExpenses(ledger: Ledger, month: String, status: ExpenseStatus): Long = expensesForMonth(ledger, month, status).fold(0L) { total, expense -> checkedAdd(total, expense.amountMinor) }

    private fun expensesForMonth(ledger: Ledger, month: String, status: ExpenseStatus): List<Expense> = ledger.expenses.filter { it.status == status && CalendarRules.monthOf(it.dateFor(status)) == month }

    private fun Expense.dateFor(status: ExpenseStatus): String = if (status == ExpenseStatus.PLANNED) expectedOn!! else occurredOn!!

    private fun mutatePlan(id: String, change: (Expense) -> Expense): Expense {
        var result: Expense? = null
        update { ledger ->
            val current = ledger.expenses.findById(id)
            requireStatus(current, ExpenseStatus.PLANNED)
            val changed = change(current).also { CalendarRules.requireDate(it.expectedOn!!) }
            result = changed
            ledger.copy(expenses = ledger.expenses.replace(changed))
        }
        return result ?: error("Planned expense was not changed")
    }

    private fun update(change: (Ledger) -> Ledger) {
        val current = store.read()
        store.replace(change(current).copy(revision = checkedAdd(current.revision, 1L)))
    }

    private fun requireSource(ledger: Ledger, sourceId: String) {
        if (ledger.sources.none { it.id == sourceId }) throw FinanceException("Unknown income source '$sourceId'; expected a registered source ID")
    }

    private fun requireName(name: String) {
        if (name.trim().isEmpty()) throw FinanceException("Invalid income source '$name'; expected a nonempty name")
    }

    private fun requireStatus(expense: Expense, expected: ExpenseStatus) {
        if (expense.status != expected) throw FinanceException("Expense '${expense.id}' has status '${expense.status}'; expected $expected")
    }

    private fun List<Expense>.findById(id: String): Expense = find { it.id == id } ?: throw FinanceException("Unknown expense '$id'; expected an existing expense ID")

    private fun List<Expense>.replace(updated: Expense): List<Expense> = map { if (it.id == updated.id) updated else it }

    private fun List<IncomeOverride>.replaceBySourceMonth(updated: IncomeOverride): List<IncomeOverride> = filterNot { it.sourceId == updated.sourceId && it.budgetMonth == updated.budgetMonth } + updated

    private fun List<IncomeSchedule>.replaceBySourceMonth(updated: IncomeSchedule): List<IncomeSchedule> = filterNot { it.sourceId == updated.sourceId && it.startsMonth == updated.startsMonth } + updated

    private fun checkedAdd(left: Long, right: Long): Long {
        if (right > 0 && left > Long.MAX_VALUE - right) throw FinanceException("Amount overflow '$left + $right'; expected signed 64-bit minor units")
        return left + right
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
