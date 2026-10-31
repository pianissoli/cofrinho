package caixa.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate

class BackupManager(private val database: Path) {
    fun beforeOperation(kind: String) {
        copy("pre-$kind-${stamp()}.sqlite", pinned = false)
    }

    fun preserveConflict(contents: String, label: String) {
        val directory = directory()
        Files.createDirectories(directory)
        val name = "conflict-$label-${stamp()}.caixa"
        Files.writeString(directory.resolve(name), contents)
        Files.writeString(directory.resolve("$name.pinned"), "pinned")
    }

    fun preserveLocalConflict() {
        copy("conflict-local-${stamp()}.sqlite", pinned = true)
    }

    fun ordinaryBackups(): List<Path> {
        if (!Files.isDirectory(directory())) return emptyList()
        return Files.list(directory()).use { stream -> stream.filter { it.fileName.toString().endsWith(".sqlite") }.sorted().toList() }
    }

    fun afterFinancialEdit() {
        val directory = directory()
        Files.createDirectories(directory)
        val prefix = "daily-${LocalDate.now()}"
        if (Files.list(directory).use { stream -> stream.anyMatch { it.fileName.toString().startsWith(prefix) && it.fileName.toString().endsWith(".sqlite") } }) return
        copy("$prefix${stamp()}.sqlite", pinned = false)
        pruneOrdinary(directory)
    }

    private fun copy(name: String, pinned: Boolean) {
        val directory = directory()
        Files.createDirectories(directory)
        val target = directory.resolve(name)
        if (!Files.exists(database)) throw IllegalStateException("Cannot back up '$database'; expected an existing SQLite database")
        checkpointWal()
        val temporary = Files.createTempFile(directory, ".backup-", ".tmp")
        try {
            Files.copy(database, temporary, StandardCopyOption.REPLACE_EXISTING)
            moveIntoPlace(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        if (pinned) Files.writeString(directory.resolve("$name.pinned"), "pinned")
    }

    private fun pruneOrdinary(directory: Path) {
        val files = Files.list(directory).use { stream -> stream.filter { it.fileName.toString().endsWith(".sqlite") && !Files.exists(it.resolveSibling("${it.fileName}.pinned")) }.sorted().toList() }
        files.dropLast(10).forEach { file -> if (!Files.exists(file.resolveSibling("${file.fileName}.pinned"))) Files.deleteIfExists(file) }
    }

    private fun moveIntoPlace(temporary: Path, target: Path) {
        runCatching { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun checkpointWal() {
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(FULL)") }
        }
    }

    private fun directory(): Path = database.toAbsolutePath().parent.resolve("backups")
    private fun stamp(): String = Instant.now().toString().replace(":", "-")
}
