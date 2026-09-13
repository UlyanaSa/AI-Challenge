plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
}

group = "com.osvin.aichallenge"
version = "1.0.0"

dependencies {
    // Транспорт к LLM API: агент формулирует запрос, сервер отдаёт HTTP-клиент.
    api(libs.ktor.client.core)
    // DTO запроса/ответа DeepSeek и JSON-режим формата в публичном API агента.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.core)
}
