package ar.fausto.weil

import java.io.File
import java.util.Properties
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Desktop dev harness (Phase 1 of docs/desktop-target-plan.md): no real
 * passkeys / Turso sync engine exist on the JVM target yet, so this wires a
 * local file-backed [SecureStore] pre-seeded with a fake logged-in session
 * and a SQLite-backed [Database] instead. Swap this out once Phase 2/3 land.
 */

/** Same rationale as Android's dbDispatcher: the Rust SQL parser (and, here,
 * plain JDBC too, for parity) gets its own thread with a generous stack. */
val jvmDbDispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "weil-db", 16L * 1024 * 1024).apply { isDaemon = true }
    }.asCoroutineDispatcher()

private const val DEV_USER_ID = "dev-user"
private const val DEV_JWT = "dev-jwt"
private const val DEV_DB_URL = "fake://dev"

/**
 * Properties-file store at ~/.weil/dev-store.properties. Plaintext, so it is
 * a dev convenience, not a secure store — it holds a real refresh cookie and
 * Turso JWT once you sign in. Delete the file to sign out completely.
 *
 * With [seedDevSession] it instead pre-seeds a fake logged-in session so the
 * UI renders offline against [FakeDatabase] with no auth at all.
 */
class JvmSecureStore(
    private val file: File = File(System.getProperty("user.home"), ".weil/dev-store.properties"),
    seedDevSession: Boolean = false,
) : SecureStore {
    private val props = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    init {
        if (seedDevSession) {
            if (props.getProperty("user_id") == null) {
                props.setProperty("user_id", DEV_USER_ID)
                props.setProperty("known_user", DEV_USER_ID)
                props.setProperty("jwt", DEV_JWT)
                props.setProperty("db_url", DEV_DB_URL)
                props.setProperty("expires_at", Long.MAX_VALUE.toString())
                persist()
            }
        } else if (isDevSession) {
            // Left over from a previous `-Dweil.mode=fake` run: its fake jwt /
            // db_url would restore() into a bogus LoggedIn state and be handed
            // to HttpDatabase. Drop it so real auth starts from LoggedOut.
            listOf("user_id", "known_user", "jwt", "db_url", "db_hostname", "expires_at")
                .forEach { props.remove(it) }
            persist()
        }
    }

    /** True when this is the pre-seeded fake session, not a hand-placed real one. */
    val isDevSession: Boolean get() = props.getProperty("user_id") == DEV_USER_ID

    override fun read(key: String): String? = props.getProperty(key)

    override fun write(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        persist()
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "weil desktop dev store") }
    }
}

/** Offline-mode stub: in `-Dweil.mode=fake` the store is pre-seeded as
 * already logged in, so no ceremony should ever run. Real auth uses
 * [BrowserPasskeys]. */
class JvmDevPasskeys : PasskeyCeremony {
    override suspend fun create(optionsJson: String): String = throw PasskeyNotFound()
    override suspend fun assert(optionsJson: String): String = throw PasskeyNotFound()
}
