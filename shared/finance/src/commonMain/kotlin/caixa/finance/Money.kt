package caixa.finance

object MoneyRules {
    fun parseMinor(input: String, scale: Int): Long {
        if (scale !in 0..6) throw FinanceException("Invalid currency scale '$scale'; expected 0-6")
        val value = input.trim()
        if (!value.matches(Regex("\\d+(\\.\\d{1,6})?"))) {
            throw FinanceException("Invalid amount '$input'; expected a positive decimal amount")
        }
        val parts = value.split('.')
        val whole = parts[0].toLongOrNull() ?: failAmount(input)
        val fraction = parts.getOrNull(1).orEmpty()
        if (fraction.length > scale) failAmount(input)
        val padded = fraction.padEnd(scale, '0')
        val minor = whole.checkedTimes(powerOfTen(scale))
        return minor.checkedPlus(padded.ifEmpty { "0" }.toLong())
    }

    fun formatMinor(amountMinor: Long, scale: Int): String {
        if (scale !in 0..6) throw FinanceException("Invalid currency scale '$scale'; expected 0-6")
        val raw = amountMinor.toString()
        val negative = raw.startsWith('-')
        val digits = raw.removePrefix("-").padStart(scale + 1, '0')
        if (scale == 0) return raw
        val split = digits.length - scale
        val whole = digits.substring(0, split)
        val fraction = digits.substring(split)
        return (if (negative) "-" else "") + "$whole.$fraction"
    }

    fun requirePositive(amountMinor: Long, label: String) {
        if (amountMinor <= 0) throw FinanceException("Invalid $label '$amountMinor'; expected a positive minor-unit amount")
    }

    private fun powerOfTen(scale: Int): Long = (0 until scale).fold(1L) { result, _ -> result.checkedTimes(10) }

    private fun failAmount(input: String): Nothing = throw FinanceException("Invalid amount '$input'; expected a positive decimal amount")

    private fun Long.checkedPlus(other: Long): Long {
        if (other > 0 && this > Long.MAX_VALUE - other) throw FinanceException("Amount overflow '$this + $other'; expected signed 64-bit minor units")
        return this + other
    }

    private fun Long.checkedTimes(other: Long): Long {
        if (other != 0L && this > Long.MAX_VALUE / other) throw FinanceException("Amount overflow '$this * $other'; expected signed 64-bit minor units")
        return this * other
    }
}
