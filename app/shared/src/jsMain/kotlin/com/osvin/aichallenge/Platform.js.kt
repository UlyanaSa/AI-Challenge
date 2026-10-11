package com.osvin.aichallenge

import kotlin.js.Date
import web.navigator.navigator

class JsPlatform: Platform {
    private val userAgent = navigator.userAgent
    private val browserList = listOf("Chrome", "Firefox", "Safari", "Edge")

    override val name: String = userAgent.findAnyOf(browserList, ignoreCase = true)
            ?.let { (startIndex) -> userAgent.substring(startIndex).substringBefore(" ") }
            ?: "Unknown"
}

actual fun getPlatform(): Platform = JsPlatform()
/** Сервер запущен на том же компьютере, что и браузер. */
actual fun serverBaseUrl(): String = "http://localhost:8080"

/** В браузере время epoch отдаёт `Date.now()` — сразу в миллисекундах. */
actual fun nowMillis(): Long = Date.now().toLong()
