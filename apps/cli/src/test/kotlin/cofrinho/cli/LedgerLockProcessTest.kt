package cofrinho.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LedgerLockProcessTest {
    @Test
    fun separateProcessHoldingLedgerLockIsRejected(): Unit {
        val directory = Files.createTempDirectory("cofrinho-lock-process-")
        val database = directory.resolve("ledger.sqlite")
        val ready = directory.resolve("ready")
        val process = startProbe(database, ready)
        try {
            waitForReady(process, ready)
            assertFailsWith<IllegalStateException> { LedgerLock.acquire(database) }
        } finally {
            stopProbe(process)
            deleteDirectory(directory)
        }
    }

    private fun startProbe(database: Path, ready: Path): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), "cofrinho.cli.LockProbeKt",
        database.toString(), ready.toString(),
    ).redirectErrorStream(true).start()

    private fun waitForReady(process: Process, ready: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && !Files.exists(ready)) {
            if (!process.isAlive) error("Lock probe exited before acquiring the ledger lock")
            Thread.sleep(25)
        }
        check(Files.exists(ready)) { "Lock probe did not acquire the ledger lock within 5 seconds" }
    }

    private fun stopProbe(process: Process) {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private fun deleteDirectory(directory: Path) {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
