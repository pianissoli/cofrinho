package cofrinho.cli

import java.nio.file.Files
import java.nio.file.Path

fun main(arguments: Array<String>) {
    val database = Path.of(arguments.getOrNull(0) ?: error("Missing database path"))
    val ready = Path.of(arguments.getOrNull(1) ?: error("Missing ready path"))
    LedgerLock.acquire(database).use {
        Files.writeString(ready, "locked")
        Thread.sleep(10_000)
    }
}
