package com.osvin.aichallenge

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
