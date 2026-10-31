package caixa.cli

fun main(args: Array<String>) {
    try {
        CaixaCli(DefaultPaths.database).run(args.toList())
    } catch (error: Exception) {
        System.err.println("caixa: ${error.message ?: "operation failed"}")
        kotlin.system.exitProcess(1)
    }
}
