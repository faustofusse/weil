package ar.fausto.weil

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Directory holding this user's database files inside the iOS app sandbox,
 * isolated per user id so switching accounts never mixes local state.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun databaseDirectory(userId: String): String {
    val dirs = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true,
    )
    val documents = dirs.firstOrNull() as? String ?: ""
    val dir = "$documents/databases/$userId"
    NSFileManager.defaultManager.createDirectoryAtPath(
        dir,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return dir
}

/**
 * Path of the Turso sync-engine database, mirroring Android's
 * `databases/{userId}/turso.db`. Replicas written by the retired libsql
 * embedded-replica engine (`local.db*`, same directory) are deleted on first
 * open by [IosTursoDatabase].
 */
fun tursoDatabasePath(userId: String): String = "${databaseDirectory(userId)}/turso.db"
