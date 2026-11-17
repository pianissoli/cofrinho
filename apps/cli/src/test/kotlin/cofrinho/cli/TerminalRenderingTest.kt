package cofrinho.cli

import cofrinho.cli.tui.LanternaTerminal
import cofrinho.cli.tui.TerminalKey
import cofrinho.cli.tui.TerminalKeyKind
import cofrinho.cli.tui.TuiFrame
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalRenderingTest {
    @Test
    fun frameErasesOldRowsAndKeepsWritesBuffered() {
        val virtual = DefaultVirtualTerminal(TerminalSize(80, 24))
        LanternaTerminal(virtual).use { terminal ->
            terminal.start()
            terminal.clear()
            terminal.write(0, 10, "Previous screen")
            terminal.flush()
            terminal.clear()
            terminal.write(0, 2, "Next screen")
            assertEquals('P', virtual.getCharacter(0, 10).character)
            terminal.flush()
            assertEquals(' ', virtual.getCharacter(0, 10).character)
            assertEquals('N', virtual.getCharacter(0, 2).character)
        }
    }

    @Test
    fun boldSelectionDoesNotLeakIntoUnselectedText() {
        val virtual = DefaultVirtualTerminal(TerminalSize(80, 24))
        LanternaTerminal(virtual).use { terminal ->
            terminal.start()
            terminal.clear()
            terminal.write(0, 3, "Selected", true)
            terminal.write(0, 4, "Other")
            terminal.flush()
            assertTrue(virtual.getCharacter(0, 3).isBold)
            assertFalse(virtual.getCharacter(0, 4).isBold)
            terminal.clear()
            terminal.write(0, 3, "Other")
            terminal.write(0, 4, "Selected", true)
            terminal.flush()
            assertFalse(virtual.getCharacter(0, 3).isBold)
            assertTrue(virtual.getCharacter(0, 4).isBold)
        }
    }

    @Test
    fun unchangedFrameDoesNotClearOrRewriteThePhysicalScreen() {
        val virtual = DefaultVirtualTerminal(TerminalSize(80, 24))
        LanternaTerminal(virtual).use { terminal ->
            terminal.start()
            terminal.clear()
            terminal.write(0, 3, "Same frame", true)
            terminal.flush()
            virtual.getAndResetDirtyCells()
            virtual.isWholeBufferDirtyThenReset()
            terminal.clear()
            terminal.write(0, 3, "Same frame", true)
            terminal.flush()
            assertTrue(virtual.getAndResetDirtyCells().isEmpty())
            assertFalse(virtual.isWholeBufferDirtyThenReset())
        }
    }

    @Test
    fun resizedFrameReservesRowsForActionsNoticesAndFooter() {
        val virtual = DefaultVirtualTerminal(TerminalSize(80, 24))
        LanternaTerminal(virtual).use { terminal ->
            terminal.start()
            virtual.setTerminalSize(TerminalSize(100, 40))
            val frame = TuiFrame(terminal, terminal.size())
            terminal.clear()
            frame.actions("Actions")
            frame.notice("Saved")
            frame.footer("Help")
            frame.field(4, "Amount", "10", true)
            frame.button(8, "Save", false)
            frame.line(100, "Outside the screen")
            terminal.flush()
            assertEquals(29, frame.listCapacity(6))
            assertEquals('A', virtual.getCharacter(0, 35).character)
            assertEquals('N', virtual.getCharacter(0, 36).character)
            assertEquals('H', virtual.getCharacter(0, 38).character)
            assertTrue(virtual.getCharacter(0, 4).isBold)
            assertFalse(virtual.getCharacter(0, 8).isBold)
        }
    }

    @Test
    fun bufferedScreenPreservesKeyboardInputAndClosesPrivateMode() {
        val virtual = DefaultVirtualTerminal(TerminalSize(80, 24))
        LanternaTerminal(virtual).use { terminal ->
            terminal.start()
            virtual.addInput(KeyStroke(KeyType.ArrowDown))
            assertEquals(TerminalKey(TerminalKeyKind.DOWN), terminal.readKey())
        }
        assertTrue(virtual.isCursorVisible)
    }
}
