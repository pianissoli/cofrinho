package cofrinho.cli

import cofrinho.cli.tui.CofrinhoTui
import java.nio.file.Path

fun main(args: Array<String>) {
    try {
        val launch = LaunchArguments.parse(args.toList(), DefaultPaths.database)
        if (launch.help) return printHelp()
        if (launch.command == null) {
            check(TerminalAvailability.isUsable()) {
                "interactive TUI requires a usable terminal; use --help or an explicit command for noninteractive use"
            }
            return CofrinhoTui(launch.database).run()
        }
        CofrinhoCli(launch.database).run(launch.remaining)
    } catch (error: Exception) {
        System.err.println("cofrinho: ${error.message ?: "operation failed"}")
        kotlin.system.exitProcess(1)
    }
}

internal data class LaunchArguments(
    val database: Path,
    val command: String?,
    val remaining: List<String>,
    val help: Boolean,
) {
    companion object {
        fun parse(arguments: List<String>, defaultDatabase: Path): LaunchArguments {
            if (arguments.any { it == "--help" || it == "-h" }) return LaunchArguments(defaultDatabase, null, arguments, true)
            val values = arguments.toMutableList()
            val database = if (values.firstOrNull() == "--db") {
                values.removeAt(0)
                Path.of(values.removeFirstOrNull() ?: error("Missing value after '--db'; expected a database path"))
            } else defaultDatabase
            val command = values.firstOrNull()?.takeUnless { it.startsWith("-") }
            if (command == null && values.isNotEmpty()) error("Unknown option '${values.first()}'; expected --db PATH, --help, or a command")
            return LaunchArguments(database, command, values, false)
        }
    }
}

internal object TerminalAvailability {
    fun isUsable(): Boolean = isUsable(System.console() != null, System.getenv("TERM"))

    fun isUsable(consoleAvailable: Boolean, terminalName: String?): Boolean = consoleAvailable && !terminalName.isNullOrBlank()
}

object DefaultPaths {
    val database: Path = Path.of(System.getProperty("user.home"), ".local", "share", "cofrinho", "cofrinho.sqlite")
}
