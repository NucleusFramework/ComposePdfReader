package dev.nucleusframework.pdf

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform