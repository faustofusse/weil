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
) {
    private val mutex = Mutex()
    private var database: Database? = null
    private var openedUserId: String? = null

    suspend fun <T> use(block: (Database) -> T): T {
        var attempt = 0
        while (true) {
            val db = currentDb()
            try {
                return withContext(dbContext) { block(db) }
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
