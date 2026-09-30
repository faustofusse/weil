package ar.fausto.weil

/*
 * Broker import, the pure half (plans/inversiones-brokers.md, "El modelo
 * común" and phase 2). Every broker — IOL's API, an IBKR Flex report, a
 * custody statement — is translated by its connector into one BrokerBatch
 * of source-agnostic events plus an optional snapshot, and planBrokerImport
 * turns that into balanced transactions, prices, and the differences between
 * what the broker says and what the ledger would hold. Nothing here does I/O
 * and nothing here writes: the review screen does, like every other import.
 *
 * Accounting rules, following ledger-cli (see the plan's decisions):
 *  - an instrument is a commodity; a buy is an exchange with a cost (`@@`);
 *  - a broker that itemizes its charges (IOL: commission, market rights,
 *    IVA) gets each one expensed to its own account and the units at their
 *    price, which is also the broker's average price; otherwise
 *  - **commissions are capitalized**: a buy costs gross + fees, a sale
 *    brings in gross − fees. That is the basis brokers (IBKR's `Cost Basis`)
 *    and AFIP use, and it is what makes the broker's realized gain reproduce
 *    exactly instead of counting the commission twice. The fee stays visible
 *    in the transaction's note and, on a buy, in the posting's `fee_minor`,
 *    which the investments tab leaves out of the unrealized gain (the way a
 *    broker's average price does);
 *  - a sale leaves the holdings at its cost basis: the broker's when it
 *    reports one (IBKR, FIFO), else the ledger's average cost (IOL, custody
 *    statements). The difference against the net proceeds is the realized
 *    gain;
 *  - market value is never booked: prices go to the prices table;
 *  - the snapshot is an assertion, never an adjustment written behind the
 *    user's back: differences come back as data for the review screen.
 */

/** An instrument as a broker describes it; becomes a `commodities` row. */
data class InstrumentInfo(
    /** Market-scoped id, the commodity code on postings: 'BCBA:MELI'. */
    val id: String,
    val symbol: String,
    val name: String? = null,
    val kind: String,
    /** Decimals of the quantity: posting amount = quantity × 10^scale. */
    val scale: Int,
    /** Face value a quote refers to: 100 for bonds and letras, else 1. */
    val pricePer: Int = 1,
    val quoteCommodity: String? = null,
    val isin: String? = null,
    val conid: String? = null,
)

/** One price, as the prices table stores it (price per [InstrumentInfo.pricePer]). */
data class PriceQuote(
    val commodity: String,
    val quoteCommodity: String,
    val at: Long,
    val price: Decimal,
    val source: String,
)

enum class IncomeKind { Dividend, Interest }

/**
 * One itemized charge, for a transaction's note: "Comisión" $ 1.585,35 +
 * IVA $ 332,92. Informational; the amounts that are booked are the fees.
 */
data class FeeItem(val label: String, val net: Decimal, val vat: Decimal, val commodity: String)

/**
 * One movement at a broker. Amounts are [Decimal] in the commodity's own
 * units (dollars, shares), as the broker states them; the planner converts
 * to minor units with each commodity's scale. [ref] is unique per provider
 * (IOL's order number, IBKR's transactionID).
 */
sealed interface BrokerEvent {
    val ref: String
    val at: Long
    val timeKnown: Boolean
    /** Payee of the transaction: "Compra TTWO", "Dividendo AO27". */
    val description: String

    /**
     * A buy (quantity > 0) or a sale (quantity < 0).
     *
     * [gross] is the trade value without fees, as a positive magnitude, in
     * [cashCommodity]; [fees] are positive, in the same commodity (a fee in
     * another currency is the connector's to convert or report).
     * [costBasis] is the broker's cost of what a sale closes (positive,
     * commissions included), when it reports one.
     *
     * [foreignFees] are charges in *other* currencies (IOL bills a dollar
     * trade's market fee in pesos). They can't be part of a cost in
     * [cashCommodity], so they are expensed from that currency's cash.
     */
    data class Trade(
        override val ref: String,
        override val at: Long,
        override val timeKnown: Boolean,
        override val description: String,
        val instrument: String,
        val quantity: Decimal,
        val gross: Decimal,
        val cashCommodity: String,
        val fees: Decimal = Decimal.ZERO,
        val costBasis: Decimal? = null,
        val foreignFees: Map<String, Decimal> = emptyMap(),
        /** [fees] + [foreignFees] itemized, when the broker says what they are. */
        val feeItems: List<FeeItem> = emptyList(),
    ) : BrokerEvent

    /** Dividend or interest: [gross] before [tax] withheld, both positive. */
    data class Income(
        override val ref: String,
        override val at: Long,
        override val timeKnown: Boolean,
        override val description: String,
        val kind: IncomeKind,
        val gross: Decimal,
        val cashCommodity: String,
        val tax: Decimal = Decimal.ZERO,
        val instrument: String? = null,
        /** Charges on the payment (IOL bills a coupon's commission in pesos), expensed from that currency's cash. */
        val fees: Map<String, Decimal> = emptyMap(),
        val feeItems: List<FeeItem> = emptyList(),
    ) : BrokerEvent

    /**
     * Part of a bond paid back: [quantity] units (positive) leave, [cash]
     * arrives. Books like a sale without fees, at average cost. A null
     * [quantity] is a redemption of the whole position — what a letra's
     * maturity is, and all IOL states exactly (its own text rounds the
     * count: "-2,16832e+006" for 2.168.316).
     */
    data class Principal(
        override val ref: String,
        override val at: Long,
        override val timeKnown: Boolean,
        override val description: String,
        val instrument: String,
        val quantity: Decimal?,
        val cash: Decimal,
        val cashCommodity: String,
        /** Charges on the payment, expensed from that currency's cash. */
        val fees: Map<String, Decimal> = emptyMap(),
        val feeItems: List<FeeItem> = emptyList(),
    ) : BrokerEvent

    /**
     * Money in (+) or out (−) of the broker. The other side is an account the
     * broker never names (IBKR's Flex has no sender field): the planner books
     * it against the opening balance and flags it, and the review screen lets
     * the user point it at the account the money really came from or went to
     * ([PlannedTransaction.withCounterpart]).
     */
    data class CashTransfer(
        override val ref: String,
        override val at: Long,
        override val timeKnown: Boolean,
        override val description: String,
        val amount: Decimal,
        val cashCommodity: String,
    ) : BrokerEvent

    /** [from] (positive) of one currency became [to] (positive) of another; [fees] in [fromCommodity]. */
    data class FxConversion(
        override val ref: String,
        override val at: Long,
        override val timeKnown: Boolean,
        override val description: String,
        val from: Decimal,
        val fromCommodity: String,
        val to: Decimal,
        val toCommodity: String,
        val fees: Decimal = Decimal.ZERO,
        /** The charges itemized (both orders of an IOL pair); those in [fromCommodity] split [fees] by type. */
        val feeItems: List<FeeItem> = emptyList(),
    ) : BrokerEvent

    /** A split, a spin-off, a correction: units appear or vanish, cost untouched. */
    data class QuantityChange(
        override val ref: String,
        override val at: Long,
        override val timeKnown: Boolean,
        override val description: String,
        val instrument: String,
        val delta: Decimal,
        /** Why it is there, when the planner deduced it (the transaction's note). */
        val note: String? = null,
    ) : BrokerEvent
}

/** What the broker says the account holds at [at]. */
data class BrokerSnapshot(
    val at: Long,
    /** Cash per currency, in the currency's units. */
    val cash: Map<String, Decimal>,
    val positions: List<SnapshotPosition>,
)

data class SnapshotPosition(
    val instrument: String,
    val quantity: Decimal,
    /** Last price, per [InstrumentInfo.pricePer], in the instrument's quote currency. */
    val price: Decimal? = null,
    /** Total cost of the position (positive), when the broker reports it. */
    val cost: Decimal? = null,
    val costCommodity: String? = null,
)

/** Everything one connector run produced. */
data class BrokerBatch(
    /** Provider id and the prefix of every ref: "iol", "ibkr". */
    val provider: String,
    val events: List<BrokerEvent>,
    val snapshot: BrokerSnapshot? = null,
    val instruments: List<InstrumentInfo> = emptyList(),
    val prices: List<PriceQuote> = emptyList(),
    /** What the connector itself couldn't translate; passed through as plan issues. */
    val notes: List<PlanIssue> = emptyList(),
    /**
     * Start of the period the batch reports on, when the source states one
     * (an IBKR statement's fromDate). The opening is dated there: it is what
     * the account held when the report starts, not a minute before its first
     * trade, or the register would show the account empty until then.
     */
    val since: Long? = null,
)

/**
 * The accounts a broker connection books into (plan decision 7), created
 * once when the broker is connected. Ids, not paths: a rename breaks nothing.
 * Stored as JSON in the synced `broker.<provider>.accounts` setting.
 */
@kotlinx.serialization.Serializable
data class BrokerAccounts(
    /** Cash account per currency: "ARS" → Activos:IOL:Pesos. */
    val cash: Map<String, String>,
    /** The multi-commodity Cartera account (plan question 3). */
    val holdings: String,
    val interest: String,
    val dividends: String,
    val capitalGains: String,
    val taxes: String,
    /**
     * Commissions that are expensed: FX fees, a coupon's commission, and a
     * broker's itemized commission ([FeeItem]); an un-itemized trade fee is
     * capitalized and never lands here.
     */
    val commissions: String,
    val opening: String,
    val adjustments: String,
    /** IVA on itemized charges. Null on connections made before it existed ([BrokersRepository.connect] adds it). */
    val vat: String? = null,
    /** The market's fee ("Derechos de mercado") of itemized charges; null like [vat]. */
    val marketFees: String? = null,
)

/** A holding as the ledger has it: quantity and total cost, both minor units. */
data class HeldPosition(
    val quantityMinor: Long,
    val costMinor: Long,
    val costCommodity: String?,
)

/** The ledger side the planner compares against (balances before this batch). */
data class BrokerLedgerView(
    /** Balance of each cash account, by currency, minor units. */
    val cash: Map<String, Long> = emptyMap(),
    /** Holdings account, by commodity. */
    val holdings: Map<String, HeldPosition> = emptyMap(),
) {
    /** Nothing booked for this broker yet: the batch has to open the account. */
    val isEmpty: Boolean
        get() = cash.values.all { it == 0L } && holdings.values.all { it.quantityMinor == 0L }
}

enum class PlannedKind { Opening, Trade, Income, Principal, Transfer, Fx, QuantityChange }

data class PlannedTransaction(
    val kind: PlannedKind,
    val transaction: NewTransaction,
    /** The namespaced source ref ("ibkr:42307900416"); null for the opening. */
    val ref: String?,
    /**
     * A deposit or withdrawal whose other side is the fallback
     * ([BrokerAccounts.opening]): the broker doesn't say which account the
     * money came from, so a person should.
     */
    val needsCounterpart: Boolean = false,
    /**
     * Deduced by the planner rather than reported by the broker (a split
     * read off the snapshot): shown in the review, never written unattended
     * ([isRoutine]).
     */
    val inferred: Boolean = false,
) {
    /**
     * The same transaction with its [fallback] leg (the opening balance)
     * moved to [accountId]: the bank account a deposit came from, or the one
     * a withdrawal went to. Amounts, dates and sources are untouched; a
     * transaction that doesn't need a counterpart is returned as is.
     */
    fun withCounterpart(fallback: String, accountId: String): PlannedTransaction {
        if (!needsCounterpart || accountId == fallback) return this
        return copy(
            transaction = transaction.copy(
                drafts = transaction.drafts.map { if (it.accountId == fallback) it.copy(accountId = accountId) else it },
            ),
        )
    }
}

/** An event the planner could not turn into a transaction, and why (developer text). */
data class PlanIssue(val ref: String?, val message: String)

/**
 * Ledger vs broker for one account and commodity, after this plan is
 * applied. Differences are reported, never written: the review screen
 * decides (match a bank movement, or propose an adjustment the user sees).
 */
data class BalanceDifference(
    val accountId: String,
    val commodity: String,
    val ledgerMinor: Long,
    val brokerMinor: Long,
) {
    val deltaMinor: Long get() = brokerMinor - ledgerMinor
}

data class BrokerPlan(
    val transactions: List<PlannedTransaction>,
    /** Instruments the ledger doesn't know yet, to insert before the transactions. */
    val newCommodities: List<InstrumentInfo>,
    val prices: List<PriceQuote>,
    val differences: List<BalanceDifference>,
    val issues: List<PlanIssue>,
    /** Refs already booked (in transaction_sources), left alone. */
    val skippedRefs: List<String>,
    /**
     * Transactions this plan supersedes: a rebuilt import (see
     * [planBrokerImport]'s `rebuild`) deletes them in the same SQL
     * transaction that writes [transactions].
     */
    val replaces: List<String> = emptyList(),
)

/**
 * The trading lines of one security, line → the security's own id.
 *
 * BYMA lists a security on several lines: `AAPL` in pesos, `AAPLD` in MEP
 * dollars, `AAPLC` in cable dollars (an ON's peso line ends in `O`:
 * `AEC1O`/`AEC1D`). An order names the line it traded on, but custody is per
 * security, so a broker's portfolio reports the holding under the peso
 * ticker only: 8 `AAPLD` bought with dollars show up there as 8 more `AAPL`.
 * Each line stays its own commodity (its cost is in its own currency), and
 * everything that compares or draws down a *holding* — the opening, the
 * differences, a sale on another line, a redemption — goes through this map.
 *
 * A line is a BCBA instrument quoted in dollars whose code is another
 * known, non-dollar BCBA instrument's plus `D` or `C`. The dollar quote is
 * what keeps `YPFD` (YPF's peso ticker) from being read as a line of `YPF`.
 */
fun tradingLines(instruments: Collection<InstrumentInfo>): Map<String, String> {
    val byId = instruments.associateBy { it.id }
    val result = mutableMapOf<String, String>()
    for (info in instruments) {
        if (!info.id.startsWith(BYMA_PREFIX) || info.quoteCommodity != "USD") continue
        val code = info.id.removePrefix(BYMA_PREFIX)
        if (code.length < 3 || (code.last() != 'D' && code.last() != 'C')) continue
        val stem = code.dropLast(1)
        val candidates = buildList {
            add(BYMA_PREFIX + stem)
            if (info.kind == "on") add(BYMA_PREFIX + stem + "O")
        }
        val base = candidates.firstOrNull { id -> byId[id]?.let { it.quoteCommodity != "USD" } == true } ?: continue
        result[info.id] = base
    }
    return result
}

private const val BYMA_PREFIX = "BCBA:"

/** The ref as stored in `transaction_sources`: provider-scoped. */
fun brokerRef(provider: String, ref: String): String = "$provider:$ref"

/**
 * Turns a [batch] into a [BrokerPlan].
 *
 * [knownRefs] are the namespaced refs already in `transaction_sources`
 * (see [brokerRef]); [scales] the decimals of commodities the ledger already
 * knows (a commodity nobody describes keeps 2, like everywhere else);
 * [lines] the trading lines of one security ([tradingLines] over the
 * ledger's commodities and the batch's).
 *
 * [rebuild] plans the opening even though the ledger is not empty: the
 * caller left the broker's own transactions out of [ledger] because the
 * plan replaces them, and what remains (a bank transfer typed by hand) is
 * subtracted from the opening instead of counted twice.
 */
fun planBrokerImport(
    batch: BrokerBatch,
    accounts: BrokerAccounts,
    ledger: BrokerLedgerView,
    knownRefs: Set<String>,
    scales: Map<String, Int> = emptyMap(),
    lines: Map<String, String> = tradingLines(batch.instruments),
    rebuild: Boolean = false,
): BrokerPlan = BrokerPlanner(batch, accounts, ledger, knownRefs, scales, lines, rebuild).plan()

private class BrokerPlanner(
    val batch: BrokerBatch,
    val accounts: BrokerAccounts,
    val ledger: BrokerLedgerView,
    val knownRefs: Set<String>,
    knownScales: Map<String, Int>,
    val lines: Map<String, String>,
    val rebuild: Boolean,
) {
    private val scales: Map<String, Int> = knownScales + batch.instruments.associate { it.id to it.scale }

    /** The security a trading line belongs to (see [tradingLines]); itself for anything else. */
    fun speciesOf(instrument: String): String = lines[instrument] ?: instrument
    private val cash: MutableMap<String, Long> = ledger.cash.toMutableMap()
    private val holdings: MutableMap<String, HeldPosition> = ledger.holdings.toMutableMap()
    private val planned = mutableListOf<PlannedTransaction>()
    private val issues = batch.notes.toMutableList()

    fun scaleOf(commodity: String): Int = scales[commodity] ?: 2

    fun minor(value: Decimal, commodity: String): Long = value.toMinorUnits(scaleOf(commodity))

    /**
     * A quantity in minor units, exactly: money may round to the cent, a
     * quantity may not. 0,5 of something whose scale is 0 means the scale is
     * wrong, and rounding it would book a phantom unit.
     */
    fun quantityMinor(value: Decimal, instrument: String): Long {
        val scale = scaleOf(instrument)
        if (value.rescale(scale) != value) {
            throw PlanException("quantity ${value.toPlainString()} has more decimals than $instrument's scale ($scale)")
        }
        return value.toMinorUnits(scale)
    }

    fun plan(): BrokerPlan {
        val (fresh, known) = batch.events
            .sortedWith(compareBy<BrokerEvent>({ it.at }, { it.ref }))
            .partition { brokerRef(batch.provider, it.ref) !in knownRefs }

        val splits = batch.snapshot?.let { inferSplits(fresh, it) }.orEmpty()
        val events = if (splits.isEmpty()) fresh else (fresh + splits).sortedWith(compareBy<BrokerEvent>({ it.at }, { it.ref }))

        if ((ledger.isEmpty || rebuild) && batch.snapshot != null) planOpening(events, batch.snapshot)

        for (event in events) {
            val ref = brokerRef(batch.provider, event.ref)
            try {
                planEvent(event, ref)
            } catch (e: PlanException) {
                issues += PlanIssue(ref, e.message ?: "unplannable")
            } catch (e: ArithmeticException) {
                issues += PlanIssue(ref, "amount out of range: ${e.message}")
            }
        }
        if (splits.isNotEmpty()) {
            val inferred = splits.map { brokerRef(batch.provider, it.ref) }.toSet()
            planned.replaceAll { if (it.ref in inferred) it.copy(inferred = true) else it }
        }
        batch.snapshot?.let { reportHiddenSplits(it) }

        return BrokerPlan(
            transactions = planned,
            newCommodities = batch.instruments.filter { it.id !in ledgerScalesKnown },
            prices = prices(),
            differences = differences(),
            issues = issues,
            skippedRefs = known.map { brokerRef(batch.provider, it.ref) },
        )
    }

    /** Commodities the ledger had before this batch (not the batch's own). */
    private val ledgerScalesKnown: Set<String> = knownScales.keys

    // --- events ---------------------------------------------------------

    private fun planEvent(event: BrokerEvent, ref: String) {
        when (event) {
            is BrokerEvent.Trade -> if (event.quantity.signum > 0) planBuy(event, ref) else planSale(event, ref)
            is BrokerEvent.Income -> planIncome(event, ref)
            is BrokerEvent.Principal -> planPrincipal(event, ref)
            is BrokerEvent.CashTransfer -> planTransfer(event, ref)
            is BrokerEvent.FxConversion -> planFx(event, ref)
            is BrokerEvent.QuantityChange -> planQuantityChange(event, ref)
        }
    }

    private fun planBuy(e: BrokerEvent.Trade, ref: String) {
        if (e.quantity.isZero) throw PlanException("zero quantity")
        val cashAccount = cashAccount(e.cashCommodity)
        val quantity = quantityMinor(e.quantity, e.instrument)
        val itemized = itemizedCharges(e.feeItems, mapOf(e.cashCommodity to e.fees) + e.foreignFees)
        // Itemized charges are expenses, each in its account, and the units
        // cost their price (what the broker's own average price is); a fee
        // nobody itemized is capitalized, the ledger's default.
        val cost = minor(if (itemized != null) e.gross else e.gross + e.fees, e.cashCommodity)
        val position = holdings[e.instrument]
        checkCostCommodity(position, e.cashCommodity, e.instrument)
        add(
            PlannedKind.Trade, e, ref,
            listOf(
                draft(
                    accounts.holdings, quantity, e.instrument, cost, e.cashCommodity,
                    fee = if (itemized != null) null else minor(e.fees, e.cashCommodity),
                ),
                draft(cashAccount, -cost, e.cashCommodity),
            ) + (itemized?.paidDrafts() ?: foreignFeeDrafts(e.foreignFees)),
            note = feeNote(e.fees, e.cashCommodity, e.foreignFees, e.feeItems),
        )
        if (itemized != null) itemized.bumpCash() else bumpForeignFees(e.foreignFees)
        holdings[e.instrument] = HeldPosition(
            (position?.quantityMinor ?: 0L) + quantity,
            (position?.costMinor ?: 0L) + cost,
            e.cashCommodity,
        )
        cash.bump(e.cashCommodity, -cost)
    }

    private fun planSale(e: BrokerEvent.Trade, ref: String) {
        val sold = quantityMinor(e.quantity.abs(), e.instrument)
        if (sold == 0L) throw PlanException("zero quantity")
        val itemized = itemizedCharges(e.feeItems, mapOf(e.cashCommodity to e.fees) + e.foreignFees)
        // Itemized: the sale brings in its price and the charges are expensed.
        val net = minor(if (itemized != null) e.gross else e.gross - e.fees, e.cashCommodity)
        val basis = e.costBasis?.let { minor(it, e.cashCommodity) }
        closePosition(
            e, ref, PlannedKind.Trade, e.instrument, sold, net, e.cashCommodity, basis,
            feeNote(e.fees, e.cashCommodity, e.foreignFees, e.feeItems),
            itemized?.paidDrafts() ?: foreignFeeDrafts(e.foreignFees),
        )
        if (itemized != null) itemized.bumpCash() else bumpForeignFees(e.foreignFees)
    }

    private fun planPrincipal(e: BrokerEvent.Principal, ref: String) {
        // A whole-position redemption takes every line of the security: an
        // ON bought on its dollar line (AEC1D) is paid back under its peso
        // ticker (AEC1O).
        val species = speciesOf(e.instrument)
        val redeemed = e.quantity?.let { quantityMinor(it.abs(), e.instrument) }
            ?: holdings.filter { speciesOf(it.key) == species }.values.sumOf { it.quantityMinor.coerceAtLeast(0L) }
                .takeIf { it > 0L }
            ?: throw PlanException("redeems the whole position of ${e.instrument} but the ledger holds none")
        if (redeemed == 0L) throw PlanException("zero quantity")
        closePosition(
            e, ref, PlannedKind.Principal, e.instrument, redeemed, minor(e.cash, e.cashCommodity), e.cashCommodity, null,
            feeNote(Decimal.ZERO, e.cashCommodity, e.fees, e.feeItems),
            itemizedCharges(e.feeItems, e.fees)?.paidDrafts() ?: foreignFeeDrafts(e.fees),
        )
        bumpForeignFees(e.fees)
    }

    /**
     * [quantity] units of [instrument] leave the holdings at their cost basis
     * and [net] arrives in cash; the gap is the realized gain. The units come
     * from [instrument]'s own line first and then from the other lines of
     * the same security ([lots]), each at its own cost, with [net] (and a
     * broker's basis) split between them by quantity. One transaction.
     */
    private fun closePosition(
        e: BrokerEvent,
        ref: String,
        kind: PlannedKind,
        instrument: String,
        quantity: Long,
        net: Long,
        cashCommodity: String,
        brokerBasis: Long?,
        note: String?,
        extraDrafts: List<DraftPosting>,
    ) {
        val lots = lots(instrument, quantity)
        val nets = splitByQuantity(net, lots.map { it.second })
        val bases = brokerBasis?.let { splitByQuantity(it, lots.map { l -> l.second }) }
        // Everything is computed before anything is recorded, so a lot that
        // can't close leaves the running balances as they were.
        val closed = lots.mapIndexed { i, (line, units) -> closeLot(line, units, nets[i], cashCommodity, bases?.get(i)) }
        add(kind, e, ref, closed.flatMap { it.drafts } + extraDrafts, note)
        for (lot in closed) {
            holdings[lot.instrument] = lot.after
            cash.bump(cashCommodity, lot.net)
        }
    }

    private class ClosedLot(val instrument: String, val drafts: List<DraftPosting>, val after: HeldPosition, val net: Long)

    /**
     * Where [quantity] units of [instrument] come out of: its own line, then
     * the security's peso line, then any other line. Whatever no line holds
     * stays on [instrument], where closing it reports the shortfall (or,
     * with a broker's basis, books it as the broker says).
     */
    private fun lots(instrument: String, quantity: Long): List<Pair<String, Long>> {
        val species = speciesOf(instrument)
        val candidates = listOf(instrument) + holdings.keys
            .filter { it != instrument && speciesOf(it) == species }
            .sortedWith(compareBy<String>({ it != species }, { it }))
        val result = mutableListOf<Pair<String, Long>>()
        var left = quantity
        for (line in candidates) {
            if (left == 0L) break
            val held = holdings[line]?.quantityMinor ?: 0L
            if (held <= 0L) continue
            val take = minOf(held, left)
            result += line to take
            left -= take
        }
        if (left > 0L) {
            val own = result.indexOfFirst { it.first == instrument }
            if (own >= 0) result[own] = instrument to (result[own].second + left) else result += instrument to left
        }
        return result
    }

    private fun closeLot(instrument: String, quantity: Long, net: Long, cashCommodity: String, brokerBasis: Long?): ClosedLot {
        val cashAccount = cashAccount(cashCommodity)
        val position = holdings[instrument]
        val costCommodity = position?.costCommodity?.takeIf { position.quantityMinor != 0L } ?: cashCommodity
        if (costCommodity != cashCommodity && brokerBasis != null) {
            throw PlanException("$instrument is held at cost in $costCommodity, the broker's basis is in $cashCommodity")
        }
        val held = position?.quantityMinor ?: 0L
        val basis = brokerBasis ?: run {
            // Average cost needs the units to come from somewhere: selling
            // more than the ledger holds means history is missing, and any
            // basis made up here would be a made-up gain.
            if (position == null || held < quantity) {
                throw PlanException("closes $quantity of $instrument but the ledger holds $held")
            }
            averageCost(position, quantity)
        }
        val drafts = buildList {
            add(draft(accounts.holdings, -quantity, instrument, -basis, costCommodity))
            if (costCommodity == cashCommodity) {
                add(draft(cashAccount, net, cashCommodity))
                // Income is negative in the books: a gain is a credit.
                val gain = net - basis
                if (gain != 0L) add(draft(accounts.capitalGains, -gain, cashCommodity))
            } else {
                // Bought in one currency, paid back in another (a hard-dollar
                // ON bought with pesos): there is no single currency to state
                // a gain in without a rate. The money received costs the
                // basis, exactly like dollars bought through MEP, and the
                // result stays inside that rate instead of being invented.
                if (net <= 0L) throw PlanException("non-positive proceeds in another currency than the cost")
                add(draft(cashAccount, net, cashCommodity, basis, costCommodity))
            }
        }
        return ClosedLot(
            instrument,
            drafts,
            HeldPosition(held - quantity, (position?.costMinor ?: 0L) - basis, costCommodity),
            net,
        )
    }

    private fun planIncome(e: BrokerEvent.Income, ref: String) {
        val cashAccount = cashAccount(e.cashCommodity)
        val gross = minor(e.gross, e.cashCommodity)
        val tax = minor(e.tax, e.cashCommodity)
        if (gross == 0L) throw PlanException("zero income")
        val incomeAccount = if (e.kind == IncomeKind.Dividend) accounts.dividends else accounts.interest
        val drafts = buildList {
            add(draft(cashAccount, gross - tax, e.cashCommodity))
            add(draft(incomeAccount, -gross, e.cashCommodity))
            if (tax != 0L) add(draft(accounts.taxes, tax, e.cashCommodity))
        } + (itemizedCharges(e.feeItems, e.fees)?.paidDrafts() ?: foreignFeeDrafts(e.fees))
        add(PlannedKind.Income, e, ref, drafts, note = feeNote(Decimal.ZERO, e.cashCommodity, e.fees, e.feeItems))
        cash.bump(e.cashCommodity, gross - tax)
        bumpForeignFees(e.fees)
    }

    private fun planTransfer(e: BrokerEvent.CashTransfer, ref: String) {
        val cashAccount = cashAccount(e.cashCommodity)
        val amount = minor(e.amount, e.cashCommodity)
        if (amount == 0L) throw PlanException("zero transfer")
        // Against the opening balance until someone says where it came from:
        // money that existed before the ledger knew about it is exactly what
        // that account means, and it moves no net worth twice.
        add(
            PlannedKind.Transfer, e, ref,
            listOf(
                draft(cashAccount, amount, e.cashCommodity),
                draft(accounts.opening, -amount, e.cashCommodity),
            ),
            needsCounterpart = true,
        )
        cash.bump(e.cashCommodity, amount)
    }

    private fun planFx(e: BrokerEvent.FxConversion, ref: String) {
        val fromAccount = cashAccount(e.fromCommodity)
        val toAccount = cashAccount(e.toCommodity)
        if (e.fromCommodity == e.toCommodity) throw PlanException("conversion within ${e.fromCommodity}")
        val from = minor(e.from, e.fromCommodity)
        val to = minor(e.to, e.toCommodity)
        val fees = minor(e.fees, e.fromCommodity)
        if (from == 0L || to == 0L) throw PlanException("zero conversion")
        // The currency bought carries what it cost net of the fee, so the
        // fee is an expense and not part of the rate.
        val drafts = buildList {
            add(draft(toAccount, to, e.toCommodity, from - fees, e.fromCommodity))
            add(draft(fromAccount, -from, e.fromCommodity))
            if (fees != 0L) {
                // By type when the broker itemized this currency's charges.
                val itemized = itemizedCharges(e.feeItems.filter { it.commodity == e.fromCommodity }, mapOf(e.fromCommodity to e.fees))
                addAll(itemized?.expenseDrafts ?: listOf(draft(accounts.commissions, fees, e.fromCommodity)))
            }
        }
        add(PlannedKind.Fx, e, ref, drafts, note = feeNote(Decimal.ZERO, e.fromCommodity, emptyMap(), e.feeItems))
        cash.bump(e.fromCommodity, -from)
        cash.bump(e.toCommodity, to)
    }

    private fun planQuantityChange(e: BrokerEvent.QuantityChange, ref: String) {
        val delta = quantityMinor(e.delta, e.instrument)
        if (delta == 0L) throw PlanException("zero quantity change")
        // Same commodity on both legs, so it balances without a cost and the
        // position's total cost is untouched: a 2:1 split halves the cost per
        // unit, which is exactly what a split is.
        add(
            PlannedKind.QuantityChange, e, ref,
            listOf(
                draft(accounts.holdings, delta, e.instrument),
                draft(accounts.adjustments, -delta, e.instrument),
            ),
            note = e.note,
        )
        val position = holdings[e.instrument]
        holdings[e.instrument] = HeldPosition(
            (position?.quantityMinor ?: 0L) + delta,
            position?.costMinor ?: 0L,
            position?.costCommodity,
        )
    }

    // --- splits -----------------------------------------------------------

    /**
     * Splits the broker applied but never listed as a movement (IOL's
     * operations have no row for YPF's 1:10 of 2026): what the ledger plus
     * this batch would hold of a security is an exact fraction (or multiple)
     * of what the snapshot says, and the broker's average cost moved by the
     * same factor. Both have to agree: 10 units where the history explains 1
     * is also what an account opened before its history looks like, and only
     * the cost tells them apart (the 9 units held from before cost what the
     * one bought did; after a 1:10 split the broker's average is a tenth).
     *
     * The split is dated just before the snapshot (the broker doesn't say
     * when) on the one line that holds the units, and marked
     * [PlannedTransaction.inferred], so an unattended sync never writes it.
     * A security held on several lines, or bought in another currency than
     * the one the broker states its cost in, is left to [differences].
     */
    private fun inferSplits(events: List<BrokerEvent>, snapshot: BrokerSnapshot): List<BrokerEvent.QuantityChange> {
        val quantity = mutableMapOf<String, Decimal>()
        val paid = mutableMapOf<String, MutableMap<String, Pair<Decimal, Decimal>>>()
        val skip = mutableSetOf<String>()
        fun units(line: String, d: Decimal) { quantity[line] = (quantity[line] ?: Decimal.ZERO) + d }
        fun paid(line: String, commodity: String, cost: Decimal, units: Decimal) {
            val byCurrency = paid.getOrPut(speciesOf(line)) { mutableMapOf() }
            val (c, u) = byCurrency[commodity] ?: (Decimal.ZERO to Decimal.ZERO)
            byCurrency[commodity] = (c + cost) to (u + units)
        }
        for ((line, position) in ledger.holdings) {
            val units = Decimal.ofMinorUnits(position.quantityMinor, scaleOf(line))
            units(line, units)
            val costCommodity = position.costCommodity
            if (position.quantityMinor > 0L && position.costMinor > 0L && costCommodity != null) {
                paid(line, costCommodity, Decimal.ofMinorUnits(position.costMinor, scaleOf(costCommodity)), units)
            }
        }
        for (event in events) {
            when (event) {
                is BrokerEvent.Trade -> {
                    units(event.instrument, event.quantity)
                    if (event.quantity.signum > 0) paid(event.instrument, event.cashCommodity, event.gross, event.quantity)
                }
                is BrokerEvent.Principal -> event.quantity?.let { units(event.instrument, -it.abs()) }
                    ?: skip.add(speciesOf(event.instrument))
                // The broker did report it: nothing to infer.
                is BrokerEvent.QuantityChange -> skip.add(speciesOf(event.instrument))
                else -> Unit
            }
        }
        val result = mutableListOf<BrokerEvent.QuantityChange>()
        for ((species, held) in snapshotBySpecies(snapshot)) {
            if (species in skip) continue
            val brokerCost = held.cost ?: continue
            val costCommodity = held.costCommodity ?: continue
            if (held.quantity.signum <= 0 || brokerCost.signum <= 0) continue
            val lines = quantity.filter { speciesOf(it.key) == species && !it.value.isZero }
            val (line, ours) = lines.entries.singleOrNull() ?: continue
            if (ours.signum <= 0 || ours == held.quantity) continue
            val forward = held.quantity > ours
            val whole = run whole@{
                val bought = paid[species]?.takeIf { it.keys == setOf(costCommodity) }?.get(costCommodity) ?: return@whole null
                if (bought.second.signum <= 0) return@whole null
                val ourUnit = bought.first.divide(bought.second, 8)
                val brokerUnit = brokerCost.divide(held.quantity, 8)
                val factor = if (forward) held.quantity.divide(ours, 0) else ours.divide(held.quantity, 0)
                if (factor < Decimal.of(2)) return@whole null
                if ((if (forward) ours * factor else held.quantity * factor) != (if (forward) held.quantity else ours)) return@whole null
                // After a 1:r split the broker's unit cost is ours / r; after r:1, ours × r.
                val expected = if (forward) ourUnit.divide(factor, 8) else ourUnit * factor
                if (!roughlyEqual(brokerUnit, expected)) return@whole null
                factor to snapshot.at - 1
            }
            val (factor, at) = whole ?: (if (forward) partialSplit(species, held.quantity - ours, events) else null) ?: continue
            val symbol = batch.instruments.firstOrNull { it.id == species }?.symbol ?: species.substringAfter(':')
            val ratio = factor.stripTrailingZeros().toPlainString()
            result += BrokerEvent.QuantityChange(
                ref = "split:$species:${snapshot.at}",
                at = at,
                timeKnown = false,
                description = if (forward) "Split $symbol 1:$ratio" else "Contrasplit $symbol $ratio:1",
                instrument = line,
                delta = held.quantity - ours,
                note = "Deducido: ${batch.provider.uppercase()} informa ${held.quantity.toPlainString()}, " +
                    "el historial explica ${ours.toPlainString()}",
            )
        }
        return result
    }

    /**
     * A split that multiplied only the units held before some later trade
     * (a CEDEAR's ratio change: 3 SPYD bought at US$ 31 became 9, and 15
     * more were bought at US$ 13, so the broker holds 24 where the history
     * explains 18). The gap has to be a whole multiple of what was held at
     * exactly one point between trades, and the trades on either side of
     * that point have to be priced apart by about the same factor (within
     * 40 %: the market moves between them, a split moves the price by the
     * whole factor). Returns the factor and a date just before the first
     * trade after the split, or null.
     */
    private fun partialSplit(species: String, gap: Decimal, events: List<BrokerEvent>): Pair<Decimal, Long>? {
        val moves = events.filter {
            (it is BrokerEvent.Trade && speciesOf(it.instrument) == species) ||
                (it is BrokerEvent.Principal && speciesOf(it.instrument) == species)
        }
        if (moves.size < 2 || gap.signum <= 0) return null
        var held = Decimal.ZERO
        for ((line, position) in ledger.holdings) {
            if (speciesOf(line) == species) held += Decimal.ofMinorUnits(position.quantityMinor, scaleOf(line))
        }
        val candidates = mutableListOf<Pair<Int, Decimal>>()
        for ((k, move) in moves.withIndex()) {
            // A split right before moves[k] multiplied what was held then.
            if (k > 0 && held.signum > 0) {
                val extra = gap.divide(held, 0)
                if (extra.signum > 0 && extra * held == gap) candidates += k to (extra + Decimal.of(1))
            }
            held += when (move) {
                is BrokerEvent.Trade -> move.quantity
                is BrokerEvent.Principal -> -(move.quantity ?: return null).abs()
                else -> Decimal.ZERO
            }
        }
        val (k, factor) = candidates.singleOrNull() ?: return null
        val before = moves.take(k).filterIsInstance<BrokerEvent.Trade>().lastOrNull() ?: return null
        val after = moves.drop(k).filterIsInstance<BrokerEvent.Trade>()
            .firstOrNull { it.cashCommodity == before.cashCommodity } ?: return null
        val priceBefore = before.gross.divide(before.quantity.abs(), 8)
        val priceAfter = after.gross.divide(after.quantity.abs(), 8)
        if (priceAfter.signum <= 0) return null
        val moved = priceBefore.divide(priceAfter, 8)
        val ten = Decimal.of(10)
        val fourteen = Decimal.of(14)
        if (moved * fourteen < factor * ten || moved * ten > factor * fourteen) return null
        return factor to moves[k].at - 1
    }

    /**
     * A split an earlier import turned into units the account never had
     * (before [inferSplits] existed, the opening made up the difference at
     * the old price): the quantities agree, but the ledger's average cost is
     * a whole multiple of the broker's. Nothing the plan can fix by adding
     * a movement — the history itself is wrong — so it is reported, and a
     * rebuilt import replans it with the split.
     */
    private fun reportHiddenSplits(snapshot: BrokerSnapshot) {
        for ((species, held) in snapshotBySpecies(snapshot)) {
            val brokerCost = held.cost ?: continue
            if (held.quantity.signum <= 0 || brokerCost.signum <= 0) continue
            val lines = holdings.filter { speciesOf(it.key) == species && it.value.quantityMinor != 0L }
            val (line, position) = lines.entries.singleOrNull() ?: continue
            if (position.costCommodity != held.costCommodity || position.costMinor <= 0L) continue
            val units = Decimal.ofMinorUnits(position.quantityMinor, scaleOf(line))
            if (units != held.quantity) continue
            val ours = Decimal.ofMinorUnits(position.costMinor, scaleOf(held.costCommodity!!))
            val (bigger, smaller) = if (ours > brokerCost) ours to brokerCost else brokerCost to ours
            val factor = bigger.divide(smaller, 0)
            if (factor < Decimal.of(2) || !roughlyEqual(bigger, smaller * factor)) continue
            issues += PlanIssue(
                null,
                "$species: the ledger's cost is ${if (ours > brokerCost) "${factor.toPlainString()}×" else "1/${factor.toPlainString()} of"} " +
                    "the broker's, a split missing from the history; rebuild the import",
            )
        }
    }

    /** Within 10 %: a cost with commissions against one without, never a whole factor apart. */
    private fun roughlyEqual(a: Decimal, b: Decimal): Boolean {
        if (b.signum <= 0) return false
        val gap = (a - b).abs()
        return gap * Decimal.of(10) <= b
    }

    // --- opening ----------------------------------------------------------

    /**
     * First import of an account whose history starts mid-life (IOL answers
     * about a year, a custody statement is a single photo): what the snapshot
     * holds minus what this batch's events will add is what was there before
     * them, booked against Patrimonio:Saldo inicial just before the first
     * event (plan decision 6).
     */
    private fun planOpening(events: List<BrokerEvent>, snapshot: BrokerSnapshot) {
        // By security, not by line: the snapshot reports 11 AAPL where the
        // events bought 3 AAPL and 8 AAPLD, and the opening is zero, not 8.
        val quantityDelta = mutableMapOf<String, Decimal>()
        val cashDelta = mutableMapOf<String, Decimal>()
        fun q(instrument: String, d: Decimal) {
            val species = speciesOf(instrument)
            quantityDelta[species] = (quantityDelta[species] ?: Decimal.ZERO) + d
        }
        fun c(commodity: String, d: Decimal) { cashDelta[commodity] = (cashDelta[commodity] ?: Decimal.ZERO) + d }
        for (event in events) {
            when (event) {
                is BrokerEvent.Trade -> {
                    q(event.instrument, event.quantity)
                    c(event.cashCommodity, if (event.quantity.signum > 0) -(event.gross + event.fees) else event.gross - event.fees)
                    for ((commodity, fee) in event.foreignFees) c(commodity, -fee)
                }
                is BrokerEvent.Income -> {
                    c(event.cashCommodity, event.gross - event.tax)
                    for ((commodity, fee) in event.fees) c(commodity, -fee)
                }
                is BrokerEvent.Principal -> {
                    // A whole-position redemption takes whatever is there, so
                    // the opening is the snapshot's (zero after maturity) plus
                    // what it took — unknown here; it's valued from the
                    // other events and the snapshot like any other holding.
                    event.quantity?.let { q(event.instrument, -it.abs()) }
                    c(event.cashCommodity, event.cash)
                    for ((commodity, fee) in event.fees) c(commodity, -fee)
                }
                is BrokerEvent.CashTransfer -> c(event.cashCommodity, event.amount)
                is BrokerEvent.FxConversion -> {
                    c(event.fromCommodity, -event.from)
                    c(event.toCommodity, event.to)
                }
                is BrokerEvent.QuantityChange -> q(event.instrument, event.delta)
            }
        }

        val drafts = mutableListOf<DraftPosting>()
        val equity = mutableMapOf<String, Long>()
        val held = snapshotBySpecies(snapshot)
        // What the ledger already holds outside this plan: nothing on a first
        // import, a hand-typed transfer of securities on a rebuild.
        val booked = mutableMapOf<String, Decimal>()
        for ((line, position) in ledger.holdings) {
            val species = speciesOf(line)
            booked[species] = (booked[species] ?: Decimal.ZERO) + Decimal.ofMinorUnits(position.quantityMinor, scaleOf(line))
        }
        val instruments = (held.keys + quantityDelta.keys).distinct()
        for (instrument in instruments) {
            val position = held[instrument]
            val opening = redeemedOpening(instrument, events)
                ?: ((position?.quantity ?: Decimal.ZERO) - (quantityDelta[instrument] ?: Decimal.ZERO) -
                    (booked[instrument] ?: Decimal.ZERO))
            if (opening.isZero) continue
            if (opening.signum < 0) {
                issues += PlanIssue(null, "opening: $instrument would open short (${opening.toPlainString()})")
                continue
            }
            val (cost, costCommodity) = openingCost(instrument, opening, position, events) ?: run {
                issues += PlanIssue(null, "opening: no price or cost to value ${opening.toPlainString()} $instrument")
                null
            } ?: continue
            val quantity = minor(opening, instrument)
            drafts += draft(accounts.holdings, quantity, instrument, cost, costCommodity)
            equity.bump(costCommodity, cost)
            val before = holdings[instrument]
            holdings[instrument] = if (before == null || before.quantityMinor == 0L) {
                HeldPosition(quantity, cost, costCommodity)
            } else {
                HeldPosition(before.quantityMinor + quantity, before.costMinor + cost, before.costCommodity ?: costCommodity)
            }
        }
        for (commodity in (snapshot.cash.keys + cashDelta.keys).distinct()) {
            val opening = (snapshot.cash[commodity] ?: Decimal.ZERO) - (cashDelta[commodity] ?: Decimal.ZERO)
            val amount = minor(opening, commodity) - (ledger.cash[commodity] ?: 0L)
            if (amount == 0L) continue
            val account = accounts.cash[commodity] ?: run {
                issues += PlanIssue(null, "opening: no cash account for $commodity")
                null
            } ?: continue
            drafts += draft(account, amount, commodity)
            equity.bump(commodity, amount)
            cash.bump(commodity, amount)
        }
        if (drafts.isEmpty()) return
        for ((commodity, amount) in equity) {
            if (amount != 0L) drafts += draft(accounts.opening, -amount, commodity)
        }
        val beforeFirst = (events.minOfOrNull { it.at } ?: snapshot.at) - 1
        val at = batch.since?.let { minOf(it, beforeFirst) } ?: beforeFirst
        val transaction = NewTransaction(
            date = at,
            payee = "Saldo inicial ${batch.provider.uppercase()}",
            note = null,
            drafts = drafts,
            timeKnown = false,
        )
        if (validates(transaction, null)) planned += PlannedTransaction(PlannedKind.Opening, transaction, null)
    }

    /**
     * The opening of an instrument the batch redeems in full, or null when it
     * doesn't. A whole-position redemption resets the count, so the snapshot
     * says nothing about what was there before it: the opening is just
     * enough for the events before the first redemption never to go short —
     * zero when the batch bought it itself, which is the usual case (a letra
     * bought and held to maturity).
     */
    private fun redeemedOpening(instrument: String, events: List<BrokerEvent>): Decimal? {
        val own = events.filter {
            (it is BrokerEvent.Trade && speciesOf(it.instrument) == instrument) ||
                (it is BrokerEvent.Principal && speciesOf(it.instrument) == instrument) ||
                (it is BrokerEvent.QuantityChange && speciesOf(it.instrument) == instrument)
        }
        val firstReset = own.indexOfFirst { it is BrokerEvent.Principal && it.quantity == null }
        if (firstReset < 0) return null
        var running = Decimal.ZERO
        var lowest = Decimal.ZERO
        for (event in own.take(firstReset)) {
            running += when (event) {
                is BrokerEvent.Trade -> event.quantity
                is BrokerEvent.Principal -> -(event.quantity ?: Decimal.ZERO).abs()
                is BrokerEvent.QuantityChange -> event.delta
                else -> Decimal.ZERO
            }
            if (running < lowest) lowest = running
        }
        return -lowest
    }

    /**
     * What the units open at: the broker's cost for the position when it
     * reports one (pro rata, since part may have been bought in this batch),
     * else the broker's cost basis on a later sale of them, else the first
     * price this batch shows for them. Null when nothing prices them.
     *
     * [instrument] is a security; its trades on every line count, but only
     * the buys in the currency the broker states its cost in are taken off
     * that cost (a dollar amount can't be subtracted from pesos). The first
     * trade used as a price is the security's own line when there is one.
     */
    private fun openingCost(
        instrument: String,
        opening: Decimal,
        held: SnapshotPosition?,
        events: List<BrokerEvent>,
    ): Pair<Long, String>? {
        val trades = events.filterIsInstance<BrokerEvent.Trade>()
            .filter { speciesOf(it.instrument) == instrument }
            .sortedBy { it.instrument != instrument }
        val bought = trades.filter { it.quantity.signum > 0 && it.cashCommodity == held?.costCommodity }
        if (held?.cost != null && held.costCommodity != null && held.quantity.signum > 0) {
            // Cost of the snapshot's units, minus what this batch's buys added,
            // spread over what remains: exact when nothing was sold.
            val boughtCost = bought.fold(Decimal.ZERO) { acc, t -> acc + t.gross + t.fees }
            val boughtQuantity = bought.fold(Decimal.ZERO) { acc, t -> acc + t.quantity }
            val remainingQuantity = held.quantity - boughtQuantity
            val remainingCost = held.cost - boughtCost
            if (remainingQuantity.signum > 0 && remainingCost.signum > 0) {
                val cost = (remainingCost * opening).divide(remainingQuantity, scaleOf(held.costCommodity))
                return minor(cost, held.costCommodity) to held.costCommodity
            }
        }
        trades.firstOrNull { it.quantity.signum < 0 && it.costBasis != null }?.let { sale ->
            val perUnit = sale.costBasis!!.divide(sale.quantity.abs(), 8)
            return minor(perUnit * opening, sale.cashCommodity) to sale.cashCommodity
        }
        trades.firstOrNull()?.let { trade ->
            val perUnit = (trade.gross).divide(trade.quantity.abs(), 8)
            return minor(perUnit * opening, trade.cashCommodity) to trade.cashCommodity
        }
        val info = batch.instruments.firstOrNull { it.id == instrument }
        val quote = held?.price ?: batch.prices.firstOrNull { it.commodity == instrument }?.price
        val quoteCommodity = info?.quoteCommodity ?: batch.prices.firstOrNull { it.commodity == instrument }?.quoteCommodity
        if (quote != null && quoteCommodity != null) {
            val value = (quote * opening).movePointLeft(pricePerExponent(info?.pricePer ?: 1))
            return minor(value, quoteCommodity) to quoteCommodity
        }
        return null
    }

    // --- outputs ----------------------------------------------------------

    private fun prices(): List<PriceQuote> {
        val fromSnapshot = batch.snapshot?.let { snapshot ->
            snapshot.positions.mapNotNull { position ->
                val price = position.price ?: return@mapNotNull null
                val quote = batch.instruments.firstOrNull { it.id == position.instrument }?.quoteCommodity
                    ?: position.costCommodity
                    ?: return@mapNotNull null
                PriceQuote(position.instrument, quote, snapshot.at, price, batch.provider)
            }
        }.orEmpty()
        return (batch.prices + fromSnapshot).distinctBy { listOf(it.commodity, it.quoteCommodity, it.at, it.source) }
    }

    private fun differences(): List<BalanceDifference> {
        val snapshot = batch.snapshot ?: return emptyList()
        val result = mutableListOf<BalanceDifference>()
        for (commodity in (snapshot.cash.keys + cash.keys).distinct().sorted()) {
            val account = accounts.cash[commodity] ?: continue
            val broker = snapshot.cash[commodity]?.let { minor(it, commodity) } ?: 0L
            val ledgerNow = cash[commodity] ?: 0L
            if (broker != ledgerNow) result += BalanceDifference(account, commodity, ledgerNow, broker)
        }
        // Per security: the broker's AAPL is the ledger's AAPL plus AAPLD.
        val broker = snapshotBySpecies(snapshot)
        val booked = mutableMapOf<String, Long>()
        for ((line, position) in holdings) booked.bump(speciesOf(line), position.quantityMinor)
        for (instrument in (broker.keys + booked.keys).distinct().sorted()) {
            val brokerNow = broker[instrument]?.let { minor(it.quantity, instrument) } ?: 0L
            val ledgerNow = booked[instrument] ?: 0L
            if (brokerNow != ledgerNow) result += BalanceDifference(accounts.holdings, instrument, ledgerNow, brokerNow)
        }
        return result
    }

    /**
     * The snapshot keyed by security. A broker reports one row per security
     * already; two rows that map to one (a line reported on its own) add up,
     * keeping the first one's price and adding costs stated in one currency.
     */
    private fun snapshotBySpecies(snapshot: BrokerSnapshot): Map<String, SnapshotPosition> {
        val result = linkedMapOf<String, SnapshotPosition>()
        for (position in snapshot.positions) {
            val species = speciesOf(position.instrument)
            val before = result[species]
            result[species] = if (before == null) {
                position.copy(instrument = species)
            } else {
                before.copy(
                    quantity = before.quantity + position.quantity,
                    cost = if (before.costCommodity == position.costCommodity && before.cost != null && position.cost != null) {
                        before.cost + position.cost
                    } else {
                        null
                    },
                )
            }
        }
        return result
    }

    // --- helpers ----------------------------------------------------------

    private fun add(
        kind: PlannedKind,
        event: BrokerEvent,
        ref: String,
        drafts: List<DraftPosting>,
        note: String? = null,
        needsCounterpart: Boolean = false,
    ) {
        val transaction = NewTransaction(
            date = event.at,
            payee = event.description,
            note = note,
            drafts = drafts,
            timeKnown = event.timeKnown,
            sources = listOf(TransactionSource(EventSource.Broker, ref)),
        )
        if (validates(transaction, ref)) {
            planned += PlannedTransaction(kind, transaction, ref, needsCounterpart)
        }
    }

    /**
     * Every plan goes through the same validation a hand-typed row does: a
     * planner bug surfaces as an issue on that event, never as an unbalanced
     * transaction in the review screen.
     */
    private fun validates(transaction: NewTransaction, ref: String?): Boolean = try {
        resolvePostings(transaction.drafts)
        true
    } catch (e: LedgerValidationException) {
        issues += PlanIssue(ref, "invalid plan: ${e.message}")
        false
    }

    private fun cashAccount(commodity: String): String =
        accounts.cash[commodity] ?: throw PlanException("no cash account for $commodity")

    private fun checkCostCommodity(position: HeldPosition?, commodity: String, instrument: String) {
        val held = position?.costCommodity ?: return
        if (position.quantityMinor != 0L && held != commodity) {
            throw PlanException("$instrument is held at cost in $held, this trade settles in $commodity")
        }
    }

    private fun averageCost(position: HeldPosition, quantity: Long): Long =
        if (quantity == position.quantityMinor) {
            // The whole position: exactly its cost, no rounding residue left
            // behind on a position that is now zero.
            position.costMinor
        } else {
            (Decimal.of(position.costMinor) * Decimal.of(quantity))
                .divide(Decimal.of(position.quantityMinor), 0)
                .toMinorUnits(0)
        }

    private fun feeNote(
        fees: Decimal,
        commodity: String,
        foreign: Map<String, Decimal> = emptyMap(),
        items: List<FeeItem> = emptyList(),
    ): String? {
        // Itemized when the broker says what each charge is: "Comisión
        // $ 1.585,35 + IVA $ 332,92 · Derechos de mercado $ 221,95 + IVA $ 46,61".
        if (items.isNotEmpty()) {
            return items.joinToString(" · ") { item ->
                val net = formatMoney(minor(item.net, item.commodity), item.commodity)
                if (item.vat.isZero) "${item.label} $net"
                else "${item.label} $net + IVA ${formatMoney(minor(item.vat, item.commodity), item.commodity)}"
            }
        }
        val parts = (listOf(commodity to fees) + foreign.toList())
            .filter { !it.second.isZero }
            .map { (c, f) -> formatMoney(minor(f, c), c) }
        return if (parts.isEmpty()) null else "Comisión " + parts.joinToString(" + ")
    }

    /** Itemized charges as expense drafts per account, and what they took from each currency's cash. */
    private inner class ItemizedCharges(val expenseDrafts: List<DraftPosting>, val charged: Map<String, Long>) {
        /** The expenses plus the cash they came out of. */
        fun paidDrafts(): List<DraftPosting> =
            expenseDrafts + charged.map { (commodity, amount) -> draft(cashAccount(commodity), -amount, commodity) }

        fun bumpCash() {
            for ((commodity, amount) in charged) cash.bump(commodity, -amount)
        }
    }

    /**
     * [items] split into Comisiones / Derechos de mercado / IVA, when they
     * add up to [totals] (per currency, to the cent); null otherwise, and
     * the caller books the charges the un-itemized way. An item the broker
     * names in no known way is a commission.
     */
    private fun itemizedCharges(items: List<FeeItem>, totals: Map<String, Decimal>): ItemizedCharges? {
        if (items.isEmpty()) return null
        val drafts = mutableListOf<DraftPosting>()
        val charged = mutableMapOf<String, Long>()
        for (item in items) {
            val net = minor(item.net, item.commodity)
            val vat = minor(item.vat, item.commodity)
            val label = item.label.lowercase()
            val account = when {
                "derecho" in label -> accounts.marketFees ?: accounts.commissions
                else -> accounts.commissions
            }
            if (net != 0L) drafts += draft(account, net, item.commodity)
            if (vat != 0L) drafts += draft(accounts.vat ?: accounts.taxes, vat, item.commodity)
            charged.bump(item.commodity, net + vat)
        }
        val expected = totals.filterValues { !it.isZero }.mapValues { (c, v) -> minor(v, c) }
        if (charged.filterValues { it != 0L } != expected) return null
        return ItemizedCharges(drafts, charged)
    }

    /** Fees in another currency than the trade's: an expense from that currency's cash. */
    private fun foreignFeeDrafts(fees: Map<String, Decimal>): List<DraftPosting> =
        fees.filterValues { !it.isZero }.flatMap { (commodity, fee) ->
            val amount = minor(fee, commodity)
            listOf(
                draft(accounts.commissions, amount, commodity),
                draft(cashAccount(commodity), -amount, commodity),
            )
        }

    private fun bumpForeignFees(fees: Map<String, Decimal>) {
        for ((commodity, fee) in fees) cash.bump(commodity, -minor(fee, commodity))
    }

    private fun draft(
        account: String,
        amount: Long,
        commodity: String,
        cost: Long? = null,
        costCommodity: String? = null,
        fee: Long? = null,
    ) = DraftPosting(
        accountId = account,
        amountText = formatMinorUnits(amount),
        commodity = commodity,
        costText = cost?.let { formatMinorUnits(it) }.orEmpty(),
        costCommodity = if (cost != null) costCommodity else null,
        feeMinor = fee?.takeIf { cost != null && it != 0L },
    )
}

/** 100 → 2: the exponent of a face-value divisor (only powers of ten occur). */
private fun pricePerExponent(pricePer: Int): Int {
    var n = pricePer
    var exponent = 0
    while (n >= 10 && n % 10 == 0) {
        n /= 10
        exponent++
    }
    require(n == 1) { "price_per must be a power of ten: $pricePer" }
    return exponent
}

/**
 * [total] split in proportion to [parts], exactly: the last share takes the
 * rounding, so the shares add up to [total].
 */
private fun splitByQuantity(total: Long, parts: List<Long>): List<Long> {
    if (parts.size <= 1) return listOf(total)
    val sum = Decimal.of(parts.sum())
    var assigned = 0L
    return parts.mapIndexed { i, part ->
        if (i == parts.lastIndex) {
            total - assigned
        } else {
            (Decimal.of(total) * Decimal.of(part)).divide(sum, 0).toMinorUnits(0).also { assigned += it }
        }
    }
}

private fun MutableMap<String, Long>.bump(key: String, by: Long) {
    this[key] = (this[key] ?: 0L) + by
}

private class PlanException(message: String) : Exception(message)
