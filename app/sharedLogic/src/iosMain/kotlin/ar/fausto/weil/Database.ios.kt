package ar.fausto.weil

import cnames.structs.libsql_error_t
import kotlinx.cinterop.*
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import swiftPMImport.Weil.app.app.sharedLogic.*

/**
 * Default path for the local embedded-replica database inside the iOS app sandbox,
 * isolated per user id so switching accounts never mixes replicas.
 */
@OptIn(ExperimentalForeignApi::class)
fun defaultDatabasePath(userId: String): String {
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
    return "$dir/local.db"
}

/**
 * libsql embedded-replica database backed by the native C API exposed by the
 * libsql-swift Swift package.
 */
@OptIn(ExperimentalForeignApi::class)
class IOSDatabase(
    path: String,
    url: String,
    authToken: String,
) : Database {

    private var db: CValue<libsql_database_t>? = null
    private var connection: CValue<libsql_connection_t>? = null

    init {
        memScoped {
            val desc = cValue<libsql_database_desc_t> {
                this.path = path.cstr.ptr
                this.url = url.cstr.ptr
                this.auth_token = authToken.cstr.ptr
                this.sync_interval = 2uL
                this.disable_read_your_writes = false
                this.webpki = false
                this.synced = false
                this.disable_safety_assert = false
            }

            val opened = libsql_database_init(desc)
            errIf(opened)
            db = opened

            val conn = libsql_database_connect(opened)
            errIf(conn)
            connection = conn

            val setup = SCHEMA_SQL.cstr.ptr
            val batch = libsql_connection_batch(conn, setup)
            errIf(batch)
            migrateSchema()
        }
    }

    override fun sync() {
        val db = db ?: return
        val result = libsql_database_sync(db)
        errIf(result)
    }

    override fun execute(sql: String, params: Map<String, Any>?) {
        val connection = connection ?: return
        memScoped {
            val stmt = libsql_connection_prepare(connection, sql.cstr.ptr)
            errIf(stmt)
            try {
                params?.forEach { (name, value) ->
                    val bind = libsql_statement_bind_named(stmt, name.cstr.ptr, bindValue(value))
                    errIf(bind)
                }
                errIf(libsql_statement_execute(stmt))
            } finally {
                libsql_statement_deinit(stmt)
            }
        }
    }

    override fun <T> query(
        sql: String,
        params: Map<String, Any>?,
        block: (Sequence<Row>) -> T,
    ): T {
        val connection = connection ?: return block(emptySequence())

        return memScoped {
            val stmt = libsql_connection_prepare(connection, sql.cstr.ptr)
            errIf(stmt)

            params?.forEach { (name, value) ->
                val v = bindValue(value)
                val bind = libsql_statement_bind_named(stmt, name.cstr.ptr, v)
                errIf(bind)
            }

            val rows = libsql_statement_query(stmt)
            errIf(rows)

            val seq = sequence {
                while (true) {
                    val row = libsql_rows_next(rows)
                    errIf(row)
                    if (libsql_row_empty(row)) break

                    val length = libsql_row_length(row)
                    val columns = List(length) { index ->
                        val result = libsql_row_value(row, index)
                        errIf(result)
                        result.toKotlinValue()
                    }
                    yield(columns)
                }
            }

            try {
                block(seq)
            } finally {
                libsql_rows_deinit(rows)
                libsql_statement_deinit(stmt)
            }
        }
    }

    override fun close() {
        connection?.let { libsql_connection_deinit(it) }
        db?.let { libsql_database_deinit(it) }
        connection = null
        db = null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun bindValue(value: Any?): CValue<libsql_value_t> =
    when (value) {
        is Long -> libsql_integer(value)
        is Int -> libsql_integer(value.toLong())
        is Double -> libsql_real(value)
        is Float -> libsql_real(value.toDouble())
        is String -> memScoped {
            libsql_text(value.cstr.ptr, value.length.toULong())
        }
        is ByteArray -> value.usePinned { pinned ->
            libsql_blob(pinned.addressOf(0).reinterpret<UByteVar>(), value.size.toULong())
        }
        null -> libsql_null()
        else -> throw IllegalArgumentException("Unsupported libsql parameter type: ${value::class}")
    }

@OptIn(ExperimentalForeignApi::class)
private fun CValue<libsql_result_value_t>.toKotlinValue(): Any? = useContents {
    // All struct field access must happen inside this scope: the pointed
    // memory is only valid while useContents is running.
    when (ok.type) {
        LIBSQL_TYPE_INTEGER -> ok.value.integer
        LIBSQL_TYPE_REAL -> ok.value.real
        LIBSQL_TYPE_TEXT -> {
            val slice = ok.value.text
            val bytes = slice.ptr!!.reinterpret<UByteVar>().readBytes(slice.len.toInt())
            libsql_slice_deinit(slice.asCValue())
            // Text slices are NUL-terminated and the length includes the
            // terminator — stop at it, like the Swift String(cString:) wrapper.
            bytes.decodeToString().substringBefore('\u0000')
        }
        LIBSQL_TYPE_BLOB -> {
            val slice = ok.value.blob
            val bytes = slice.ptr!!.reinterpret<UByteVar>().readBytes(slice.len.toInt())
            libsql_slice_deinit(slice.asCValue())
            bytes
        }
        LIBSQL_TYPE_NULL -> null
        else -> null
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun libsql_slice_t.asCValue(): CValue<libsql_slice_t> =
    cValue {
        ptr = this@asCValue.ptr
        len = this@asCValue.len
    }

@OptIn(ExperimentalForeignApi::class)
private fun throwLastError(err: CPointer<libsql_error_t>) {
    val message = libsql_error_message(err)!!.toKString()
    libsql_error_deinit(err)
    throw IllegalStateException(message)
}

@OptIn(ExperimentalForeignApi::class)
private fun errIf(value: CValue<libsql_database_t>) {
    value.useContents { err }?.let(::throwLastError)
}

@OptIn(ExperimentalForeignApi::class)
private fun errIf(value: CValue<libsql_connection_t>) {
    value.useContents { err }?.let(::throwLastError)
}

@OptIn(ExperimentalForeignApi::class)
private fun errIf(value: CValue<libsql_statement_t>) {
    value.useContents { err }?.let(::throwLastError)
}

@OptIn(ExperimentalForeignApi::class)
private fun errIf(value: CValue<libsql_rows_t>) {
    value.useContents { err }?.let(::throwLastError)
}

@OptIn(ExperimentalForeignApi::class)
private fun errIf(value: CValue<libsql_row_t>) {
    value.useContents { err }?.let(::throwLastError)
}

@OptIn(ExperimentalForeignApi::class)
private fun errIf(value: CValue<libsql_result_value_t>) {
    value.useContents { err }?.let(::throwLastError)
}

@OptIn(ExperimentalForeignApi::class)
private fun errIf(value: CValue<libsql_sync_t>) {
    value.useContents { err }?.let(::throwLastError)
}

@OptIn(ExperimentalForeignApi::class)
private fun errIf(value: CValue<libsql_batch_t>) {
    value.useContents { err }?.let(::throwLastError)
}

@OptIn(ExperimentalForeignApi::class)
private fun errIf(value: CValue<libsql_execute_t>) {
    value.useContents { err }?.let(::throwLastError)
}

@OptIn(ExperimentalForeignApi::class)
private fun errIf(value: CValue<libsql_bind_t>) {
    value.useContents { err }?.let(::throwLastError)
}
