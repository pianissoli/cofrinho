package cofrinho.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainRoutingTest {
    @Test
    fun helpDoesNotSelectTuiOrOpenDatabase(): Unit {
        val launch = LaunchArguments.parse(listOf("--help"), Path.of("/tmp/unused.sqlite"))
        assertTrue(launch.help)
        assertNull(launch.command)
    }

    @Test
    fun databaseOnlyLaunchSelectsTuiWithoutChangingArguments(): Unit {
        val launch = LaunchArguments.parse(listOf("--db", "/tmp/ledger.sqlite"), Path.of("/tmp/default.sqlite"))
        assertEquals(Path.of("/tmp/ledger.sqlite"), launch.database)
        assertNull(launch.command)
        assertTrue(launch.remaining.isEmpty())
    }

    @Test
    fun explicitCommandRemainsNonInteractive(): Unit {
        val launch = LaunchArguments.parse(listOf("--db", "/tmp/ledger.sqlite", "budget", "show"), Path.of("/tmp/default.sqlite"))
        assertEquals("budget", launch.command)
        assertEquals(listOf("budget", "show"), launch.remaining)
        assertFalse(launch.help)
    }

    @Test
    fun malformedLaunchOptionsFailBeforeTuiSelection(): Unit {
        assertFailsWith<IllegalStateException> { LaunchArguments.parse(listOf("--db"), Path.of("/tmp/default.sqlite")) }
        assertFailsWith<IllegalStateException> { LaunchArguments.parse(listOf("--unknown"), Path.of("/tmp/default.sqlite")) }
    }

    @Test
    fun terminalAvailabilityRejectsNonInteractiveExecution(): Unit {
        assertFalse(TerminalAvailability.isUsable(false, "xterm"))
        assertFalse(TerminalAvailability.isUsable(true, null))
        assertTrue(TerminalAvailability.isUsable(true, "xterm"))
    }
}
