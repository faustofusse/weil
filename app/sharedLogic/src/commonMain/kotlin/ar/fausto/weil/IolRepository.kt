package ar.fausto.weil

/**
 * InvertirOnline end to end (plans/inversiones-brokers.md, phase 4):
 * credentials in the device's secure store (decision 1: never synced, never
 * on the server), fetch through the read-only [IolClient], translate with
 * [iolBatch], plan with [planBrokerImport], and — after review — write with
 * [BrokersRepository.apply].
 *
 * The API window is the whole history on the first import (IOL answered
 * about a year for the real account) and, afterwards, from the last sync
 * minus a margin: refs dedupe the overlap, and the margin covers orders that
 * settle or get corrected a few days after they were placed.
 */
class IolRepository(
    private val store: SecureStore,
    private val brokers: BrokersRepository,
    private val settings: SettingsRepository,
    client: IolSource? = null,
) {
    private val client: IolSource = client ?: IolClient({ credentials() })

    /** The stored username, for the UI; the password never leaves the store. */
    val username: String? get() = store.read(USERNAME_KEY)

    val hasCredentials: Boolean get() = credentials() != null

    /**
     * Checks the credentials against IOL, then keeps them and makes sure the
     * accounts exist. Throws [IolAuthException] for a wrong password, before
     * anything is stored.
     */
    suspend fun connect(username: String, password: String): BrokerAccounts {
        val candidate = IolCredentials(username.trim(), password)
        client.verify(candidate)
        store.write(USERNAME_KEY, candidate.username)
        store.write(PASSWORD_KEY, candidate.password)
        return brokers.connect(IOL_PROVIDER, "IOL", listOf("ARS", "USD"))
    }

    /**
     * Forgets the credentials on this device. The accounts and everything
     * imported stay: disconnecting stops syncing, it doesn't rewrite history.
     */
    fun disconnect() {
        store.write(USERNAME_KEY, null)
        store.write(PASSWORD_KEY, null)
    }

    /** Fetches and plans; writes nothing. */
    suspend fun preview(): BrokerPlan {
        val accounts = brokers.accountsFor(IOL_PROVIDER)
            ?: brokers.connect(IOL_PROVIDER, "IOL", listOf("ARS", "USD"))
        val known = brokers.knownRefs(IOL_PROVIDER)
        val batch = iolBatch(fetch(known))
        return planBrokerImport(batch, accounts, brokers.ledgerView(accounts), known, brokers.scales())
    }

    /** Writes the reviewed plan and moves the sync window forward. Returns the new ids, for undo. */
    suspend fun apply(plan: BrokerPlan, selected: List<PlannedTransaction> = plan.transactions): List<String> {
        val ids = brokers.apply(plan, selected)
        settings.set(SYNCED_AT_KEY, epochMillis().toString())
        return ids
    }

    private suspend fun fetch(known: Set<String>): IolFetch {
        val now = epochMillis()
        val syncedAt = settings.all()[SYNCED_AT_KEY]?.toLongOrNull()
        val from = if (syncedAt == null) FIRST_DAY else iolDate(syncedAt - WINDOW_MARGIN_MS)
        val to = iolDate(now + DAY_MS)
        val operations = client.operations(from, to)
        val state = client.accountState()
        val portfolios = listOf(client.portfolio("argentina"), client.portfolio("estados_Unidos"))
        // One request per new order: only these carry currency and fees.
        val details = iolDetailsNeeded(operations, known).associateWith { client.operation(it) }
        // Symbols traded but no longer held: their type decides scale and face value.
        val instruments = iolSymbolsNeeded(operations, portfolios).mapNotNull { (market, symbol) ->
            runCatching { symbol to client.instrument(market, symbol) }.getOrNull()
        }.toMap()
        return IolFetch(now, state, portfolios, operations, details, instruments)
    }

    private fun credentials(): IolCredentials? {
        val username = store.read(USERNAME_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val password = store.read(PASSWORD_KEY)?.takeIf { it.isNotEmpty() } ?: return null
        return IolCredentials(username, password)
    }

    companion object {
        /** Secure-store keys (device-local, not the synced settings table). */
        const val USERNAME_KEY = "iol.username"
        const val PASSWORD_KEY = "iol.password"

        /** Synced: a second device resumes from where the first one left. */
        const val SYNCED_AT_KEY = "broker.iol.synced_at"

        /** Far enough back to be "everything IOL keeps". */
        private const val FIRST_DAY = "2000-01-01"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val WINDOW_MARGIN_MS = 10 * DAY_MS
    }
}

/** Epoch ms → "yyyy-MM-dd" in Argentina's time (UTC−3), IOL's filter format. */
internal fun iolDate(epochMillis: Long): String {
    val days = (epochMillis - 3 * 3600 * 1000L).floorDiv(24L * 3600 * 1000)
    // Inverse of daysFromCivil (Howard Hinnant's civil_from_days).
    val z = days + 719468
    val era = z.floorDiv(146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + (if (month <= 2) 1 else 0)
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}
