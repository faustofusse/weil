package ar.fausto.weil

import android.content.Context
import com.sun.jna.Memory
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val SCHEMA_SQL =
    "drop table if exists cuentas; create table if not exists accounts(id text primary key not null, name text not null);"

private const val TURSO_LOG_PREFIX = "turso: "

internal class TursoException(message: String) : RuntimeException(message)

/**
 * Android implementation of [Database] backed by the new Turso (SQLite
 * rewrite) sync engine, `libturso_sync_sdk_kit.so`, over the JNA interface
 * mapping [Turso.NativeApi] (C ABI: turso.h + turso_sync.h).
 *
 * Sync IO is caller-driven: operations returned by create/connect/push/wait
 * are `resume()`d until DONE; on TURSO_IO the caller drains the IO queue
 * (HTTP against the Turso Cloud server, atomic file read/write for sync
 * metadata) and calls `io_step_callbacks()`. Local statement execution is
 * library-driven (`async_io = 0`), so queries block only on local file IO.
 *
 * All native work is deferred to first use so that it runs on the 16 MB-stack
 * db dispatcher (DatabaseProvider wraps `use` with it) — the Rust parser
 * overflows on small coroutine stacks otherwise.
 */
class AndroidDatabase(
    context: Context,
    path: String,
    private val url: String,
    private val authToken: String,
) : Database {
    private val file = File(context.filesDir, path)
    private val opened = AtomicBoolean(false)
    private val tursoSetupDone = AtomicBoolean(false)

    private var db: Pointer? = null
    private var connection: Pointer? = null

    /** Blocking-first HTTP client used inside the sync IO pump on the db dispatcher. */
    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Last HTTP status >= 400 seen by the sync IO pump, for auth diagnostics. */
    @Volatile
    private var lastHttpFailureStatus: Int = 0

    private val native get() = Turso.native

    init {
        require(file.parentFile != null) { "${TURSO_LOG_PREFIX}invalid database path: $path" }
        file.parentFile?.mkdirs()
    }

    // ---- lifecycle ---------------------------------------------------------------

    private fun ensureOpen() {
        if (opened.get()) return
        deleteStaleLibsqlReplica()
        tursoSetup()

        val err = PointerByReference()
        val dbRef = PointerByReference()
        check(native.turso_sync_database_new(dbConfig(), syncConfig(), dbRef, err), err, "sync_database_new")
        val syncDb = dbRef.value ?: throw TursoException("${TURSO_LOG_PREFIX}sync_database_new returned null database")

        try {
            // Opens the synced database, or bootstraps it from the remote when
            // the local file is empty (bootstrap_if_empty = true).
            drainVoidOperation(syncDb, "create") { opRef, errorRef ->
                native.turso_sync_database_create(syncDb, opRef, errorRef)
            }

            val conn = drainOperation(syncDb, "connect", { opRef, errorRef ->
                native.turso_sync_database_connect(syncDb, opRef, errorRef)
            }) { op, errorRef ->
                val connRef = PointerByReference()
                check(native.turso_sync_operation_result_extract_connection(op, connRef), errorRef, "extract_connection")
                val extracted = connRef.value
                    ?: throw TursoException("${TURSO_LOG_PREFIX}connect returned null connection")
                native.turso_sync_operation_deinit(op)
                extracted
            }

            // Expose both before schema setup so failures unwind through the catch below.
            db = syncDb
            connection = conn
            opened.set(true)

            // Idempotent schema setup; no-op against an already-bootstrapped schema.
            executeBatch(SCHEMA_SQL)
        } catch (t: Throwable) {
            connection?.let { existing -> runCatching { native.turso_connection_close(existing, err) } }
            runCatching { native.turso_sync_database_deinit(syncDb) }
            db = null
            connection = null
            throw t
        }
    }

    private fun dbConfig(): TursoDatabaseConfig = TursoDatabaseConfig().apply {
        async_io = 0
        path = file.absolutePath
    }

    private fun syncConfig(): TursoSyncDatabaseConfig = TursoSyncDatabaseConfig().apply {
        path = file.absolutePath
        remote_url = url
        client_name = "weil"
        long_poll_timeout_ms = 0
        bootstrap_if_empty = true
    }

    /** Removes on-disk replicas written by the old libsql engine (same parent dir). */
    private fun deleteStaleLibsqlReplica() {
        file.parentFile?.listFiles { f -> f.name.startsWith("local.db") }?.forEach { it.delete() }
    }

    private fun tursoSetup() {
        // The new Turso C ABI works fully without global setup; it only wires
        // optional tracing. Keep as a no-op placeholder.
    }

    private fun errorRef(err: PointerByReference): String {
        val p = err.value ?: return "error code <null>"
        val msg = p.getString(0, Charsets.UTF_8.name())
        return msg.ifEmpty { "error code <empty>" }
    }

    private fun check(status: Int, err: PointerByReference, what: String) {
        if (status != Turso.OK) throw TursoException("$TURSO_LOG_PREFIX [$what] ${errorRef(err)}")
    }

    // ---- async operation driver ------------------------------------------------

    /**
     * Starts an async operation ([start] fills opRef), pumps it to DONE, then
     * hands the operation to [extract] — which deinits it and returns the result.
     */
    private fun <T> drainOperation(
        syncDb: Pointer,
        what: String,
        start: (PointerByReference, PointerByReference) -> Int,
        extract: (Pointer, PointerByReference) -> T,
    ): T {
        val err = PointerByReference()
        val opRef = PointerByReference()
        check(start(opRef, err), err, what)
        val op = opRef.value ?: throw TursoException("${TURSO_LOG_PREFIX}$what returned null operation")
        try {
            while (true) {
                when (val status = native.turso_sync_operation_resume(op, err)) {
                    Turso.DONE -> return extract(op, err)
                    Turso.IO -> {
                        pumpIoQueue(syncDb)
                        check(native.turso_sync_database_io_step_callbacks(syncDb, err), err, "io_step_callbacks")
                    }
                    else -> throw TursoException(httpHint("$TURSO_LOG_PREFIX [$what] ${errorRef(err)}") + " (code $status)")
                }
            }
        } catch (t: Throwable) {
            native.turso_sync_operation_deinit(op)
            throw t
        }
    }

    private fun drainVoidOperation(syncDb: Pointer, what: String, start: (PointerByReference, PointerByReference) -> Int) {
        drainOperation(syncDb, what, start) { op, _ ->
            native.turso_sync_operation_deinit(op)
        }
    }

    private fun httpHint(msg: String): String =
        if (lastHttpFailureStatus > 0) "$msg [HTTP $lastHttpFailureStatus]" else msg

    // ---- IO pump ------------------------------------------------------------------

    private fun pumpIoQueue(syncDb: Pointer) {
        val err = PointerByReference()
        val itemRef = PointerByReference()
        while (native.turso_sync_database_io_take_item(syncDb, itemRef, err) == Turso.OK) {
            val item = itemRef.value ?: break
            try {
                processIoItem(item)
            } catch (t: Throwable) {
                val msg = t.message ?: t.toString()
                native.turso_sync_database_io_poison(item, SliceRef.of(msg))
            } finally {
                native.turso_sync_database_io_item_deinit(item)
            }
        }
    }

    private fun processIoItem(item: Pointer) {
        when (native.turso_sync_database_io_request_kind(item)) {
            Turso.SYNC_IO_HTTP -> processHttp(item)
            Turso.SYNC_IO_FULL_READ -> processFullRead(item)
            Turso.SYNC_IO_FULL_WRITE -> processFullWrite(item)
            else -> native.turso_sync_database_io_done(item)
        }
    }

    private fun processHttp(item: Pointer) {
        val request = TursoSyncIoHttpRequest()
        val startErr = PointerByReference()
        check(native.turso_sync_database_io_request_http(item, request), startErr, "io_request_http")

        val baseUrl = normalizeUrl(request.url.asString() ?: url)
        val fullUrl = joinUrl(baseUrl, request.path.asString() ?: "")

        val headersBuilder = okhttp3.Headers.Builder()
        for (i in 0 until request.headers) {
            val h = TursoSyncIoHttpHeader()
            check(native.turso_sync_database_io_request_http_header(item, i, h), PointerByReference(), "io_request_http_header")
            val key = h.key.asString() ?: continue
            val value = h.value.asString() ?: continue
            // Keep our Authorization authoritative if the engine ever sets one.
            if (key.equals("Authorization", ignoreCase = true)) continue
            headersBuilder.add(key, value)
        }
        if (authToken.isNotEmpty()) headersBuilder.add("Authorization", "Bearer $authToken")

        val method = (request.method.asString() ?: "GET").uppercase()
        val body = request.body.asBytes()
        val requestBuilder = okhttp3.Request.Builder().url(fullUrl).headers(headersBuilder.build())
        when (method) {
            "GET", "HEAD" -> {
                if (method == "GET") requestBuilder.get() else requestBuilder.head()
            }
            "DELETE" -> requestBuilder.delete((body ?: ByteArray(0)).toRequestBody())
            "PATCH" -> requestBuilder.patch((body ?: ByteArray(0)).toRequestBody())
            "POST" -> requestBuilder.post((body ?: ByteArray(0)).toRequestBody())
            "PUT" -> requestBuilder.put((body ?: ByteArray(0)).toRequestBody())
            else -> requestBuilder.method(method, (body ?: ByteArray(0)).toRequestBody())
        }

        http.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code >= 400) lastHttpFailureStatus = response.code
            native.turso_sync_database_io_status(item, response.code)
            val bytes = response.body?.bytes()
            if (bytes != null && bytes.isNotEmpty()) {
                native.turso_sync_database_io_push_buffer(item, SliceRef.of(bytes))
            }
            native.turso_sync_database_io_done(item)
        }
    }

    private fun processFullRead(item: Pointer) {
        val request = TursoSyncIoFullReadRequest()
        check(native.turso_sync_database_io_request_full_read(item, request), PointerByReference(), "io_request_full_read")
        val target = File(request.path.asString() ?: "")
        val data = if (target.exists() && target.isFile) target.readBytes() else ByteArray(0)
        if (data.isNotEmpty()) {
            native.turso_sync_database_io_push_buffer(item, SliceRef.of(data))
        }
        native.turso_sync_database_io_done(item)
    }

    private fun processFullWrite(item: Pointer) {
        val request = TursoSyncIoFullWriteRequest()
        check(native.turso_sync_database_io_request_full_write(item, request), PointerByReference(), "io_request_full_write")
        val target = File(request.path.asString() ?: "")
        val content = request.content.asBytes() ?: ByteArray(0)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(content)
        if (!tmp.renameTo(target)) {
            target.writeBytes(content)
            tmp.delete()
        }
        native.turso_sync_database_io_done(item)
    }

    /** libsql:// and turso:// URLs are https:// over the wire. */
    private fun normalizeUrl(raw: String): String =
        raw.replace(Regex("^(libsql|turso)://"), "https://")

    private fun joinUrl(base: String, path: String): String {
        if (path.isEmpty()) return base
        val pathPart = if (path.startsWith("/")) path else "/$path"
        return base.removeSuffix("/") + pathPart
    }

    // ---- statements -------------------------------------------------------------------

    private fun prepareSingle(sql: String): Pointer {
        val err = PointerByReference()
        val stmtRef = PointerByReference()
        check(
            native.turso_connection_prepare_single(requireConnection(), sql, stmtRef, err),
            err,
            "prepare",
        )
        return stmtRef.value ?: throw TursoException("${TURSO_LOG_PREFIX}prepare returned null statement for '$sql'")
    }

    private fun requireConnection(): Pointer =
        connection ?: throw TursoException("${TURSO_LOG_PREFIX}connection is not open")

    /** Steps the statement; loops over TURSO_IO defensively. True when a row is available. */
    private fun stepNext(stmt: Pointer): Boolean {
        val err = PointerByReference()
        while (true) {
            when (val status = native.turso_statement_step(stmt, err)) {
                Turso.DONE -> return false
                Turso.ROW -> return true
                Turso.IO -> check(native.turso_statement_run_io(stmt, err), err, "statement_run_io")
                else -> throw TursoException("$TURSO_LOG_PREFIX step failed: ${errorRef(err)} (code $status)")
            }
        }
    }

    private fun executeWithIo(stmt: Pointer) {
        val err = PointerByReference()
        val changes = LongByReference()
        while (true) {
            when (val status = native.turso_statement_execute(stmt, changes, err)) {
                Turso.DONE, Turso.ROW -> return
                Turso.IO -> check(native.turso_statement_run_io(stmt, err), err, "statement_run_io")
                else -> throw TursoException("$TURSO_LOG_PREFIX execute failed: ${errorRef(err)} (code $status)")
            }
        }
    }

    /** Binds named params (`:name` keys) via turso_statement_named_position. */
    private fun bind(stmt: Pointer, params: Map<String, Any>?) {
        for ((name, value) in params ?: emptyMap()) {
            // 1-indexed position; <= 0 means the name wasn't found — silently
            // unbound, identical to the libsql missing-colon gotcha.
            val pos = native.turso_statement_named_position(stmt, name)
            if (pos <= 0) continue
            val position = NativeLong(pos)
            val err = PointerByReference()
            when (value) {
                is Long -> check(native.turso_statement_bind_positional_int(stmt, position, value), err, "bind_int")
                is Int -> check(native.turso_statement_bind_positional_int(stmt, position, value.toLong()), err, "bind_int")
                is Short -> check(native.turso_statement_bind_positional_int(stmt, position, value.toLong()), err, "bind_int")
                is Byte -> check(native.turso_statement_bind_positional_int(stmt, position, value.toLong()), err, "bind_int")
                is Boolean -> check(
                    native.turso_statement_bind_positional_int(stmt, position, if (value) 1L else 0L),
                    err,
                    "bind_int",
                )
                is Double -> check(native.turso_statement_bind_positional_double(stmt, position, value), err, "bind_double")
                is Float -> check(
                    native.turso_statement_bind_positional_double(stmt, position, value.toDouble()),
                    err,
                    "bind_double",
                )
                is String -> {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    check(
                        native.turso_statement_bind_positional_text(stmt, position, wrap(bytes), NativeLong(bytes.size.toLong())),
                        err,
                        "bind_text",
                    )
                }
                is ByteArray -> check(
                    native.turso_statement_bind_positional_blob(stmt, position, wrap(value), NativeLong(value.size.toLong())),
                    err,
                    "bind_blob",
                )
                null -> check(native.turso_statement_bind_positional_null(stmt, position), err, "bind_null")
                else -> throw TursoException("$TURSO_LOG_PREFIX unsupported parameter type for '$name': ${value::class.simpleName}")
            }
        }
    }

    private fun wrap(bytes: ByteArray): Pointer {
        if (bytes.isEmpty()) return Memory(1)
        val mem = Memory(bytes.size.toLong())
        mem.write(0, bytes, 0, bytes.size)
        return mem
    }

    /** Copies the current row out — native pointers are valid only until the next step. */
    private fun readRow(stmt: Pointer): Row {
        val count = native.turso_statement_column_count(stmt).toInt()
        val row = ArrayList<Any?>(count)
        for (i in 0 until count) {
            val index = NativeLong(i.toLong())
            row.add(
                when (native.turso_statement_row_value_kind(stmt, index)) {
                    Turso.TYPE_INTEGER -> native.turso_statement_row_value_int(stmt, index)
                    Turso.TYPE_REAL -> native.turso_statement_row_value_double(stmt, index)
                    Turso.TYPE_TEXT -> native.turso_statement_row_value_bytes_ptr(stmt, index)?.let { p ->
                        val len = native.turso_statement_row_value_bytes_count(stmt, index)
                        String(p.getByteArray(0, len.toInt()), Charsets.UTF_8)
                    }
                    Turso.TYPE_BLOB -> native.turso_statement_row_value_bytes_ptr(stmt, index)?.let { p ->
                        val len = native.turso_statement_row_value_bytes_count(stmt, index)
                        p.getByteArray(0, len.toInt())
                    }
                    else -> null
                },
            )
        }
        return row
    }

    private fun executeBatch(sql: String) {
        for (statement in sql.split(';')) {
            val trimmed = statement.trim()
            if (trimmed.isNotEmpty()) executeImpl(trimmed, null)
        }
    }

    // ---- Database interface -------------------------------------------------------------

    override fun sync() {
        val syncDb = requireOpenDb()

        // 1) Push local changes (logical mutations) — mirrors the old db.push().
        drainVoidOperation(syncDb, "push_changes") { opRef, errorRef ->
            native.turso_sync_database_push_changes(syncDb, opRef, errorRef)
        }

        // 2) Pull remote changes (physical pages; may be empty).
        val changes = drainOperation(syncDb, "wait_changes", { opRef, errorRef ->
            native.turso_sync_database_wait_changes(syncDb, opRef, errorRef)
        }) { op, errorRef ->
            val changesRef = PointerByReference()
            check(native.turso_sync_operation_result_extract_changes(op, changesRef), errorRef, "extract_changes")
            native.turso_sync_operation_deinit(op)
            changesRef.value
        }

        // 3) Apply remote changes; apply CONSUMES them on either outcome.
        if (changes != null) {
            drainVoidOperation(syncDb, "apply_changes") { opRef, errorRef ->
                native.turso_sync_database_apply_changes(syncDb, changes, opRef, errorRef)
            }
        }
    }

    override fun close() {
        val syncDb = db ?: return
        connection?.let { conn ->
            val err = PointerByReference()
            try {
                native.turso_connection_close(conn, err)
            } catch (_: Throwable) {
            }
        }
        try {
            native.turso_sync_database_deinit(syncDb)
        } catch (_: Throwable) {
        }
        http.dispatcher.executorService.shutdown()
        db = null
        connection = null
        opened.set(false)
    }

    override fun execute(sql: String, params: Map<String, Any>?) {
        requireOpenDb()
        executeImpl(sql, params)
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
            native.turso_statement_finalize(stmt)
        }
    }

    // ---- internals ----------------------------------------------------------------------

    private fun executeImpl(sql: String, params: Map<String, Any>?) {
        val stmt = prepareSingle(sql)
        try {
            bind(stmt, params)
            executeWithIo(stmt)
        } finally {
            native.turso_statement_finalize(stmt)
        }
    }

    private fun requireOpenDb(): Pointer {
        ensureOpen()
        return db ?: throw TursoException("${TURSO_LOG_PREFIX}database is not open")
    }
}
