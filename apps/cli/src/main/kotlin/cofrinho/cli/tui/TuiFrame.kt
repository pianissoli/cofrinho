package cofrinho.cli.tui

import com.googlecode.lanterna.TerminalSize

/** Keeps lists and feedback in separate rows. Example: frame.field(4, "Amount", "10", true). */
internal class TuiFrame(private val terminal: TerminalPort, val size: TerminalSize) {
    fun line(row: Int, value: String, bold: Boolean = false) {
        if (row !in 0 until size.rows) return
        val width = size.columns.coerceAtLeast(1)
        terminal.write(0, row, value.take(width).padEnd(width, ' '), bold)
    }

    fun field(row: Int, label: String, value: String, focused: Boolean) {
        line(row, "${if (focused) ">" else " "} ${label.padEnd(13)} $value", focused)
    }

    fun button(row: Int, label: String, focused: Boolean) {
        line(row, "${if (focused) ">" else " "} [ $label ]", focused)
    }

    fun notice(message: String?) {
        if (message == null) return
        line(size.rows - 4, "Notice: $message")
    }

    fun listCapacity(firstRow: Int): Int = (size.rows - 5 - firstRow).coerceAtLeast(1)

    fun actions(value: String): Unit = line(size.rows - 5, value)

    fun footer(value: String): Unit = line(size.rows - 2, value)
}
