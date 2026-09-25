package ar.fausto.weil

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * IOL synced in the background (plans/inversiones-brokers.md, phase 4): the
 * same unattended [IolRepository.autoSync] the app runs on launch, every few
 * hours while the phone has a network. Routine movements land in the ledger;
 * anything that needs a person waits as an alert on the investments tab,
 * which this process keeps in memory until the app is opened (or, if the
 * process dies first, the next launch finds it again).
 *
 * The graph exists because WorkManager starts the process through
 * [WeilApplication.onCreate]. A signed-out app, or one without IOL
 * credentials on this device, makes this a no-op; failures are retried by
 * the next period rather than by WorkManager's backoff, since autoSync
 * already throttles itself.
 */
class IolSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = AndroidGraphHolder.graph ?: return Result.success()
        if (graph.auth.state.value !is AuthState.LoggedIn) return Result.success()
        graph.iol.autoSync()
        return Result.success()
    }

    companion object {
        private const val NAME = "iol-auto-sync"

        /** Idempotent: KEEP leaves an already scheduled period alone. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<IolSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
