package ar.fausto.weil

import android.content.Context

/**
 * Single AppGraph shared by MainActivity and the notification listener service.
 * Two graphs would mean two AndroidDatabase instances on the same turso.db file.
 */
object AndroidGraphHolder {
    @Volatile
    var appContext: Context? = null
        private set

    @Volatile
    var graph: AppGraph? = null
        private set

    fun start(context: Context, graphFactory: () -> AppGraph) {
        appContext = context.applicationContext
        if (graph == null) {
            graph = graphFactory()
        }
    }
}
