package ar.fausto.weil

interface PasskeyCeremony {
    suspend fun create(optionsJson: String): String
    suspend fun assert(optionsJson: String): String
}
