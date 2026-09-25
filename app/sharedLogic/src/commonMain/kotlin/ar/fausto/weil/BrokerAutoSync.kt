package ar.fausto.weil

/*
 * Syncing a broker without anyone watching (plans/inversiones-brokers.md,
 * phase 4, "sync automático"). The review screen exists for the cases a
 * person has to decide; a buy that IOL reports and the planner books
 * cleanly is not one of them — it is the broker saying what happened, the
 * same reason a bank push is recorded without a queue (AutoRecordRepository).
 *
 * So an unattended sync writes a plan only when it is routine, and hands
 * everything else back as data for the tab to show:
 *  - issues (an event the planner could not book) → the whole plan waits
 *    for review: writing the rest would leave the ledger half a story;
 *  - an opening → review: that is the first import, the one with numbers
 *    the user has never seen in the app;
 *  - a transfer still pointing at the in-transit account → review;
 *  - differences between ledger and broker → never written by anyone
 *    (decision 5), shown as an alert that opens the review with them.
 */

/** What an unattended sync did, for the tab's alerts and the snackbar. */
sealed interface BrokerAutoSync {
    /** Wrote [transactionIds] (possibly none); [plan] still carries the differences. */
    data class Applied(val plan: BrokerPlan, val transactionIds: List<String>) : BrokerAutoSync {
        val differences: List<BalanceDifference> get() = plan.differences
    }

    /** Nothing written: the plan needs a person. */
    data class NeedsReview(val plan: BrokerPlan) : BrokerAutoSync

    /** The broker refused the stored credentials. */
    data object WrongCredentials : BrokerAutoSync

    /** Network or broker trouble; retried on the next occasion, never shown. */
    data class Failed(val message: String) : BrokerAutoSync
}

/** True when [plan] can be written with nobody reviewing it; see the file comment. */
fun isRoutine(plan: BrokerPlan): Boolean =
    plan.issues.isEmpty() && plan.transactions.none { it.kind == PlannedKind.Opening || it.needsCounterpart }

/** Unattended syncs no more often than this, measured from the last applied one. */
const val BROKER_AUTO_SYNC_INTERVAL_MS = 30L * 60 * 1000
