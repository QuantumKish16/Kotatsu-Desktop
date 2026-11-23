package com.kishore.kotatsu

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform