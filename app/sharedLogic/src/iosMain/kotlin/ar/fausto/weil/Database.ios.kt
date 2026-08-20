package ar.fausto.weil

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.readValue
import swiftPMImport.Weil.app.app.sharedLogic.libsql_connection_deinit
import swiftPMImport.Weil.app.app.sharedLogic.libsql_connection_t
import swiftPMImport.Weil.app.app.sharedLogic.libsql_database_connect
import swiftPMImport.Weil.app.app.sharedLogic.libsql_database_deinit

import swiftPMImport.Weil.app.app.sharedLogic.libsql_database_desc_t
import swiftPMImport.Weil.app.app.sharedLogic.libsql_database_init
import swiftPMImport.Weil.app.app.sharedLogic.libsql_database_t

@OptIn(ExperimentalForeignApi::class)
class IOSDatabase : Database {
    var db: CValue<libsql_database_t>? = null
    var connection: CValue<libsql_connection_t>? = null

    init {
        val description = nativeHeap.alloc<libsql_database_desc_t>()
        db = libsql_database_init(description.readValue())
        nativeHeap.free(description)
        db?.let { connection = libsql_database_connect(it) }
    }

    fun close() {
        connection?.let { libsql_connection_deinit(it) }
        db?.let { libsql_database_deinit(it) }
    }

    override fun sync() {
        TODO("Not yet implemented")
    }

    override fun execute(sql: String) {
        TODO("Not yet implemented")
    }

    override fun <T> query(
        sql: String,
        params: Map<String, Any>?,
        block: (Sequence<Row>) -> T,
    ): T {
        TODO("Not yet implemented")
    }
}