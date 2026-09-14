package com.osvin.aichallenge

import platform.UIKit.UIDevice

class IOSPlatform : Platform {
    override val name: String =
        UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()
/** Сервер запущен на том же компьютере, что и браузер. */
actual fun serverBaseUrl(): String = "http://localhost:8080"
