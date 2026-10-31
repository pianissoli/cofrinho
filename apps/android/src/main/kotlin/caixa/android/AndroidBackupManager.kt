package caixa.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import caixa.transfer.PortableLedger
import caixa.transfer.PortableLedgerCodec
import java.io.File
import java.time.LocalDate
import java.time.Instant

class AndroidBackupManager(context: Context) {
    private val database = context.getDatabasePath("caixa.sqlite")
    private val directory = File(context.filesDir, "backups")

    fun beforeOperation(kind: String) {
        copy("pre-$kind-${stamp()}.sqlite")
    }

    fun preserveConflict(snapshot: PortableLedger, digest: String) {
        directory.mkdirs()
        val name = "conflict-incoming-${digest.take(16)}-${stamp()}.caixa"
        File(directory, name).writeText(PortableLedgerCodec.encode(snapshot))
        File(directory, "$name.pinned").writeText("pinned")
    }

    fun preserveLocalConflict() {
        val name = "conflict-local-${stamp()}.sqlite"
        copy(name)
        File(directory, "$name.pinned").writeText("pinned")
    }

    fun afterFinancialEdit() {
        if (!database.exists()) return
        directory.mkdirs()
        val prefix = "daily-${LocalDate.now()}"
        if (directory.listFiles()?.any { it.name.startsWith(prefix) } == true) return
        copy("$prefix-${stamp()}.sqlite")
        pruneOrdinary()
    }

    private fun copy(name: String) {
        if (!database.exists()) return
        directory.mkdirs()
        checkpointWal()
        val temporary = File(directory, ".backup-$name.tmp")
        database.copyTo(temporary, overwrite = true)
        if (!temporary.renameTo(File(directory, name))) error("Cannot finalize backup '$name'; expected writable app-private storage")
        temporary.delete()
    }

    private fun pruneOrdinary() {
        val backups = directory.listFiles()?.filter { it.name.endsWith(".sqlite") && !File(directory, "${it.name}.pinned").exists() }?.sortedBy { it.name }.orEmpty()
        backups.dropLast(10).forEach { it.delete() }
    }

    private fun checkpointWal() {
        val connection = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            connection.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        } finally {
            connection.close()
        }
    }

    private fun stamp(): String = Instant.now().toString().replace(':', '-')
}
