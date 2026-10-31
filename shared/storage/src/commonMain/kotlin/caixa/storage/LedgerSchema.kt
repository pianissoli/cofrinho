package caixa.storage

/**
 * Documents the SQLDelight database identity used by all first-release clients.
 * Platform modules provide a driver, create `CaixaDatabase.Schema`, and use
 * `SqlDelightLedgerStore` provides the shared transactional path; platform
 * wrappers may add driver-specific migration and backup handling.
 */
object LedgerSchema {
    const val databaseName: String = "CaixaDatabase"
    const val schemaVersion: Int = 2
}
