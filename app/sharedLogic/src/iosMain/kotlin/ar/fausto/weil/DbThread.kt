package ar.fausto.weil

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.withContext
import platform.Foundation.NSCondition
import platform.Foundation.NSThread
import kotlin.coroutines.CoroutineContext

/**
 * A single dedicated thread with a main-thread-sized (16MB) stack.
 *
 * libsql's native core recursively parses SQL with huge non-inlined Rust frames
 * and overflows the ~512KB stack of Kotlin/Native worker threads. All libsql
 * work on iOS is routed through this thread, matching the Android dispatcher.
 */
internal object DbThread {
    private val queue = mutableListOf<() -> Unit>()
    private val condition = NSCondition()

    private val thread = NSThread {
        while (true) {
            condition.lock()
            while (queue.isEmpty()) {
                condition.wait()
            }
            val block = queue.removeAt(0)
            condition.unlock()
            block()
        }
    }.apply {
        name = "weil-db"
        stackSize = 16uL * 1024uL * 1024uL
    }

    init {
        thread.start()
    }

    fun dispatch(block: () -> Unit) {
        condition.lock()
        queue.add(block)
        condition.signal()
        condition.unlock()
    }
}

/**
 * Coroutine dispatcher backed by [DbThread].
 */
val DbDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        DbThread.dispatch { block.run() }
    }
}

/**
 * Runs [block] on the dedicated libsql thread and returns its result.
 */
suspend fun <T> withDbContext(block: () -> T): T = withContext(DbDispatcher) { block() }
