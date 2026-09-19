package ar.fausto.weil

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import kotlin.math.sqrt
import org.sqlite.Function

/**
 * The three vector functions the Turso sync engine ships and plain
 * sqlite-jdbc does not, implemented as SQLite user functions so the desktop
 * harness can render the "parecidos a este" screens for real instead of
 * showing an empty section.
 *
 * Same wire format as Turso's `F32_BLOB`: little-endian float32, packed, no
 * header. Deliberately *not* a general implementation — the app only ever
 * calls these three, always on `f32` of one dimensionality.
 */
fun registerVectorFunctions(conn: Connection) {
    // vector32('[0.1,0.2,…]') -> blob
    Function.create(conn, "vector32", object : Function() {
        override fun xFunc() {
            val text = value_text(0)
            if (text == null) {
                result()
                return
            }
            result(encodeF32(parseVectorLiteral(text)))
        }
    })

    // vector_extract(blob) -> '[0.1,0.2,…]'
    Function.create(conn, "vector_extract", object : Function() {
        override fun xFunc() {
            val blob = value_blob(0)
            if (blob == null) {
                result()
                return
            }
            result(decodeF32(blob).joinToString(",", prefix = "[", postfix = "]") { it.toString() })
        }
    })

    // vector_distance_cos(a, b) -> 1 - cos(a, b)
    Function.create(conn, "vector_distance_cos", object : Function() {
        override fun xFunc() {
            val a = value_blob(0)
            val b = value_blob(1)
            if (a == null || b == null) {
                result()
                return
            }
            result(cosineDistance(decodeF32(a), decodeF32(b)))
        }
    })
}

private fun parseVectorLiteral(text: String): FloatArray =
    text.trim().removePrefix("[").removeSuffix("]")
        .split(',')
        .mapNotNull { it.trim().toFloatOrNull() }
        .toFloatArray()

private fun encodeF32(values: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in values) buffer.putFloat(v)
    return buffer.array()
}

private fun decodeF32(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 4) { buffer.getFloat() }
}

private fun cosineDistance(a: FloatArray, b: FloatArray): Double {
    if (a.size != b.size || a.isEmpty()) return 1.0
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i].toDouble() * b[i]
        na += a[i].toDouble() * a[i]
        nb += b[i].toDouble() * b[i]
    }
    val norm = sqrt(na) * sqrt(nb)
    return if (norm == 0.0) 1.0 else 1.0 - dot / norm
}

/**
 * Deterministic, local, **non-semantic** embedder: hashed token counts,
 * L2-normalized. It exists so the harness can exercise the whole path (sweep →
 * store → scan → render) with no key and no network. It must never reach a
 * real database — the model tag says so out loud, and rows tagged with it are
 * ignored by a real [EMBEDDING_TAG] search.
 */
class FakeEmbedder : TextEmbedder {
    override val tag: String = "hash-bow/$EMBEDDING_DIMS"

    override suspend fun embed(texts: List<String>): List<List<Double>> = texts.map { text ->
        val vec = DoubleArray(EMBEDDING_DIMS)
        for (token in text.lowercase().split(Regex("[^\\p{L}\\p{N}$]+"))) {
            if (token.isEmpty()) continue
            var h = 2166136261u
            for (ch in token) h = (h xor ch.code.toUInt()) * 16777619u
            vec[(h % EMBEDDING_DIMS.toUInt()).toInt()] += 1.0
        }
        val norm = sqrt(vec.sumOf { it * it }).takeIf { it > 0 } ?: 1.0
        vec.map { it / norm }
    }
}
