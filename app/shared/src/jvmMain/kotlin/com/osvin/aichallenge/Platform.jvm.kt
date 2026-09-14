package com.osvin.aichallenge

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

/** Десктопный запуск ходит в сервер на том же компьютере. */
actual fun serverBaseUrl(): String = "http://localhost:8080"
