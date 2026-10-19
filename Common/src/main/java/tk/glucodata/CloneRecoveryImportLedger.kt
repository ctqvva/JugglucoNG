package tk.glucodata

/** Keep the receipt in the same database transaction as the imported records. */
internal object CloneRecoveryImportLedger {
    suspend fun importOnce(
        manifest: CloneRecoveryManifest,
        transaction: suspend (suspend () -> Unit) -> Unit,
        readDigest: () -> String?,
        writeDigest: () -> Unit,
        importRecords: suspend () -> Unit,
    ) {
        transaction {
            val previous = readDigest()
            if (previous != null) {
                require(previous == manifest.sha256) { "Clone recovery job already committed a different package" }
            } else {
                importRecords()
                writeDigest()
            }
        }
    }
}
