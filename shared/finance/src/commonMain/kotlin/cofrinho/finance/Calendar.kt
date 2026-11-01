package cofrinho.finance

object CalendarRules {
    fun monthOf(date: String): String {
        requireDate(date)
        return date.substring(0, 7)
    }

    fun requireMonth(month: String) {
        if (!month.matches(Regex("\\d{4}-\\d{2}"))) {
            throw FinanceException("Invalid month '$month'; expected YYYY-MM")
        }
        val number = month.substring(5).toInt()
        if (number !in 1..12) {
            throw FinanceException("Invalid month '$month'; expected month 01-12")
        }
    }

    fun requireDate(date: String) {
        if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            throw FinanceException("Invalid date '$date'; expected YYYY-MM-DD")
        }
        val year = date.substring(0, 4).toInt()
        val month = date.substring(5, 7).toInt()
        val day = date.substring(8).toInt()
        val max = daysInMonth(year, month)
        if (day !in 1..max) {
            throw FinanceException("Invalid date '$date'; expected a real calendar date")
        }
    }

    fun compareMonths(left: String, right: String): Int {
        requireMonth(left)
        requireMonth(right)
        return left.compareTo(right)
    }

    private fun daysInMonth(year: Int, month: Int): Int {
        if (month !in 1..12) return 0
        if (month == 2) return if (isLeap(year)) 29 else 28
        return if (month in listOf(4, 6, 9, 11)) 30 else 31
    }

    private fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}
