package cofrinho.cli

import cofrinho.finance.BudgetSummary
import cofrinho.finance.Expense
import cofrinho.finance.IdentifierSource
import cofrinho.finance.Ledger
import cofrinho.finance.LedgerClock
import cofrinho.finance.LedgerService
import cofrinho.finance.PaymentMethod
import cofrinho.finance.TransferState
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

interface DesktopBackupPort {
    fun beforeOperation(kind: String)
    fun afterFinancialEdit()
    fun preserveConflict(contents: String, label: String)
    fun preserveLocalConflict()
    fun ordinaryBackups(): List<Path>
}

data class SavedEdit<T>(val value: T, val backupWarning: String? = null)

class DesktopLedgerSession private constructor(
    val databasePath: Path,
    private val lock: LedgerLock,
    val store: JdbcLedgerStore,
    val backups: DesktopBackupPort,
    val clock: LedgerClock,
    identifiers: IdentifierSource,
) : AutoCloseable {
    val service: LedgerService = LedgerService(store, clock, identifiers)

    fun summary(month: String): BudgetSummary = service.summary(month)

    fun monthExpenses(month: String): List<Expense> = store.read().expenses.filter { it.occurredOn.startsWith(month) }.sortedWith(compareBy<Expense> { it.occurredOn }.thenBy { it.id })

    fun addExpense(amountMinor: Long, date: String?, method: PaymentMethod, description: String?): SavedEdit<Expense> {
        requireEditable()
        val saved = service.addExpense(amountMinor, date, method, description)
        return SavedEdit(saved, backupWarning())
    }

    fun initialize(ledger: Ledger) {
        store.replace(ledger)
        store.writeTransferState(TransferState(revision = ledger.revision))
    }

    fun requireEditable() {
        if (store.readTransferState().handedOff) throw IllegalStateException("Ledger is handed off; import the returned file or run transfer resume after confirming the warning")
    }

    private fun backupWarning(): String? = runCatching { backups.afterFinancialEdit() }.exceptionOrNull()?.message

    override fun close() = lock.close()

    companion object {
        fun open(
            databasePath: Path,
            clock: LedgerClock = DesktopClock,
            identifiers: IdentifierSource = DesktopIdentifiers,
            backups: DesktopBackupPort = BackupManager(databasePath),
        ): DesktopLedgerSession {
            val normalized = databasePath.toAbsolutePath().normalize()
            Files.createDirectories(normalized.parent)
            val lock = LedgerLock.acquire(normalized)
            return runCatching { DesktopLedgerSession(normalized, lock, JdbcLedgerStore(normalized), backups, clock, identifiers) }
                .getOrElse { error -> lock.close(); throw error }
        }
    }
}

class LedgerLock private constructor(private val channel: FileChannel, private val held: FileLock) : AutoCloseable {
    override fun close() {
        runCatching { held.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(database: Path): LedgerLock {
            val normalized = canonical(database)
            val lockPath = normalized.resolveSibling("${normalized.fileName}.lock")
            val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            return try {
                val held = channel.tryLock() ?: throw IllegalStateException("Ledger '$normalized' is already open; expected one cooperating desktop session")
                LedgerLock(channel, held)
            } catch (error: OverlappingFileLockException) {
                channel.close()
                throw IllegalStateException("Ledger '$normalized' is already open; expected one cooperating desktop session", error)
            } catch (error: Exception) {
                channel.close()
                throw error
            }
        }

        private fun canonical(database: Path): Path {
            val absolute = database.toAbsolutePath().normalize()
            if (Files.exists(absolute)) return absolute.toRealPath()
            return absolute.parent.toRealPath().resolve(absolute.fileName)
        }
    }
}

object DesktopClock : LedgerClock {
    override fun today(): String = LocalDate.now(ZoneId.systemDefault()).toString()
    override fun nowUtc(): String = Instant.now().toString()
}

object DesktopIdentifiers : IdentifierSource {
    override fun next(): String = UUID.randomUUID().toString()
}
