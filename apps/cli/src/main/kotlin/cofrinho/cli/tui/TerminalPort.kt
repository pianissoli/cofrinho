package cofrinho.cli.tui

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.TextGraphics
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.TerminalScreen
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
    fun write(column: Int, row: Int, value: String, bold: Boolean = false)
    fun flush()
    fun readKey(): TerminalKey?
}

class LanternaTerminal(private val terminal: Terminal) : TerminalPort {
    private val bufferedScreen: TerminalScreen = TerminalScreen(terminal)

    override fun start() {
        bufferedScreen.startScreen()
        bufferedScreen.cursorPosition = null
    }

    override fun stop() {
        runCatching { bufferedScreen.stopScreen() }
    }

    override fun close() {
        stop()
        terminal.close()
    }

    override fun clear() {
        // Filling only the back buffer lets refresh erase stale cells without a physical clear on every key.
        frameGraphics().fill(' ')
    }

    override fun size(): TerminalSize {
        bufferedScreen.doResizeIfNecessary()
        return bufferedScreen.terminalSize
    }

    override fun write(column: Int, row: Int, value: String, bold: Boolean) {
        val graphics = frameGraphics()
        if (bold) graphics.enableModifiers(SGR.BOLD)
        graphics.putString(column, row, value)
    }

    private fun frameGraphics(): TextGraphics = bufferedScreen.newTextGraphics().apply {
        foregroundColor = TextColor.ANSI.WHITE
        backgroundColor = TextColor.ANSI.BLACK
    }

    override fun flush() = bufferedScreen.refresh()

    override fun readKey(): TerminalKey? = bufferedScreen.readInput()?.let(::mapKey)

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
        KeyType.EOF -> TerminalKey(TerminalKeyKind.EOF)
        else -> TerminalKey(TerminalKeyKind.OTHER)
    }
}

object LanternaTerminalFactory {
    fun open(): TerminalPort = LanternaTerminal(DefaultTerminalFactory().createTerminal())
}
