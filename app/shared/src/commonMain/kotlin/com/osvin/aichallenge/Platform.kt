package com.osvin.aichallenge

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform