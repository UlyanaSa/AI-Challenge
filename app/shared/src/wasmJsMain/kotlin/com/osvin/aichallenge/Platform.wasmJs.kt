package com.osvin.aichallenge

import com.osvin.aichallenge.data.SettingsStorage
import kotlin.JsFun
import kotlin.coroutines.resume
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * В Kotlin/Wasm нет ни `System.currentTimeMillis()`, ни `kotlin.js.Date`:
 * время epoch берём у браузера через JS-функцию, объявленную аннотацией
 * [JsFun]. `Date.now()` возвращает JS-число (Double), поэтому результат
 * приводим к миллисекундам в Long.
 */
@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => Date.now()")
private external fun jsDateNow(): Double

/**
 * Адрес страницы без параметров (`origin + pathname`) и её параметры (`search`).
 * Отдельные функции, потому что в Kotlin/Wasm браузерного API нет: `location` доступен
 * только объявлением через [JsFun], как и `Date.now()` выше.
 */
@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => window.location.origin + window.location.pathname")
private external fun jsAppAddress(): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => window.location.search")
private external fun jsLocationSearch(): String

/**
 * Буфер обмена браузера: кладём текст и дожидаемся ответа на запись.
 *
 * Ответ приходит промисом `writeText`, поэтому наружу отдаётся не «вызов сделан», а исход:
 * без этого окно говорило «Ссылка скопирована» при пустом буфере (страница не в фокусе —
 * обычное дело для фоновой вкладки, — нет разрешения, чужой фрейм). Неудача промиса
 * обрабатывается здесь же: непойманный отказ ронял страницу накладкой
 * «Uncaught runtime errors».
 */
@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(text, done) => { const c = (typeof navigator !== 'undefined' && navigator.clipboard) ? navigator.clipboard : null; if (!c || !c.writeText) { done(false); return; } c.writeText(text).then(() => done(true), () => done(false)); }")
private external fun jsCopyToClipboard(text: String, done: (Boolean) -> Unit)

class WasmPlatform: Platform {
    override val name: String = "Web with Kotlin/Wasm"
}

actual fun getPlatform(): Platform = WasmPlatform()
/** Сервер запущен на том же компьютере, что и браузер. */
actual fun serverBaseUrl(): String = "http://localhost:8080"

/** В браузере время epoch отдаёт `Date.now()`, объявленный через [JsFun]. */
actual fun nowMillis(): Long = jsDateNow().toLong()

/** Адрес страницы без параметров, объявленный через [JsFun]: из него собирается ссылка на чат. */
actual fun appAddress(): String? = jsAppAddress()

/** Параметры адреса страницы, объявленные через [JsFun]: по `?chat=<id>` открывается чат (§5). */
actual fun openedChatId(): String? = chatIdFromUrl(jsLocationSearch())

/** Буфер обмена браузера, объявленный через [JsFun]: ждёт ответа на запись. */
actual suspend fun copyToClipboard(text: String): Boolean = suspendCancellableCoroutine { cont ->
    jsCopyToClipboard(text) { copied -> if (cont.isActive) cont.resume(copied) }
}

/**
 * Хранилище настроек сервиса: `localStorage` браузера, объявленный через [JsFun].
 *
 * Объявлений три, а не одно с возвратом значения, потому что у `localStorage` нет
 * операции «прочитать или записать»: чтение, запись и удаление — разные вызовы.
 * Отсутствующая запись отдаётся пустой строкой, а не `null`: так разбор читается
 * без лишнего шага и совпадает с тем, что о разделении «нет записи» и «пустая строка»
 * сказано в [SettingsStorage].
 *
 * Долговечность `localStorage` не наша: браузер сам решает, что делать с хранилищем
 * при переполнении или в приватном окне, и обещать здесь больше браузера нельзя.
 */
private class WasmSettingsStorage : SettingsStorage {

    override fun read(key: String): String? = jsLocalStorageGet(key).takeIf { it.isNotEmpty() }

    override fun write(key: String, value: String?) {
        if (value == null) jsLocalStorageRemove(key) else jsLocalStorageSet(key, value)
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(key) => { const value = window.localStorage.getItem(key); return value === null ? '' : value; }")
private external fun jsLocalStorageGet(key: String): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(key, value) => { window.localStorage.setItem(key, value); }")
private external fun jsLocalStorageSet(key: String, value: String)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(key) => { window.localStorage.removeItem(key); }")
private external fun jsLocalStorageRemove(key: String)

/** Настройки сервиса переживают перезапуск страницы в `localStorage` браузера. */
actual fun platformSettingsStorage(): SettingsStorage = WasmSettingsStorage()
