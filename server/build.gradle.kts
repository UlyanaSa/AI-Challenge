plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinxSerialization)
   // alias(libs.plugins.shadow)
}

group = "com.osvin.aichallenge"
version = "1.0.0"

application {
    mainClass = "com.osvin.aichallenge.ApplicationKt"
}

dependencies {
    api(project(":core"))
    implementation(project(":agent"))
    // Сервер инструментов поднимается из своего же classpath, поэтому модуль инструментов
    // нужен и как зависимость (имя точки входа), и как классы для запуска процесса.
    implementation(project(":mcp"))
    implementation(project(":mcp-github"))
    // Сервис курсов поднимается так же — из своего classpath, поэтому модуль нужен и как
    // зависимость (имя точки входа), и как классы для запуска процесса. Его собственные
    // зависимости (драйвер SQLite, HTTP-клиент) приходят вместе с ним: процесс сервиса
    // работает на том же classpath, что и сервер приложения.
    implementation(project(":currency-monitor"))
    // Сервер пайплайна поднимается так же — из своего classpath: модуль нужен и как зависимость
    // (имя точки входа), и как классы процесса, который читает базу службы курсов и пишет отчёты.
    implementation(project(":mcp-pipeline"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.dotenv.kotlin)
    implementation(libs.logback)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
}

// ✅ Альтернативный способ создания fat JAR (без Shadow)
tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.osvin.aichallenge.ApplicationKt",
            "Implementation-Version" to project.version
        )
    }

    // Включаем все зависимости в JAR
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

//// Настройка shadow JAR
//tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
//    archiveBaseName.set("deepseek-backend")
//    archiveClassifier.set("")
//    archiveVersion.set(project.version.toString())
//
//    manifest {
//        attributes(
//            "Main-Class" to "com.osvin.aichallenge.ApplicationKt",
//            "Implementation-Version" to project.version
//        )
//    }
//
//    mergeServiceFiles()
//    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
//}

// Задача для разработки
tasks.register<JavaExec>("runDev") {
    group = "application"
    description = "Run in development mode with .env"
    mainClass.set("com.osvin.aichallenge.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath

    // Загрузка .env файла
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key, value)
            }
    }
}
/**
 * Живой режим демонстрации — те же имена свойств, что у прочих демонстраций проекта
 * (`:agent:demoLogs`): флаг `-Pdemo.live=1` и ключ из `server/.env` (или `-Pdemo.api.key=…`)
 * пробрасываются в JVM тестов. Без флага демонстрация инструментов не ходит в сеть.
 */
tasks.withType<Test>().configureEach {
    (project.findProperty("demo.live") as String?)?.let { systemProperty("demo.live", it) }
    val keyFromEnvFile = file(".env").takeIf { it.isFile }
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
        // Живой прогон всегда исполняется заново: иначе Gradle отдаёт UP-TO-DATE и молчит.
        outputs.upToDateWhen { false }
    }
}

/**
 * Демонстрация дня 17: агент сам вызывает инструмент MCP-сервера GitHub.
 *
 * Клиент инструментов — тот же, что у сервера приложения ([GitHubTools]): процесс сервера
 * поднимается по протоколу MCP, модель живая, GitHub подставной (токена в окружении нет,
 * адрес API подставляется переменной `GITHUB_API_BASE`). Три просьбы — все репозитории,
 * публичные, приватные — печатаются в консоль вместе с логами агента.
 *
 * `./gradlew :server:githubDemo -Pdemo.live=1` — живой прогон, ключ из `server/.env`;
 * без флага задача только напоминает, как её запускать.
 */
val githubDemo by tasks.registering(Test::class) {
    group = "verification"
    description = "Прогон демонстрации инструмента GitHub: агент сам вызывает инструмент MCP-сервера"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("com.osvin.aichallenge.GitHubToolsDemoTest") }
    // Только печать демонстрации: статусы тестов в консоль не нужны.
    testLogging { events("standardOut") }
    outputs.upToDateWhen { false }
}

/**
 * Демонстрация дня 19: модель сама собирает цепочку из трёх серверов инструментов пайплайна.
 *
 * Сервер инструментов — тот же, что у приложения ([PipelineTools]): процесс поднимается
 * по протоколу MCP, история курсов — временная база с одной вставкой, файл пишется настоящий.
 * Цепочку собирает модель по описаниям инструментов: в просьбе нет ни их имён, ни порядка,
 * а проверяется и порядок вызовов, и то, что данные дошли между шагами до файла.
 *
 * `./gradlew :server:pipelineDemo -Pdemo.live=1` — живой прогон, ключ из `server/.env`;
 * без флага задача только напоминает, как её запускать.
 */
val pipelineDemo by tasks.registering(Test::class) {
    group = "verification"
    description = "Прогон демонстрации пайплайна: цепочку «курсы → сводка → файл» собирает модель"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("com.osvin.aichallenge.PipelineDemoTest") }
    // Только печать демонстрации: статусы тестов в консоль не нужны.
    testLogging { events("standardOut") }
    outputs.upToDateWhen { false }
}
