package ar.fausto.weil

typealias Row = List<Any?>

interface Database {
    fun sync()
    fun close()

    fun execute(sql: String, params: Map<String, Any>? = null)

    /**
     * Streams rows without buffering the whole result set in memory.
     * [block] receives a Sequence backed by the platform's cursor, which is
     * closed deterministically when [block] returns — even on exception or
     * early termination (first(), take(n), etc.).
     *
     * The Sequence must be consumed inside [block]; storing or returning it
     * escapes the cursor's lifetime and will crash or read a closed cursor.
     *
     * Named parameters use SQL `:name` placeholders and map keys that include
     * the leading colon (":name") — the underlying libsql core matches the
     * raw token, and a missing colon silently binds nothing.
     */
    fun <T> query(sql: String, params: Map<String, Any>?, block: (Sequence<Row>) -> T): T

    /** Convenience: buffers all rows into a List. Use for small results. */
    fun query(sql: String, params: Map<String, Any>?): List<Row> =
        query(sql, params) { it.toList() }
}
