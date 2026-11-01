package cofrinho.cli

import java.nio.file.Path

fun main(args: Array<String>) {
    try {
        CofrinhoCli(DefaultPaths.database).run(args.toList())
    } catch (error: Exception) {
        System.err.println("cofrinho: ${error.message ?: "operation failed"}")
        kotlin.system.exitProcess(1)
    }
}

private object DefaultPaths {
    val database: Path = Path.of(System.getProperty("user.home"), ".local", "share", "cofrinho", "cofrinho.sqlite")
}
