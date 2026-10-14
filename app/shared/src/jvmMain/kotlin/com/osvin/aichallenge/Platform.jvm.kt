package com.osvin.aichallenge

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

/** Десктопный запуск ходит в сервер на том же компьютере. */
actual fun serverBaseUrl(): String = "http://localhost:8080"

/** Своего адреса у десктопного запуска нет: его открывают не по ссылке, ссылку собирать не из чего. */
actual fun appAddress(): String? = null

/** Адресных параметров у десктопного запуска нет: `?chat=<id>` приходит только браузеру. */
actual fun openedChatId(): String? = null

/**
 * Системный буфер AWT здесь не подключён: десктопный таргет не собирается, и непроверенная
 * обвязка буфера осталась бы непроверенной. Ссылку поэтому показывают текстом для выделения.
 */
actual fun copyToClipboard(text: String): Boolean = false
