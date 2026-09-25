package ar.fausto.weil

import kotlinx.serialization.json.Json

/*
 * The database side of broker imports (plans/inversiones-brokers.md): what
 * the pure planner needs to read — balances, positions with their cost,
 * refs already booked, commodity scales — and the one write, applying a
 * reviewed plan. Broker-agnostic; IolRepository and the IBKR importer sit on
 * top of it.
 */

/** Synced setting holding a provider's [BrokerAccounts] as JSON. */
fun brokerAccountsKey(provider: String) = "broker.$provider.accounts"

class BrokersRepository(
    private val db: DatabaseProvider,
    private val accounts: AccountsRepository,
    private val ledger: TransactionsRepository,
    private val settings: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The accounts [provider] books into, or null when it was never connected. */
    suspend fun accountsFor(provider: String): BrokerAccounts? =
        settings.all()[brokerAccountsKey(provider)]?.let {
            runCatching { json.decodeFromString(BrokerAccounts.serializer(), it) }.getOrNull()
        }

    /**
     * Creates (or finds, by name) the accounts a broker books into and
     * remembers their ids (plan decision 7). Idempotent: connecting again,
     * or from a second device, reuses what is there — the income, expense
     * and equity branches are shared by every broker.
     */
    suspend fun connect(provider: String, label: String, currencies: List<String>): BrokerAccounts {
        accountsFor(provider)?.let { existing ->
            val ids = accounts.tree().flatMap { it.selfAndDescendants }.map { it.account.id }.toSet()
            if (existing.holdings in ids) return existing
        }
        val root = findOrCreate(label, AccountType.Asset, null)
        val cash = currencies.associateWith { currency ->
            findOrCreate(cashAccountName(currency), AccountType.Asset, root)
        }
        val holdings = findOrCreate("Cartera", AccountType.Asset, root)
        // Distinct root names on purpose: a root name is unique across all
        // five types (AccountsRepository.requireNameFree), so "Inversiones"
        // can't be both the income and the expense branch.
        val income = findOrCreate("Rendimientos", AccountType.Income, null)
        val expense = findOrCreate("Costos de inversión", AccountType.Expense, null)
        val equity = findOrCreate("Patrimonio", AccountType.Equity, null)
        val result = BrokerAccounts(
            cash = cash,
            holdings = holdings,
            interest = findOrCreate("Intereses", AccountType.Income, income),
            dividends = findOrCreate("Dividendos", AccountType.Income, income),
            capitalGains = findOrCreate("Ganancias de capital", AccountType.Income, income),
            taxes = findOrCreate("Impuestos", AccountType.Expense, expense),
            commissions = findOrCreate("Comisiones", AccountType.Expense, expense),
            opening = findOrCreate("Saldo inicial", AccountType.Equity, equity),
            adjustments = findOrCreate("Ajustes", AccountType.Equity, equity),
        )
        settings.set(brokerAccountsKey(provider), json.encodeToString(BrokerAccounts.serializer(), result))
        return result
    }

    /** Balances before a batch: each cash account in its currency, and the holdings with their cost. */
    suspend fun ledgerView(broker: BrokerAccounts): BrokerLedgerView {
        val balances = ledger.leafBalances()
        return BrokerLedgerView(
            cash = broker.cash.mapValues { (currency, account) -> balances[account]?.get(currency) ?: 0L },
            holdings = ledger.holdings(broker.holdings),
        )
    }

    /** Namespaced refs ("iol:…") of [provider] already booked. */
    suspend fun knownRefs(provider: String): Set<String> = db.useForRead { d ->
        d.query(
            "select ref from transaction_sources where kind = :kind and ref like :prefix",
            mapOf(":kind" to EventSource.Broker.db, ":prefix" to "$provider:%"),
        ) { rows -> rows.mapNotNull { it.firstOrNull()?.toString() }.toSet() }
    }

    /**
     * What every balance on screen is valued with: the described commodities
     * and the latest price of each (in the commodity's quote currency when
     * it has one, else whichever quote is newest).
     */
    suspend fun valuation(): Valuation = db.useForRead { d ->
        val commodities = d.query(
            "select id, symbol, name, kind, scale, price_per, quote_commodity from commodities",
            null,
        ) { rows ->
            rows.mapNotNull { row ->
                val id = row.getOrNull(0)?.toString() ?: return@mapNotNull null
                InstrumentInfo(
                    id = id,
                    symbol = row.getOrNull(1)?.toString() ?: id,
                    name = row.getOrNull(2)?.toString(),
                    kind = row.getOrNull(3)?.toString() ?: "other",
                    scale = (row.getOrNull(4) as? Number)?.toInt() ?: 2,
                    pricePer = (row.getOrNull(5) as? Number)?.toInt() ?: 1,
                    quoteCommodity = row.getOrNull(6)?.toString(),
                )
            }.toList().associateBy { it.id }
        }
        val latest = mutableMapOf<String, PriceQuote>()
        d.query("select commodity, quote_commodity, at, price, source from prices order by at desc", null) { rows ->
            for (row in rows) {
                val commodity = row.getOrNull(0)?.toString() ?: continue
                val quote = row.getOrNull(1)?.toString() ?: continue
                val price = Decimal.parse(row.getOrNull(3)?.toString().orEmpty()) ?: continue
                val preferred = commodities[commodity]?.quoteCommodity
                val current = latest[commodity]
                // Newest first: keep the first row, unless a later one is in
                // the preferred quote currency and the kept one isn't.
                if (current == null || (current.quoteCommodity != preferred && quote == preferred)) {
                    latest[commodity] = PriceQuote(
                        commodity, quote, (row.getOrNull(2) as? Number)?.toLong() ?: 0L, price,
                        row.getOrNull(4)?.toString().orEmpty(),
                    )
                }
            }
        }
        Valuation(commodities, latest, d.officialRate())
    }

    /** Decimals of every commodity the ledger describes. */
    suspend fun scales(): Map<String, Int> = db.useForRead { d ->
        d.query("select id, scale from commodities", null) { rows ->
            rows.mapNotNull { row ->
                val id = row.getOrNull(0)?.toString() ?: return@mapNotNull null
                val scale = (row.getOrNull(1) as? Number)?.toInt() ?: return@mapNotNull null
                id to scale
            }.toList().toMap()
        }
    }

    /**
     * Writes [selected] (by default the whole plan) plus the plan's new
     * commodities and prices. Commodities go first — a posting in a
     * commodity with no row would be read at 2 decimals — and both are
     * idempotent (`insert or ignore` / deterministic price ids), so a
     * retry after a failure halfway writes nothing twice. The transactions
     * are one SQL transaction (`addAll`). Returns their ids, for undo.
     */
    /** Upserts [prices] (deterministic ids: a day's second fetch replaces the first). */
    suspend fun savePrices(prices: List<PriceQuote>) {
        if (prices.isEmpty()) return
        db.use { d -> for (price in prices) d.upsertPrice(price) }
    }

    /** Newest official dollar rate (see [OFFICIAL_SOURCE]), or null. */
    suspend fun latestOfficialRate(): PriceQuote? = db.useForRead { d -> d.officialRate() }

    suspend fun apply(plan: BrokerPlan, selected: List<PlannedTransaction> = plan.transactions): List<String> {
        db.use { d ->
            for (commodity in plan.newCommodities) d.insertCommodity(commodity)
            for (price in plan.prices) d.upsertPrice(price)
        }
        return ledger.addAll(selected.map { it.transaction })
    }

    /**
     * Settles a cash [difference] against the opening balance: what the
     * broker holds minus what the ledger holds, booked between the broker's
     * cash account and `Patrimonio:Saldo inicial`.
     *
     * The opening of an account whose deposits the broker never reports is
     * a remainder (see planBrokerImport's opening), and every bank transfer
     * the user records afterwards moves it; this is the one-tap way to fix it
     * once the history is in. A separate transaction rather than an edit of
     * the opening, so it reads as what it is and undoes cleanly. Dated just
     * before the oldest movement of the broker's cash, which is where an
     * opening belongs. Returns the new transaction's id.
     *
     * Cash only: a quantity that disagrees is a split, a transfer between
     * brokers or a missing trade, and money is not how any of those is fixed.
     */
    suspend fun adjustOpening(broker: BrokerAccounts, difference: BalanceDifference, payee: String): String {
        require(difference.accountId in broker.cash.values) { "only cash differences can be adjusted" }
        require(difference.deltaMinor != 0L) { "nothing to adjust" }
        val date = (ledger.earliestDate(broker.cash.values.toList()) ?: epochMillis()) - 1
        val delta = difference.deltaMinor
        return ledger.add(
            date = date,
            payee = payee,
            note = null,
            drafts = listOf(
                DraftPosting(difference.accountId, formatMinorUnits(delta), difference.commodity),
                DraftPosting(broker.opening, formatMinorUnits(-delta), difference.commodity),
            ),
            timeKnown = false,
        )
    }

    private suspend fun findOrCreate(
        name: String,
        type: AccountType,
        parentId: String?,
    ): String {
        val existing = accounts.tree().flatMap { it.selfAndDescendants }.firstOrNull {
            it.account.parentId == parentId && it.account.type == type && it.account.name.equals(name, ignoreCase = true)
        }
        return existing?.account?.id ?: accounts.add(name, type, parentId)
    }

    private fun cashAccountName(currency: String): String = when (currency) {
        "ARS" -> "Pesos"
        "USD" -> "Dólares"
        else -> currency
    }
}

/** Only the columns that have a value: an unbound named parameter binds nothing. */
private fun Database.insertCommodity(c: InstrumentInfo) {
    val values = linkedMapOf<String, Any>(
        "id" to c.id,
        "symbol" to c.symbol,
        "kind" to c.kind,
        "scale" to c.scale.toLong(),
        "price_per" to c.pricePer.toLong(),
    )
    c.name?.let { values["name"] = it }
    c.quoteCommodity?.let { values["quote_commodity"] = it }
    c.isin?.let { values["isin"] = it }
    c.conid?.let { values["conid"] = it }
    execute(
        "insert or ignore into commodities(${values.keys.joinToString(", ")})" +
            " values(${values.keys.joinToString(", ") { ":$it" }})",
        values.mapKeys { ":${it.key}" },
    )
}

private fun Database.officialRate(): PriceQuote? = query(
    "select at, price from prices where commodity = 'USD' and quote_commodity = 'ARS'" +
        " and source = :source order by at desc limit 1",
    mapOf(":source" to OFFICIAL_SOURCE),
) { rows ->
    rows.firstOrNull()?.let { row ->
        val price = Decimal.parse(row.getOrNull(1)?.toString().orEmpty()) ?: return@let null
        PriceQuote("USD", "ARS", (row.getOrNull(0) as? Number)?.toLong() ?: 0L, price, OFFICIAL_SOURCE)
    }
}

/**
 * One row per commodity, quote, UTC day and source: a second sync on the
 * same day replaces the price instead of piling up rows, and two devices
 * writing the same day converge on one id.
 */
private fun Database.upsertPrice(p: PriceQuote) {
    val day = p.at / (24L * 60 * 60 * 1000)
    execute(
        "insert or replace into prices(id, commodity, quote_commodity, at, price, source)" +
            " values(:id, :commodity, :quote, :at, :price, :source)",
        mapOf(
            ":id" to "${p.commodity}|${p.quoteCommodity}|$day|${p.source}",
            ":commodity" to p.commodity,
            ":quote" to p.quoteCommodity,
            ":at" to p.at,
            ":price" to p.price.toPlainString(),
            ":source" to p.source,
        ),
    )
}
