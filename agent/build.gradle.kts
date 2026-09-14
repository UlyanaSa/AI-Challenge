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
    // Живой прогон демонстрации (`:agent:demoLogs -Pdemo.live=1`): тот же стек, что у сервера.
    testImplementation(libs.bundles.ktor.client)
    testImplementation(libs.ktor.serialization.kotlinx.json)
}

/**
 * Живой режим демонстрации для всех тестовых задач.
 *
 * Флаг `-Pdemo.live=1` и ключ из `server/.env` (или `-Pdemo.api.key=…`) пробрасываются
 * в JVM тестов: `./gradlew :agent:test -Pdemo.live=1` и `./gradlew :agent:demoLogs -Pdemo.live=1`
 * идут на живой API, без флага — на подставленном ответе и без сети.
 */
tasks.withType<Test>().configureEach {
    (project.findProperty("demo.live") as String?)?.let { systemProperty("demo.live", it) }
    val keyFromEnvFile = rootProject.file("server/.env").takeIf { it.isFile }
        ?.readLines()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("DEEPSEEK_API_KEY=") }
        ?.substringAfter('=')
        ?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotEmpty() }
    ((project.findProperty("demo.api.key") as String?)?.takeIf { it.isNotBlank() } ?: keyFromEnvFile)
        ?.let { systemProperty("demo.api.key", it) }
    if (project.findProperty("demo.live") == "1") {
        // Живой прогон печатает в консоль статусы тестов и логи демонстрации,
        // и всегда исполняется заново: иначе Gradle отдаёт UP-TO-DATE и молчит.
        outputs.upToDateWhen { false }
        testLogging {
            events("started", "passed", "failed", "skipped", "standardOut")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

/**
 * Демонстрация расхода токенов: печатает логи агента в консоль.
 *
 * `./gradlew :agent:demoLogs` — без сети, ответ подставлен;
 * `./gradlew :agent:demoLogs -Pdemo.live=1` — живые вызовы API, ключ берётся из `server/.env`.
 */
val demoLogs by tasks.registering(Test::class) {
    group = "verification"
    description = "Прогон демонстрации расхода токенов с печатью логов агента"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("com.osvin.aichallenge.agent.TokenBudgetDemoTest") }
    // Только печать демонстрации: предупреждения транспорта в консоль не мешают.
    testLogging { events("standardOut") }
    outputs.upToDateWhen { false }
}
