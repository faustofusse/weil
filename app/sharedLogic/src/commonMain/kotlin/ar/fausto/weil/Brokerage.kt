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
 *  - **commissions are capitalized**: a buy costs gross + fees, a sale
 *    brings in gross − fees. That is the basis brokers (IBKR's `Cost Basis`)
 *    and AFIP use, and it is what makes the broker's realized gain reproduce
 *    exactly instead of counting the commission twice. The fee stays visible
 *    in the transaction's note;
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
    ) : BrokerEvent

    /**
     * Money in (+) or out (−) of the broker. The other side is a bank account
     * the planner can't see: the transaction is booked against the
     * transfers-in-transit account and flagged, so the review screen can
     * match it with the bank's movement (Reconcile's Mirror).
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
    ) : BrokerEvent

    /** A split, a spin-off, a correction: units appear or vanish, cost untouched. */
    data class QuantityChange(
        override val ref: String,
        override val at: Long,
        override val timeKnown: Boolean,
        override val description: String,
        val instrument: String,
        val delta: Decimal,
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
    /** FX fees; trade fees are capitalized and never land here. */
    val commissions: String,
    val opening: String,
    val adjustments: String,
    /**
     * Counterpart of deposits/withdrawals until they are matched with the
     * bank. Null for a broker that never reports them (IOL): a transfer
     * event then becomes an issue instead of a guess.
     */
    val transfers: String? = null,
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
    /** A transfer whose other side is still the in-transit account. */
    val needsCounterpart: Boolean = false,
)

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
)

/** The ref as stored in `transaction_sources`: provider-scoped. */
fun brokerRef(provider: String, ref: String): String = "$provider:$ref"

/**
 * Turns a [batch] into a [BrokerPlan].
 *
 * [knownRefs] are the namespaced refs already in `transaction_sources`
 * (see [brokerRef]); [scales] the decimals of commodities the ledger already
 * knows (a commodity nobody describes keeps 2, like everywhere else).
 */
fun planBrokerImport(
    batch: BrokerBatch,
    accounts: BrokerAccounts,
    ledger: BrokerLedgerView,
    knownRefs: Set<String>,
    scales: Map<String, Int> = emptyMap(),
): BrokerPlan = BrokerPlanner(batch, accounts, ledger, knownRefs, scales).plan()

private class BrokerPlanner(
    val batch: BrokerBatch,
    val accounts: BrokerAccounts,
    val ledger: BrokerLedgerView,
    val knownRefs: Set<String>,
    knownScales: Map<String, Int>,
) {
    private val scales: Map<String, Int> = knownScales + batch.instruments.associate { it.id to it.scale }
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

        if (ledger.isEmpty && batch.snapshot != null) planOpening(fresh, batch.snapshot)

        for (event in fresh) {
            val ref = brokerRef(batch.provider, event.ref)
            try {
                planEvent(event, ref)
            } catch (e: PlanException) {
                issues += PlanIssue(ref, e.message ?: "unplannable")
            } catch (e: ArithmeticException) {
                issues += PlanIssue(ref, "amount out of range: ${e.message}")
            }
        }

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
        val cost = minor(e.gross + e.fees, e.cashCommodity)
        val position = holdings[e.instrument]
        checkCostCommodity(position, e.cashCommodity, e.instrument)
        add(
            PlannedKind.Trade, e, ref,
            listOf(
                draft(accounts.holdings, quantity, e.instrument, cost, e.cashCommodity),
                draft(cashAccount, -cost, e.cashCommodity),
            ) + foreignFeeDrafts(e.foreignFees),
            note = feeNote(e.fees, e.cashCommodity, e.foreignFees),
        )
        bumpForeignFees(e.foreignFees)
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
        val net = minor(e.gross - e.fees, e.cashCommodity)
        val basis = e.costBasis?.let { minor(it, e.cashCommodity) }
        closePosition(
            e, ref, PlannedKind.Trade, e.instrument, sold, net, e.cashCommodity, basis,
            feeNote(e.fees, e.cashCommodity, e.foreignFees), foreignFeeDrafts(e.foreignFees),
        )
        bumpForeignFees(e.foreignFees)
    }

    private fun planPrincipal(e: BrokerEvent.Principal, ref: String) {
        val redeemed = e.quantity?.let { quantityMinor(it.abs(), e.instrument) }
            ?: holdings[e.instrument]?.quantityMinor?.takeIf { it > 0L }
            ?: throw PlanException("redeems the whole position of ${e.instrument} but the ledger holds none")
        if (redeemed == 0L) throw PlanException("zero quantity")
        closePosition(e, ref, PlannedKind.Principal, e.instrument, redeemed, minor(e.cash, e.cashCommodity), e.cashCommodity, null, null, emptyList())
    }

    /**
     * [quantity] units of [instrument] leave the holdings at their cost basis
     * and [net] arrives in cash; the gap is the realized gain.
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
            addAll(extraDrafts)
        }
        add(kind, e, ref, drafts, note)
        holdings[instrument] = HeldPosition(
            held - quantity,
            (position?.costMinor ?: 0L) - basis,
            costCommodity,
        )
        cash.bump(cashCommodity, net)
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
        }
        add(PlannedKind.Income, e, ref, drafts)
        cash.bump(e.cashCommodity, gross - tax)
    }

    private fun planTransfer(e: BrokerEvent.CashTransfer, ref: String) {
        val cashAccount = cashAccount(e.cashCommodity)
        val amount = minor(e.amount, e.cashCommodity)
        if (amount == 0L) throw PlanException("zero transfer")
        val transfers = accounts.transfers ?: throw PlanException("no transfers account for this broker")
        add(
            PlannedKind.Transfer, e, ref,
            listOf(
                draft(cashAccount, amount, e.cashCommodity),
                draft(transfers, -amount, e.cashCommodity),
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
            if (fees != 0L) add(draft(accounts.commissions, fees, e.fromCommodity))
        }
        add(PlannedKind.Fx, e, ref, drafts)
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
        )
        val position = holdings[e.instrument]
        holdings[e.instrument] = HeldPosition(
            (position?.quantityMinor ?: 0L) + delta,
            position?.costMinor ?: 0L,
            position?.costCommodity,
        )
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
        val quantityDelta = mutableMapOf<String, Decimal>()
        val cashDelta = mutableMapOf<String, Decimal>()
        fun q(instrument: String, d: Decimal) { quantityDelta[instrument] = (quantityDelta[instrument] ?: Decimal.ZERO) + d }
        fun c(commodity: String, d: Decimal) { cashDelta[commodity] = (cashDelta[commodity] ?: Decimal.ZERO) + d }
        for (event in events) {
            when (event) {
                is BrokerEvent.Trade -> {
                    q(event.instrument, event.quantity)
                    c(event.cashCommodity, if (event.quantity.signum > 0) -(event.gross + event.fees) else event.gross - event.fees)
                    for ((commodity, fee) in event.foreignFees) c(commodity, -fee)
                }
                is BrokerEvent.Income -> c(event.cashCommodity, event.gross - event.tax)
                is BrokerEvent.Principal -> {
                    // A whole-position redemption takes whatever is there, so
                    // the opening is the snapshot's (zero after maturity) plus
                    // what it took — unknown here; it's valued from the
                    // other events and the snapshot like any other holding.
                    event.quantity?.let { q(event.instrument, -it.abs()) }
                    c(event.cashCommodity, event.cash)
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
        val instruments = (snapshot.positions.map { it.instrument } + quantityDelta.keys).distinct()
        for (instrument in instruments) {
            val held = snapshot.positions.firstOrNull { it.instrument == instrument }
            val opening = redeemedOpening(instrument, events)
                ?: ((held?.quantity ?: Decimal.ZERO) - (quantityDelta[instrument] ?: Decimal.ZERO))
            if (opening.isZero) continue
            if (opening.signum < 0) {
                issues += PlanIssue(null, "opening: $instrument would open short (${opening.toPlainString()})")
                continue
            }
            val (cost, costCommodity) = openingCost(instrument, opening, held, events) ?: run {
                issues += PlanIssue(null, "opening: no price or cost to value ${opening.toPlainString()} $instrument")
                null
            } ?: continue
            val quantity = minor(opening, instrument)
            drafts += draft(accounts.holdings, quantity, instrument, cost, costCommodity)
            equity.bump(costCommodity, cost)
            holdings[instrument] = HeldPosition(quantity, cost, costCommodity)
        }
        for (commodity in (snapshot.cash.keys + cashDelta.keys).distinct()) {
            val opening = (snapshot.cash[commodity] ?: Decimal.ZERO) - (cashDelta[commodity] ?: Decimal.ZERO)
            val amount = minor(opening, commodity)
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
        val at = (events.minOfOrNull { it.at } ?: snapshot.at) - 1
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
            (it is BrokerEvent.Trade && it.instrument == instrument) ||
                (it is BrokerEvent.Principal && it.instrument == instrument) ||
                (it is BrokerEvent.QuantityChange && it.instrument == instrument)
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
     */
    private fun openingCost(
        instrument: String,
        opening: Decimal,
        held: SnapshotPosition?,
        events: List<BrokerEvent>,
    ): Pair<Long, String>? {
        val trades = events.filterIsInstance<BrokerEvent.Trade>().filter { it.instrument == instrument }
        val bought = trades.filter { it.quantity.signum > 0 }
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
        val instruments = (snapshot.positions.map { it.instrument } + holdings.keys).distinct().sorted()
        for (instrument in instruments) {
            val broker = snapshot.positions.firstOrNull { it.instrument == instrument }
                ?.let { minor(it.quantity, instrument) } ?: 0L
            val ledgerNow = holdings[instrument]?.quantityMinor ?: 0L
            if (broker != ledgerNow) result += BalanceDifference(accounts.holdings, instrument, ledgerNow, broker)
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

    private fun feeNote(fees: Decimal, commodity: String, foreign: Map<String, Decimal> = emptyMap()): String? {
        val parts = (listOf(commodity to fees) + foreign.toList())
            .filter { !it.second.isZero }
            .map { (c, f) -> formatMoney(minor(f, c), c) }
        return if (parts.isEmpty()) null else "Comisión " + parts.joinToString(" + ")
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

    private fun draft(account: String, amount: Long, commodity: String, cost: Long? = null, costCommodity: String? = null) =
        DraftPosting(
            accountId = account,
            amountText = formatMinorUnits(amount),
            commodity = commodity,
            costText = cost?.let { formatMinorUnits(it) }.orEmpty(),
            costCommodity = if (cost != null) costCommodity else null,
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

private fun MutableMap<String, Long>.bump(key: String, by: Long) {
    this[key] = (this[key] ?: 0L) + by
}

private class PlanException(message: String) : Exception(message)
