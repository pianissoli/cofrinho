package cofrinho.cli.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.terminal.Terminal

enum class TerminalKeyKind {
    CHARACTER, UP, DOWN, LEFT, RIGHT, TAB, BACKTAB, ENTER, ESCAPE, BACKSPACE, DELETE, PAGE_UP, PAGE_DOWN, RESIZE, EOF, OTHER,
}

data class TerminalKey(val kind: TerminalKeyKind, val character: Char? = null)

interface TerminalPort : AutoCloseable {
    fun start()
    fun stop()
    fun clear()
    fun size(): TerminalSize
    fun write(column: Int, row: Int, value: String)
    fun flush()
    fun readKey(): TerminalKey?
}

class LanternaTerminal(private val terminal: Terminal) : TerminalPort {
    override fun start() {
        terminal.enterPrivateMode()
        terminal.setForegroundColor(TextColor.ANSI.WHITE)
        terminal.setBackgroundColor(TextColor.ANSI.BLACK)
        terminal.setCursorVisible(false)
    }

    override fun stop() {
        runCatching { terminal.setCursorVisible(true) }
        runCatching { terminal.exitPrivateMode() }
    }

    override fun close() {
        stop()
        terminal.close()
    }

    override fun clear() = terminal.clearScreen()

    override fun size(): TerminalSize = terminal.terminalSize

    override fun write(column: Int, row: Int, value: String) {
        terminal.setCursorPosition(TerminalPosition(column, row))
        terminal.putString(value)
    }

    override fun flush() = terminal.flush()

    override fun readKey(): TerminalKey? = terminal.readInput()?.let(::mapKey)

    private fun mapKey(key: KeyStroke): TerminalKey = when (key.keyType) {
        KeyType.Character -> TerminalKey(TerminalKeyKind.CHARACTER, key.character)
        KeyType.ArrowUp -> TerminalKey(TerminalKeyKind.UP)
        KeyType.ArrowDown -> TerminalKey(TerminalKeyKind.DOWN)
        KeyType.ArrowLeft -> TerminalKey(TerminalKeyKind.LEFT)
        KeyType.ArrowRight -> TerminalKey(TerminalKeyKind.RIGHT)
        KeyType.Tab -> TerminalKey(TerminalKeyKind.TAB)
        KeyType.ReverseTab -> TerminalKey(TerminalKeyKind.BACKTAB)
        KeyType.Enter -> TerminalKey(TerminalKeyKind.ENTER)
        KeyType.Escape -> TerminalKey(TerminalKeyKind.ESCAPE)
        KeyType.Backspace -> TerminalKey(TerminalKeyKind.BACKSPACE)
        KeyType.Delete -> TerminalKey(TerminalKeyKind.DELETE)
        KeyType.PageUp -> TerminalKey(TerminalKeyKind.PAGE_UP)
        KeyType.PageDown -> TerminalKey(TerminalKeyKind.PAGE_DOWN)
        KeyType.Resize -> TerminalKey(TerminalKeyKind.RESIZE)
        KeyType.EOF -> TerminalKey(TerminalKeyKind.EOF)
        else -> TerminalKey(TerminalKeyKind.OTHER)
    }
}

object LanternaTerminalFactory {
    fun open(): TerminalPort = LanternaTerminal(DefaultTerminalFactory().createTerminal())
}
