package ar.fausto.weil

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import turso.*

private const val TURSO_LOG_PREFIX = "turso: "


/**
 * iOS implementation of [Database] on the new Turso (SQLite rewrite) sync
 * engine — the same `turso_sync_sdk_kit` C ABI Android drives over JNA, here
 * through Kotlin/Native cinterop against a Rust staticlib.
 *
 * Mirrors [AndroidDatabase]: sync IO is caller-driven, so operations returned
 * by create/connect/push/wait are `resume()`d until DONE and, on TURSO_IO, the
 * caller drains the IO queue (HTTP via NSURLSession, atomic file read/write for
 * the sync metadata) before `io_step_callbacks()`.
 *
 * All calls must run on [DbDispatcher] (16 MB stack) — the Rust parser
 * overflows ordinary coroutine stacks.
 */
@OptIn(ExperimentalForeignApi::class)
class IosTursoDatabase(
    private val path: String,
    private val url: String,
    private val authToken: String,
) : Database {

    private var db: CPointer<turso_sync_database_t>? = null
    private var connection: CPointer<turso_connection_t>? = null
    private var opened = false

    /** Last HTTP status >= 400 seen by the sync IO pump, for auth diagnostics. */
    private var lastHttpFailureStatus: Int = 0

    // ---- lifecycle ---------------------------------------------------------------

    private fun ensureOpen() {
        if (opened) return
        deleteStaleLibsqlReplica()
        NSFileManager.defaultManager.createDirectoryAtPath(
            path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )

        memScoped {
            val dbConfig = alloc<turso_database_config_t>()
            dbConfig.async_io = 0u
            dbConfig.path = path.cstr.ptr

            val syncConfig = alloc<turso_sync_database_config_t>()
            syncConfig.path = path.cstr.ptr
            syncConfig.remote_url = url.cstr.ptr
            syncConfig.client_name = "weil".cstr.ptr
            syncConfig.long_poll_timeout_ms = 0
            syncConfig.bootstrap_if_empty = true

            val err = alloc<CPointerVar<ByteVar>>()
            val dbRef = alloc<CPointerVar<turso_sync_database_t>>()
            check(turso_sync_database_new(dbConfig.ptr, syncConfig.ptr, dbRef.ptr, err.ptr), err, "sync_database_new")
            val syncDb = dbRef.value
                ?: throw TursoException("${TURSO_LOG_PREFIX}sync_database_new returned null database")

            try {
                // Opens the synced database, or bootstraps it from the remote
                // when the local file is empty (bootstrap_if_empty = true).
                drainVoidOperation(syncDb, "create") { opRef, errorRef ->
                    turso_sync_database_create(syncDb, opRef, errorRef)
                }

                val conn = drainOperation(syncDb, "connect", { opRef, errorRef ->
                    turso_sync_database_connect(syncDb, opRef, errorRef)
                }) { op, errorRef ->
                    memScoped {
                        val connRef = alloc<CPointerVar<turso_connection_t>>()
                        check(
                            turso_sync_operation_result_extract_connection(op, connRef.ptr),
                            errorRef,
                            "extract_connection",
                        )
                        val extracted = connRef.value
                            ?: throw TursoException("${TURSO_LOG_PREFIX}connect returned null connection")
                        turso_sync_operation_deinit(op)
                        extracted
                    }
                }

                db = syncDb
                connection = conn
                opened = true

                applySchemaIfNeeded()
            } catch (t: Throwable) {
                connection?.let { turso_connection_close(it, null) }
                turso_sync_database_deinit(syncDb)
                db = null
                connection = null
                opened = false
                throw t
            }
        }
    }

    /** Removes on-disk replicas written by the old libsql engine (same parent dir). */
    private fun deleteStaleLibsqlReplica() {
        val dir = path.substringBeforeLast('/')
        val fm = NSFileManager.defaultManager
        @Suppress("UNCHECKED_CAST")
        val entries = fm.contentsOfDirectoryAtPath(dir, null) as? List<String> ?: return
        entries.filter { it.startsWith("local.db") }.forEach { fm.removeItemAtPath("$dir/$it", null) }
    }

    // ---- error handling ----------------------------------------------------------

        private fun errorText(err: CPointerVar<ByteVar>): String =
        err.value?.toKString()?.ifEmpty { "error code <empty>" } ?: "error code <null>"

    private fun check(status: UInt, err: CPointerVar<ByteVar>, what: String) {
        if (status != TURSO_OK) throw TursoException("$TURSO_LOG_PREFIX [$what] ${errorText(err)}")
    }

    private fun httpHint(msg: String): String =
        if (lastHttpFailureStatus > 0) "$msg [HTTP $lastHttpFailureStatus]" else msg

    // ---- async operation driver ---------------------------------------------------

    private fun <T> drainOperation(
        syncDb: CPointer<turso_sync_database_t>,
        what: String,
        start: (CValuesRef<CPointerVar<turso_sync_operation_t>>, CValuesRef<CPointerVar<ByteVar>>) -> UInt,
        extract: (CPointer<turso_sync_operation_t>, CPointerVar<ByteVar>) -> T,
    ): T = memScoped {
        val err = alloc<CPointerVar<ByteVar>>()
        val opRef = alloc<CPointerVar<turso_sync_operation_t>>()
        check(start(opRef.ptr, err.ptr), err, what)
        val op = opRef.value ?: throw TursoException("${TURSO_LOG_PREFIX}$what returned null operation")
        try {
            while (true) {
                when (val status = turso_sync_operation_resume(op, err.ptr)) {
                    TURSO_DONE -> return@memScoped extract(op, err)
                    TURSO_IO -> {
                        pumpIoQueue(syncDb)
                        check(turso_sync_database_io_step_callbacks(syncDb, err.ptr), err, "io_step_callbacks")
                    }
                    else -> throw TursoException(
                        httpHint("$TURSO_LOG_PREFIX [$what] ${errorText(err)}") + " (code $status)",
                    )
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } catch (t: Throwable) {
            turso_sync_operation_deinit(op)
            throw t
        }
    }

    private fun drainVoidOperation(
        syncDb: CPointer<turso_sync_database_t>,
        what: String,
        start: (CValuesRef<CPointerVar<turso_sync_operation_t>>, CValuesRef<CPointerVar<ByteVar>>) -> UInt,
    ) {
        drainOperation(syncDb, what, start) { op, _ -> turso_sync_operation_deinit(op) }
    }

    // ---- IO pump -------------------------------------------------------------------

    private fun pumpIoQueue(syncDb: CPointer<turso_sync_database_t>) = memScoped {
        val err = alloc<CPointerVar<ByteVar>>()
        val itemRef = alloc<CPointerVar<turso_sync_io_item_t>>()
        while (turso_sync_database_io_take_item(syncDb, itemRef.ptr, err.ptr) == TURSO_OK) {
            val item = itemRef.value ?: break
            try {
                processIoItem(item)
            } catch (t: Throwable) {
                memScoped {
                    val msg = (t.message ?: t.toString()).encodeToByteArray()
                    turso_sync_database_io_poison(item, slice(msg))
                }
            } finally {
                turso_sync_database_io_item_deinit(item)
            }
            itemRef.value = null
        }
    }

    private fun processIoItem(item: CPointer<turso_sync_io_item_t>) {
        when (turso_sync_database_io_request_kind(item)) {
            TURSO_SYNC_IO_HTTP -> processHttp(item)
            TURSO_SYNC_IO_FULL_READ -> processFullRead(item)
            TURSO_SYNC_IO_FULL_WRITE -> processFullWrite(item)
            else -> turso_sync_database_io_done(item)
        }
    }

    private fun processHttp(item: CPointer<turso_sync_io_item_t>) = memScoped {
        val request = alloc<turso_sync_io_http_request_t>()
        val err = alloc<CPointerVar<ByteVar>>()
        check(turso_sync_database_io_request_http(item, request.ptr), err, "io_request_http")

        val baseUrl = normalizeUrl(request.url.asString() ?: url)
        val fullUrl = joinUrl(baseUrl, request.path.asString() ?: "")
        val method = (request.method.asString() ?: "GET").uppercase()
        val body = request.body.asBytes()

        val nsRequest = NSMutableURLRequest(uRL = NSURL(string = fullUrl))
        nsRequest.setHTTPMethod(method)
        for (i in 0 until request.headers) {
            val header = alloc<turso_sync_io_http_header_t>()
            check(
                turso_sync_database_io_request_http_header(item, i.convert<ULong>(), header.ptr),
                err,
                "io_request_http_header",
            )
            val key = header.key.asString() ?: continue
            val value = header.value.asString() ?: continue
            // Keep our Authorization authoritative if the engine ever sets one.
            if (key.lowercase() == "authorization") continue
            nsRequest.setValue(value, forHTTPHeaderField = key)
        }
        if (authToken.isNotEmpty()) {
            nsRequest.setValue("Bearer $authToken", forHTTPHeaderField = "Authorization")
        }
        if (body != null && body.isNotEmpty() && method != "GET" && method != "HEAD") {
            nsRequest.setHTTPBody(body.toNSData())
        }

        val result = nsRequest.sendBlocking()
        if (result.error != null) throw TursoException("${TURSO_LOG_PREFIX}http $method $fullUrl: ${result.error}")
        if (result.status >= 400) lastHttpFailureStatus = result.status

        turso_sync_database_io_status(item, result.status)
        val data = result.body
        if (data != null && data.isNotEmpty()) pushBuffer(item, data)
        turso_sync_database_io_done(item)
    }

    private fun processFullRead(item: CPointer<turso_sync_io_item_t>) = memScoped {
        val request = alloc<turso_sync_io_full_read_request_t>()
        val err = alloc<CPointerVar<ByteVar>>()
        check(turso_sync_database_io_request_full_read(item, request.ptr), err, "io_request_full_read")
        val target = request.path.asString() ?: ""
        val data = NSData.dataWithContentsOfFile(target)?.toByteArray() ?: ByteArray(0)
        if (data.isNotEmpty()) pushBuffer(item, data)
        turso_sync_database_io_done(item)
    }

    private fun processFullWrite(item: CPointer<turso_sync_io_item_t>) = memScoped {
        val request = alloc<turso_sync_io_full_write_request_t>()
        val err = alloc<CPointerVar<ByteVar>>()
        check(turso_sync_database_io_request_full_write(item, request.ptr), err, "io_request_full_write")
        val target = request.path.asString() ?: ""
        val content = (request.content.asBytes() ?: ByteArray(0)).toNSData()
        // NSData's atomically flag is the temp-file + rename the engine expects.
        content.writeToFile(target, atomically = true)
        turso_sync_database_io_done(item)
    }

    private fun pushBuffer(item: CPointer<turso_sync_io_item_t>, data: ByteArray) = memScoped {
        turso_sync_database_io_push_buffer(item, slice(data))
    }

    /** libsql:// and turso:// URLs are https:// over the wire. */
    private fun normalizeUrl(raw: String): String = raw
        .replace(Regex("^libsql://"), "https://")
        .replace(Regex("^turso://"), "https://")

    private fun joinUrl(base: String, path: String): String {
        if (path.isEmpty()) return base
        val pathPart = if (path.startsWith("/")) path else "/$path"
        return base.removeSuffix("/") + pathPart
    }

    // ---- statements ------------------------------------------------------------------

    private fun requireConnection(): CPointer<turso_connection_t> =
        connection ?: throw TursoException("${TURSO_LOG_PREFIX}connection is not open")

    private fun prepareSingle(sql: String): CPointer<turso_statement_t> = memScoped {
        val err = alloc<CPointerVar<ByteVar>>()
        val stmtRef = alloc<CPointerVar<turso_statement_t>>()
        check(
            turso_connection_prepare_single(requireConnection(), sql.cstr.ptr, stmtRef.ptr, err.ptr),
            err,
            "prepare",
        )
        stmtRef.value
            ?: throw TursoException("${TURSO_LOG_PREFIX}prepare returned null statement for '$sql'")
    }

    private fun stepNext(stmt: CPointer<turso_statement_t>): Boolean = memScoped {
        val err = alloc<CPointerVar<ByteVar>>()
        while (true) {
            when (val status = turso_statement_step(stmt, err.ptr)) {
                TURSO_DONE -> return@memScoped false
                TURSO_ROW -> return@memScoped true
                TURSO_IO -> check(turso_statement_run_io(stmt, err.ptr), err, "statement_run_io")
                else -> throw TursoException("$TURSO_LOG_PREFIX step failed: ${errorText(err)} (code $status)")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    private fun executeWithIo(stmt: CPointer<turso_statement_t>) = memScoped {
        val err = alloc<CPointerVar<ByteVar>>()
        val changes = alloc<ULongVar>()
        while (true) {
            when (val status = turso_statement_execute(stmt, changes.ptr, err.ptr)) {
                TURSO_DONE, TURSO_ROW -> return@memScoped
                TURSO_IO -> check(turso_statement_run_io(stmt, err.ptr), err, "statement_run_io")
                else -> throw TursoException("$TURSO_LOG_PREFIX execute failed: ${errorText(err)} (code $status)")
            }
        }
    }

    /** Binds named params (`:name` keys) via turso_statement_named_position. */
    private fun bind(stmt: CPointer<turso_statement_t>, params: Map<String, Any>?) = memScoped {
        for ((name, value) in params ?: emptyMap()) {
            // 1-indexed position; <= 0 means the name wasn't found — silently
            // unbound, identical to the libsql missing-colon gotcha.
            val pos = turso_statement_named_position(stmt, name.cstr.ptr)
            if (pos <= 0) continue
            val position: ULong = pos.convert()
            val err = alloc<CPointerVar<ByteVar>>()
            when (value) {
                is Long -> check(turso_statement_bind_positional_int(stmt, position, value), err, "bind_int")
                is Int -> check(turso_statement_bind_positional_int(stmt, position, value.toLong()), err, "bind_int")
                is Short -> check(turso_statement_bind_positional_int(stmt, position, value.toLong()), err, "bind_int")
                is Byte -> check(turso_statement_bind_positional_int(stmt, position, value.toLong()), err, "bind_int")
                is Boolean -> check(
                    turso_statement_bind_positional_int(stmt, position, if (value) 1L else 0L),
                    err,
                    "bind_int",
                )
                is Double -> check(turso_statement_bind_positional_double(stmt, position, value), err, "bind_double")
                is Float -> check(
                    turso_statement_bind_positional_double(stmt, position, value.toDouble()),
                    err,
                    "bind_double",
                )
                is String -> {
                    val bytes = value.encodeToByteArray()
                    val buf = allocArray<ByteVar>(bytes.size.coerceAtLeast(1))
                    bytes.forEachIndexed { i, b -> buf[i] = b }
                    check(
                        turso_statement_bind_positional_text(stmt, position, buf.reinterpret(), bytes.size.convert()),
                        err,
                        "bind_text",
                    )
                }
                is ByteArray -> {
                    val buf = allocArray<ByteVar>(value.size.coerceAtLeast(1))
                    value.forEachIndexed { i, b -> buf[i] = b }
                    check(
                        turso_statement_bind_positional_blob(stmt, position, buf.reinterpret(), value.size.convert()),
                        err,
                        "bind_blob",
                    )
                }
                else -> throw TursoException(
                    "$TURSO_LOG_PREFIX unsupported parameter type for '$name': ${value::class.simpleName}",
                )
            }
        }
    }

    /** Copies the current row out — native pointers are valid only until the next step. */
    private fun readRow(stmt: CPointer<turso_statement_t>): Row {
        val count = turso_statement_column_count(stmt).toInt()
        val row = ArrayList<Any?>(count)
        for (i in 0 until count) {
            val index: ULong = i.convert()
            row.add(
                when (turso_statement_row_value_kind(stmt, index)) {
                    TURSO_TYPE_INTEGER -> turso_statement_row_value_int(stmt, index)
                    TURSO_TYPE_REAL -> turso_statement_row_value_double(stmt, index)
                    TURSO_TYPE_TEXT -> turso_statement_row_value_bytes_ptr(stmt, index)?.let { p ->
                        val len = turso_statement_row_value_bytes_count(stmt, index).toInt()
                        p.readBytes(len).decodeToString()
                    }
                    TURSO_TYPE_BLOB -> turso_statement_row_value_bytes_ptr(stmt, index)?.let { p ->
                        val len = turso_statement_row_value_bytes_count(stmt, index).toInt()
                        p.readBytes(len)
                    }
                    else -> null
                },
            )
        }
        return row
    }

    // ---- Database interface ------------------------------------------------------------

    override fun sync() {
        val syncDb = requireOpenDb()

        // 1) Push local changes (logical mutations).
        drainVoidOperation(syncDb, "push_changes") { opRef, errorRef ->
            turso_sync_database_push_changes(syncDb, opRef, errorRef)
        }

        // 2) Pull remote changes (physical pages; may be empty).
        val changes = drainOperation(syncDb, "wait_changes", { opRef, errorRef ->
            turso_sync_database_wait_changes(syncDb, opRef, errorRef)
        }) { op, errorRef ->
            memScoped {
                val changesRef = alloc<CPointerVar<turso_sync_changes_t>>()
                check(turso_sync_operation_result_extract_changes(op, changesRef.ptr), errorRef, "extract_changes")
                turso_sync_operation_deinit(op)
                changesRef.value
            }
        }

        // 3) Apply remote changes; apply CONSUMES them on either outcome.
        if (changes != null) {
            drainVoidOperation(syncDb, "apply_changes") { opRef, errorRef ->
                turso_sync_database_apply_changes(syncDb, changes, opRef, errorRef)
            }
        }
    }

    override fun close() {
        val syncDb = db ?: return
        connection?.let { turso_connection_close(it, null) }
        turso_sync_database_deinit(syncDb)
        db = null
        connection = null
        opened = false
    }

    override fun execute(sql: String, params: Map<String, Any>?) {
        requireOpenDb()
        val stmt = prepareSingle(sql)
        try {
            bind(stmt, params)
            executeWithIo(stmt)
        } finally {
            turso_statement_finalize(stmt, null)
        }
    }

    override fun <T> query(sql: String, params: Map<String, Any>?, block: (Sequence<Row>) -> T): T {
        requireOpenDb()
        val stmt = prepareSingle(sql)
        try {
            bind(stmt, params)
            val rows = sequence {
                while (stepNext(stmt)) {
                    yield(readRow(stmt))
                }
            }
            return block(rows)
        } finally {
            turso_statement_finalize(stmt, null)
        }
    }

    private fun requireOpenDb(): CPointer<turso_sync_database_t> {
        ensureOpen()
        return db ?: throw TursoException("${TURSO_LOG_PREFIX}database is not open")
    }
}

internal class TursoException(message: String) : RuntimeException(message)

// ---- helpers ---------------------------------------------------------------------------

/**
 * Allocates a `turso_slice_ref_t` pointing at a copy of [data] in the scope.
 * Allocated as a 1-element array because the struct's own `ptr` field shadows
 * cinterop's address-of `.ptr` extension.
 */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.slice(data: ByteArray): CPointer<turso_slice_ref_t> {
    val buf = allocArray<ByteVar>(data.size.coerceAtLeast(1))
    data.forEachIndexed { i, b -> buf[i] = b }
    val slice = allocArray<turso_slice_ref_t>(1)
    slice[0].ptr = buf
    slice[0].len = data.size.convert()
    return slice
}

@OptIn(ExperimentalForeignApi::class)
private fun turso_slice_ref_t.asBytes(): ByteArray? {
    val p = ptr ?: return null
    val len = len.toInt()
    if (len <= 0) return ByteArray(0)
    return p.reinterpret<ByteVar>().readBytes(len)
}

@OptIn(ExperimentalForeignApi::class)
private fun turso_slice_ref_t.asString(): String? = asBytes()?.decodeToString()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { platform.posix.memcpy(it.addressOf(0), bytes, length) }
    return out
}

private class HttpResult(val status: Int, val body: ByteArray?, val error: String?)

/**
 * NSURLSession has no synchronous API, but the sync IO pump is blocking by
 * design and already runs off the main thread on [DbDispatcher].
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSMutableURLRequest.sendBlocking(): HttpResult {
    val semaphore = dispatch_semaphore_create(0)
    var status = 0
    var body: ByteArray? = null
    var failure: String? = null

    val task = NSURLSession.sharedSession.dataTaskWithRequest(this) { data, response, error ->
        if (error != null) {
            failure = error.localizedDescription
        } else {
            status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
            body = data?.toByteArray()
        }
        dispatch_semaphore_signal(semaphore)
    }
    task.resume()
    dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)
    return HttpResult(status, body, failure)
}
