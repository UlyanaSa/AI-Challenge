package com.osvin.aichallenge

class WasmPlatform: Platform {
    override val name: String = "Web with Kotlin/Wasm"
}

actual fun getPlatform(): Platform = WasmPlatform()
/** Сервер запущен на том же компьютере, что и браузер. */
actual fun serverBaseUrl(): String = "http://localhost:8080"
