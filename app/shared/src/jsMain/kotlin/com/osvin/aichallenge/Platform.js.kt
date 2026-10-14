package com.osvin.aichallenge

import kotlin.js.Date
import kotlinx.browser.window
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

/**
 * Адрес страницы без параметров: из него собирается ссылка на чат (§5).
 * `pathname` берётся вместе с `origin`: приложение может лежать не в корне сайта.
 */
actual fun appAddress(): String? = window.location.origin + window.location.pathname

/** Параметры адреса страницы: по `?chat=<id>` приложение открывает чат сервиса (§5). */
actual fun openedChatId(): String? = chatIdFromUrl(window.location.search)

/**
 * Буфер обмена браузера. Промис `writeText` переживает вызов, поэтому успехом считается
 * сам вызов: отказ (например, страница не в защищённом контексте) приходит уже после
 * возврата, и тогда человек увидит ссылку текстом и скопирует её руками.
 */
actual fun copyToClipboard(text: String): Boolean = runCatching {
    navigator.clipboard.writeTextAsync(text)
}.isSuccess
