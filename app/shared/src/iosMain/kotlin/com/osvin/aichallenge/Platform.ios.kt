package com.osvin.aichallenge

import com.osvin.aichallenge.data.InMemorySettingsStorage
import com.osvin.aichallenge.data.SettingsStorage
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

class IOSPlatform : Platform {
    override val name: String =
        UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()
/** Сервер запущен на том же компьютере, что и браузер. */
actual fun serverBaseUrl(): String = "http://localhost:8080"

/**
 * В iOS время epoch возвращает `NSDate`: он считает секунды с дробной частью,
 * поэтому их умножаем на 1000 и отбрасываем доли миллисекунды.
 */
actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/**
 * Своего адреса у приложения iOS нет: запускают его не по ссылке, и собирать ссылку
 * на чат не из чего — чат открывают по идентификатору.
 */
actual fun appAddress(): String? = null

/** Адресных параметров у приложения iOS нет: `?chat=<id>` приходит только браузеру. */
actual fun openedChatId(): String? = null

/**
 * Буфер обмена iOS (`UIPasteboard`) здесь не подключён: iOS-сборка клиента в этой задаче
 * не собирается (§7), а непроверенная обвязка буфера осталась бы непроверенной и на живом
 * устройстве. Ссылку поэтому показывают текстом, который можно выделить.
 */
actual suspend fun copyToClipboard(text: String): Boolean = false

/**
 * Сборка iOS в этой задаче не собирается (§7), поэтому и своего хранилища настроек
 * у неё нет: непроверенная обвязка `NSUserDefaults` осталась бы непроверенной, как
 * и буфер обмена выше. Запасной вариант — память процесса: настройки живут до конца
 * запуска, и обещать больше нечем.
 */
actual fun platformSettingsStorage(): SettingsStorage = InMemorySettingsStorage()
