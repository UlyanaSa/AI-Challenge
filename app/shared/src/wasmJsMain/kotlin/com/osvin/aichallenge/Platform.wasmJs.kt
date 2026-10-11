package com.osvin.aichallenge

import kotlin.JsFun
import kotlin.js.ExperimentalWasmJsInterop

/**
 * В Kotlin/Wasm нет ни `System.currentTimeMillis()`, ни `kotlin.js.Date`:
 * время epoch берём у браузера через JS-функцию, объявленную аннотацией
 * [JsFun]. `Date.now()` возвращает JS-число (Double), поэтому результат
 * приводим к миллисекундам в Long.
 */
@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => Date.now()")
private external fun jsDateNow(): Double

class WasmPlatform: Platform {
    override val name: String = "Web with Kotlin/Wasm"
}

actual fun getPlatform(): Platform = WasmPlatform()
/** Сервер запущен на том же компьютере, что и браузер. */
actual fun serverBaseUrl(): String = "http://localhost:8080"

/** В браузере время epoch отдаёт `Date.now()`, объявленный через [JsFun]. */
actual fun nowMillis(): Long = jsDateNow().toLong()
