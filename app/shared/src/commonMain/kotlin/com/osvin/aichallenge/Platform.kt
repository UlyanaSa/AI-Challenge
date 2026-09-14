package com.osvin.aichallenge

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/**
 * Адрес сервера агента для текущей платформы.
 *
 * Android-эмулятор видит хост-машину по адресу 10.0.2.2, браузер и десктоп —
 * по localhost, поэтому адрес задаётся платформой, а не общим кодом.
 */
expect fun serverBaseUrl(): String
