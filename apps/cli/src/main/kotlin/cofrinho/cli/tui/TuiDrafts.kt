package cofrinho.cli.tui

import cofrinho.finance.PaymentMethod

internal enum class TuiScreen { SETUP, OVERVIEW, EXPENSE, RECURRING, RECURRING_FORM }

internal enum class RecurringFormMode { ADD, CHANGE, OVERRIDE }

internal class SetupDraft {
    var currency: String = "BRL"
    var scale: String = "2"
    var focus: Int = 0
    var error: String? = null

    fun append(character: Char) {
        if (focus == 0 && currency.length < 3) currency += character.uppercaseChar()
        if (focus == 1 && scale.length < 1 && character.isDigit()) scale += character
        error = null
    }

    fun backspace() {
        if (focus == 0 && currency.isNotEmpty()) currency = currency.dropLast(1)
        if (focus == 1 && scale.isNotEmpty()) scale = scale.dropLast(1)
        error = null
    }
}

internal class ExpenseDraft(today: String) {
    var amount: String = ""
    var description: String = ""
    var date: String = today
    var payment: PaymentMethod = PaymentMethod.CREDIT
    var focus: Int = 0
    var dateTouched: Boolean = false
    var dirty: Boolean = false
    var confirmation: Boolean = false
    var error: String? = null

    fun append(character: Char, scale: Int) {
        val before = "$amount|$description|$date|$payment"
        when (focus) {
            0 -> appendAmount(character, scale)
            1 -> description += character
            2 -> { date += character; dateTouched = true }
            3 -> choosePayment(character)
        }
        dirty = dirty || before != "$amount|$description|$date|$payment"
        error = null
    }

    fun changePayment(method: PaymentMethod) {
        payment = method
        dirty = true
        error = null
    }

    fun backspace() {
        when (focus) {
            0 -> amount = amount.dropLast(1)
            1 -> description = description.dropLast(1)
            2 -> { date = date.dropLast(1); dateTouched = true }
        }
        dirty = true
        error = null
    }

    private fun appendAmount(character: Char, scale: Int) {
        if (character.isDigit()) {
            val fraction = amount.substringAfter('.', "")
            if ('.' !in amount || fraction.length < scale) amount += character
            return
        }
        if (character == '.' && amount.isNotEmpty() && scale > 0 && '.' !in amount) amount += character
    }

    private fun choosePayment(character: Char) {
        if (character.equals('d', true)) payment = PaymentMethod.DEBIT
        if (character.equals('c', true)) payment = PaymentMethod.CREDIT
    }
}

internal class RecurringDraft(
    val mode: RecurringFormMode,
    val recurringExpenseId: String?,
    from: String,
    initialAmount: String = "",
    initialPayment: PaymentMethod = PaymentMethod.CREDIT,
) {
    var name: String = ""
    var amount: String = initialAmount
    var fromMonth: String = from
    var payment: PaymentMethod = initialPayment
    var paymentTouched: Boolean = false
    var focus: Int = 0
    var error: String? = null

    val focusCount: Int get() = when (mode) {
        RecurringFormMode.ADD -> 6
        RecurringFormMode.CHANGE -> 5
        RecurringFormMode.OVERRIDE -> 4
    }

    fun append(character: Char, scale: Int) {
        when (mode) {
            RecurringFormMode.ADD -> when (focus) {
                0 -> name += character
                1 -> appendAmount(character, scale)
                2 -> fromMonth += character
                3 -> choosePayment(character)
            }
            RecurringFormMode.CHANGE -> when (focus) {
                0 -> appendAmount(character, scale)
                1 -> fromMonth += character
                2 -> choosePayment(character)
            }
            RecurringFormMode.OVERRIDE -> when (focus) {
                0 -> appendAmount(character, scale)
                1 -> fromMonth += character
            }
        }
        error = null
    }

    fun backspace() {
        when (mode) {
            RecurringFormMode.ADD -> when (focus) {
                0 -> name = name.dropLast(1)
                1 -> amount = amount.dropLast(1)
                2 -> fromMonth = fromMonth.dropLast(1)
            }
            RecurringFormMode.CHANGE -> when (focus) {
                0 -> amount = amount.dropLast(1)
                1 -> fromMonth = fromMonth.dropLast(1)
            }
            RecurringFormMode.OVERRIDE -> when (focus) {
                0 -> amount = amount.dropLast(1)
                1 -> fromMonth = fromMonth.dropLast(1)
            }
        }
        error = null
    }

    private fun appendAmount(character: Char, scale: Int) {
        if (character.isDigit()) {
            val fraction = amount.substringAfter('.', "")
            if ('.' !in amount || fraction.length < scale) amount += character
            return
        }
        if (character == '.' && amount.isNotEmpty() && scale > 0 && '.' !in amount) amount += character
    }

    private fun choosePayment(character: Char) {
        if (character.equals('d', true)) { payment = PaymentMethod.DEBIT; paymentTouched = true }
        if (character.equals('c', true)) { payment = PaymentMethod.CREDIT; paymentTouched = true }
    }
}

internal fun TerminalKey.isCharacter(expected: Char): Boolean =
    kind == TerminalKeyKind.CHARACTER && character?.equals(expected, true) == true
