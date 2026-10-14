package com.osvin.aichallenge

import android.os.Build
import com.osvin.aichallenge.data.InMemorySettingsStorage
import com.osvin.aichallenge.data.SettingsStorage

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

/** На эмуляторе хост-машина доступна по адресу 10.0.2.2. */
actual fun serverBaseUrl(): String = "http://10.0.2.2:8080"

/** На Android время epoch дают системные часы JVM. */
actual fun nowMillis(): Long = System.currentTimeMillis()

/**
 * Своего адреса у Android-приложения нет: его запускают не по ссылке, и собирать
 * ссылку на чат не из чего — источник истины по чатам открывают по идентификатору.
 */
actual fun appAddress(): String? = null

/** Адресных параметров у Android-приложения нет: `?chat=<id>` приходит только браузеру. */
actual fun openedChatId(): String? = null

/**
 * Буфера обмена здесь нет: Android отдаёт его через `Context`, а общий код его не видит
 * (ни одного держателя контекста в слое репозитория нет намеренно). Ссылку поэтому
 * показывают текстом, который можно выделить.
 */
actual suspend fun copyToClipboard(text: String): Boolean = false

/**
 * Хранилище настроек сервиса у Android-приложения подставляет его точка входа:
 * `SharedPreferences` требует `Context`, а держателя контекста в общем слое нет намеренно —
 * тем же счётом, что и с буфером обмена выше. Запасной вариант здесь — память процесса:
 * настройки, положенные сюда, живут до конца запуска, и молча обещать больше нельзя.
 */
actual fun platformSettingsStorage(): SettingsStorage = InMemorySettingsStorage()
