package ar.fausto.weil

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform