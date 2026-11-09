package cofrinho.storage

/**
 * Documents the SQLDelight database identity used by all first-release clients.
 * Platform modules provide a driver, create `CofrinhoDatabase.Schema`, and use
 * `SqlDelightLedgerStore` provides the shared transactional path; platform
 * wrappers may add driver-specific migration and backup handling.
 */
object LedgerSchema {
    const val databaseName: String = "CofrinhoDatabase"
    const val schemaVersion: Int = 3
}
