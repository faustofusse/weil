package ar.fausto.weil

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DatabaseProvider(
    private val auth: AuthRepository,
    private val dbContext: CoroutineContext,
    private val dbFactory: (userId: String, url: String, token: String) -> Database,
    // Both contexts must resolve to the *same* single thread as [dbContext]:
    // AndroidDatabase exposes one native connection and only one thread may
    // ever touch it. This is purely a queue-priority knob (see WeilApplication's
    // dbDispatcher/dbReadDispatcher) so cheap reads don't wait behind a queued
    // (not yet started) background sync; it changes ordering, not concurrency.
    private val readContext: CoroutineContext = dbContext,
) {
    private val mutex = Mutex()
    private var database: Database? = null
    private var openedUserId: String? = null

    suspend fun <T> use(block: (Database) -> T): T = run(dbContext, block)

    /** For pure reads only — never for mutations or `sync()`. See [readContext]. */
    suspend fun <T> useForRead(block: (Database) -> T): T = run(readContext, block)

    private suspend fun <T> run(context: CoroutineContext, block: (Database) -> T): T {
        var attempt = 0
        while (true) {
            val db = currentDb()
            try {
                return withContext(context) { block(db) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                attempt++
                if (attempt > 1 || !isAuthError(e)) throw e
                try {
                    auth.refresh()
                } catch (e2: SessionExpired) {
                    throw e2
                } catch (_: Exception) {
                    throw e
                }
                closeCurrent()
            }
        }
    }

    private suspend fun currentDb(): Database {
        val loggedIn = auth.state.value as? AuthState.LoggedIn ?: throw SessionExpired()
        mutex.withLock {
            val existing = database
            if (existing != null && openedUserId == loggedIn.userId) return existing
            closeCurrentLocked()
            val token = auth.ensureFreshToken()
            val db = dbFactory(loggedIn.userId, token.dbUrl, token.jwt)
            database = db
            openedUserId = loggedIn.userId
            return db
        }
    }

    private suspend fun closeCurrent() {
        mutex.withLock { closeCurrentLocked() }
    }

    private fun closeCurrentLocked() {
        try {
            database?.close()
        } catch (_: Exception) {
        }
        database = null
        openedUserId = null
    }

    private fun isAuthError(e: Throwable): Boolean {
        val message = ((e.message ?: "") + " " + (e.cause?.message ?: "")).lowercase()
        return "401" in message || "unauthorized" in message || "authentication" in message
    }
}
