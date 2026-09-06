package ar.fausto.weil

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * JNA interface mapping over the new Turso C ABI (turso.h + turso_sync.h,
 * vendored in ../turso-headers alongside the jniLibs .so).
 *
 * Type mapping used throughout:
 * - C `int32_t`/enums -> Int
 * - C `int64_t`/`uint64_t` -> Long
 * - C `size_t` -> NativeLong (4 bytes on 32-bit ABIs, 8 on 64-bit)
 * - C `bool` -> Boolean
 * - opaque handles -> Pointer
 * - `const char**` / opaque `T**` out params -> PointerByReference
 */
internal object Turso {
    // status_code values (sdk-kit/turso.h)
    const val OK = 0
    const val DONE = 1
    const val ROW = 2
    const val IO = 3

    // turso_type_t values
    const val TYPE_UNKNOWN = 0
    const val TYPE_INTEGER = 1
    const val TYPE_REAL = 2
    const val TYPE_TEXT = 3
    const val TYPE_BLOB = 4
    const val TYPE_NULL = 5

    // turso_sync_io_request_type_t values (sync/sdk-kit/turso_sync.h)
    const val SYNC_IO_NONE = 0
    const val SYNC_IO_HTTP = 1
    const val SYNC_IO_FULL_READ = 2
    const val SYNC_IO_FULL_WRITE = 3

    // turso_sync_operation_result_type_t values
    const val RESULT_NONE = 0
    const val RESULT_CONNECTION = 1
    const val RESULT_CHANGES = 2
    const val RESULT_STATS = 3

    val native: NativeApi by lazy {
        com.sun.jna.Native.load("turso_sync_sdk_kit", NativeApi::class.java)
    }
}

internal interface NativeApi : Library {
    // ---- core (turso.h) ----
    fun turso_connection_prepare_single(conn: Pointer, sql: String, statement: PointerByReference, error: PointerByReference): Int
    fun turso_statement_execute(stmt: Pointer, rowsChanges: LongByReference? /* uint64_t* */, error: PointerByReference): Int
    fun turso_statement_step(stmt: Pointer, error: PointerByReference): Int
    fun turso_statement_run_io(stmt: Pointer, error: PointerByReference): Int
    fun turso_statement_reset(stmt: Pointer, error: PointerByReference): Int
    fun turso_statement_finalize(stmt: Pointer): Int
    fun turso_statement_column_count(stmt: Pointer): Long
    fun turso_statement_named_position(stmt: Pointer, name: String): Long // -1 if not found, 1-based position otherwise
    fun turso_statement_bind_positional_null(stmt: Pointer, position: NativeLong): Int
    fun turso_statement_bind_positional_int(stmt: Pointer, position: NativeLong, value: Long): Int
    fun turso_statement_bind_positional_double(stmt: Pointer, position: NativeLong, value: Double): Int
    fun turso_statement_bind_positional_blob(stmt: Pointer, position: NativeLong, ptr: Pointer, len: NativeLong): Int
    fun turso_statement_bind_positional_text(stmt: Pointer, position: NativeLong, ptr: Pointer, len: NativeLong): Int
    fun turso_statement_row_value_kind(stmt: Pointer, index: NativeLong): Int
    fun turso_statement_row_value_bytes_count(stmt: Pointer, index: NativeLong): Long
    fun turso_statement_row_value_bytes_ptr(stmt: Pointer, index: NativeLong): Pointer
    fun turso_statement_row_value_int(stmt: Pointer, index: NativeLong): Long
    fun turso_statement_row_value_double(stmt: Pointer, index: NativeLong): Double
    fun turso_str_deinit(s: Pointer)
    fun turso_connection_close(conn: Pointer, error: PointerByReference?): Int

    // ---- sync (turso_sync.h) ----
    fun turso_sync_database_new(dbConfig: TursoDatabaseConfig, syncConfig: TursoSyncDatabaseConfig, database: PointerByReference, error: PointerByReference): Int
    fun turso_sync_database_open(db: Pointer, operation: PointerByReference, error: PointerByReference): Int
    fun turso_sync_database_create(db: Pointer, operation: PointerByReference, error: PointerByReference): Int
    fun turso_sync_database_connect(db: Pointer, operation: PointerByReference, error: PointerByReference): Int
    fun turso_sync_database_push_changes(db: Pointer, operation: PointerByReference, error: PointerByReference): Int
    fun turso_sync_database_wait_changes(db: Pointer, operation: PointerByReference, error: PointerByReference): Int
    fun turso_sync_database_apply_changes(db: Pointer, changes: Pointer, operation: PointerByReference, error: PointerByReference): Int
    fun turso_sync_database_checkpoint(db: Pointer, operation: PointerByReference, error: PointerByReference): Int
    fun turso_sync_database_io_take_item(db: Pointer, item: PointerByReference, error: PointerByReference): Int
    fun turso_sync_database_io_step_callbacks(db: Pointer, error: PointerByReference): Int
    fun turso_sync_database_io_request_kind(item: Pointer): Int
    fun turso_sync_database_io_request_http(item: Pointer, request: TursoSyncIoHttpRequest): Int
    fun turso_sync_database_io_request_http_header(item: Pointer, index: Int, header: TursoSyncIoHttpHeader): Int
    fun turso_sync_database_io_request_full_read(item: Pointer, request: TursoSyncIoFullReadRequest): Int
    fun turso_sync_database_io_request_full_write(item: Pointer, request: TursoSyncIoFullWriteRequest): Int
    fun turso_sync_database_io_poison(item: Pointer, error: SliceRef): Int
    fun turso_sync_database_io_status(item: Pointer, status: Int): Int
    fun turso_sync_database_io_push_buffer(item: Pointer, buffer: SliceRef): Int
    fun turso_sync_database_io_done(item: Pointer): Int
    fun turso_sync_operation_resume(operation: Pointer, error: PointerByReference): Int
    fun turso_sync_operation_result_kind(operation: Pointer): Int
    fun turso_sync_operation_result_extract_connection(operation: Pointer, connection: PointerByReference): Int
    fun turso_sync_operation_result_extract_changes(operation: Pointer, changes: PointerByReference): Int
    fun turso_sync_operation_deinit(operation: Pointer)
    fun turso_sync_database_deinit(db: Pointer)
    fun turso_sync_database_io_item_deinit(item: Pointer)
}

/** turso_slice_ref_t */
@Structure.FieldOrder("ptr", "len")
open class SliceRef : Structure() {
    @JvmField
    var ptr: Pointer? = null

    @JvmField
    var len: NativeLong = NativeLong(0)

    class ByRef : SliceRef(), Structure.ByReference

    companion object {
        fun of(data: ByteArray): SliceRef {
            val mem = if (data.isEmpty()) Memory(1) else Memory(data.size.toLong())
            if (data.isNotEmpty()) mem.write(0, data, 0, data.size)
            val s = SliceRef()
            s.ptr = mem
            s.len = NativeLong(data.size.toLong())
            return s
        }

        fun of(text: String): SliceRef = of(text.toByteArray(Charsets.UTF_8))
    }

    fun asBytes(): ByteArray? {
        val p = ptr ?: return null
        val n = len.toLong()
        if (n <= 0) return null
        return p.getByteArray(0, n.toInt())
    }

    fun asString(): String? = asBytes()?.decodeToString()

    override fun toString(): String = asString() ?: ""
}

/** turso_database_config_t */
@Structure.FieldOrder("async_io", "path", "experimental_features", "vfs", "encryption_cipher", "encryption_hexkey", "page_codec", "open_flags")
class TursoDatabaseConfig : Structure() {
    /** non-zero means caller-driven IO for local statements */
    @JvmField
    var async_io: Long = 0

    @JvmField
    var path: String? = null

    @JvmField
    var experimental_features: String? = null

    @JvmField
    var vfs: String? = null

    @JvmField
    var encryption_cipher: String? = null

    @JvmField
    var encryption_hexkey: String? = null

    @JvmField
    var page_codec: Pointer? = null

    @JvmField
    var open_flags: Int = 0
}

/** turso_sync_database_config_t */
@Structure.FieldOrder("path", "remote_url", "client_name", "long_poll_timeout_ms", "bootstrap_if_empty", "reserved_bytes", "partial_bootstrap_strategy_prefix", "partial_bootstrap_strategy_query", "partial_bootstrap_segment_size", "partial_bootstrap_prefetch", "remote_encryption_key", "remote_encryption_cipher", "push_operations_threshold", "pull_bytes_threshold", "logical_mvcc_pull")
class TursoSyncDatabaseConfig : Structure() {
    @JvmField
    var path: String? = null

    @JvmField
    var remote_url: String? = null

    @JvmField
    var client_name: String? = null

    @JvmField
    var long_poll_timeout_ms: Int = 0

    @JvmField
    var bootstrap_if_empty: Boolean = true

    @JvmField
    var reserved_bytes: Int = 0

    @JvmField
    var partial_bootstrap_strategy_prefix: Int = 0

    @JvmField
    var partial_bootstrap_strategy_query: String? = null

    @JvmField
    var partial_bootstrap_segment_size: NativeLong = NativeLong(0)

    @JvmField
    var partial_bootstrap_prefetch: Boolean = false

    @JvmField
    var remote_encryption_key: String? = null

    @JvmField
    var remote_encryption_cipher: String? = null

    @JvmField
    var push_operations_threshold: NativeLong = NativeLong(0)

    @JvmField
    var pull_bytes_threshold: NativeLong = NativeLong(0)

    @JvmField
    var logical_mvcc_pull: Boolean = false
}

/** turso_sync_io_http_request_t */
@Structure.FieldOrder("url", "method", "path", "body", "headers")
class TursoSyncIoHttpRequest : Structure() {
    @JvmField
    var url: SliceRef = SliceRef()

    @JvmField
    var method: SliceRef = SliceRef()

    @JvmField
    var path: SliceRef = SliceRef()

    @JvmField
    var body: SliceRef = SliceRef()

    @JvmField
    var headers: Int = 0
}

/** turso_sync_io_http_header_t */
@Structure.FieldOrder("key", "value")
class TursoSyncIoHttpHeader : Structure() {
    @JvmField
    var key: SliceRef = SliceRef()

    @JvmField
    var value: SliceRef = SliceRef()
}

/** turso_sync_io_full_read_request_t */
@Structure.FieldOrder("path")
class TursoSyncIoFullReadRequest : Structure() {
    @JvmField
    var path: SliceRef = SliceRef()
}

/** turso_sync_io_full_write_request_t */
@Structure.FieldOrder("path", "content")
class TursoSyncIoFullWriteRequest : Structure() {
    @JvmField
    var path: SliceRef = SliceRef()

    @JvmField
    var content: SliceRef = SliceRef()
}
