package cofrinho.cli

import cofrinho.finance.Ledger
import cofrinho.finance.TransferState
import cofrinho.transfer.PortableLedger
import cofrinho.transfer.PortableLedgerCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CofrinhoCliTransferTest {
    /** Uses the local revision when accepting a returned ledger. Example: importAdvancesTheInspectedLocalRevision(). */
    @Test
    fun importAdvancesTheInspectedLocalRevision(): Unit = CliTransferFixture().use { fixture ->
        val incoming: PortableLedger = PortableLedgerCodec.snapshot(fixture.store.read(), "incoming", "local", "2026-10-30T12:00:00Z")
        val source: Path = fixture.directory.resolve("incoming.cofrinho")
        Files.writeString(source, PortableLedgerCodec.encode(incoming))
        fixture.cli.run(listOf("transfer", "import", "--file", source.toString()))
        val checkpoint: TransferState = fixture.store.readTransferState()
        assertEquals(8L, fixture.store.read().revision)
        assertEquals(8L, checkpoint.revision)
        assertEquals("incoming", checkpoint.checkpointId)
        assertFalse(checkpoint.handedOff)
    }

    /** Keeps retry exports byte-identical to the staged handoff. Example: repeatedExportReusesStagedPayload(). */
    @Test
    fun repeatedExportReusesStagedPayload(): Unit = CliTransferFixture().use { fixture ->
        val first: Path = fixture.directory.resolve("first.cofrinho")
        val retry: Path = fixture.directory.resolve("retry.cofrinho")
        fixture.cli.run(listOf("transfer", "export", "--file", first.toString()))
        val checkpoint: TransferState = fixture.store.readTransferState()
        fixture.cli.run(listOf("transfer", "export", "--file", retry.toString()))
        assertEquals(Files.readString(first), Files.readString(retry))
        assertEquals(Files.readString(first), checkpoint.stagedPayload)
        assertEquals(checkpoint, fixture.store.readTransferState())
        assertTrue(checkpoint.handedOff)
    }
}

private class CliTransferFixture : AutoCloseable {
    val directory: Path = Files.createTempDirectory("cofrinho-cli-transfer-")
    private val database: Path = directory.resolve("ledger.sqlite")
    val store: JdbcLedgerStore = JdbcLedgerStore(database)
    val cli: CofrinhoCli = CofrinhoCli(database)

    init {
        store.replace(Ledger("ledger", "BRL", 2, revision = 7L))
        store.writeTransferState(TransferState(checkpointId = "local", payloadDigest = "original-digest", revision = 7L))
    }

    override fun close(): Unit {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
        }
    }
}
