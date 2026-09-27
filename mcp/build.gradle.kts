plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "com.osvin.aichallenge"
version = "1.0.0"

/**
 * MCP-слой проекта: клиент и сервер протокола в одном модуле, но не внутри агента
 * и не внутри серверного приложения.
 *
 * Клиент и сервер MCP не принадлежат ни агенту, ни Ktor-приложению:
 * - агент — это логика работы с моделью ([InvariantStore], профиль, память, задача),
 *   и он о MCP не знает вовсе — иначе протокол оказался бы внутри домена;
 * - серверное приложение — это HTTP-маршруты для приложения и человека, и заменять
 *   его MCP нельзя: у них разные потребители (см. README, `### task-16`).
 *
 * Данные проекту нужны этому модулю потому, что их он и отдаёт инструментами:
 * отсюда зависимость на `:agent`, а не наоборот.
 */
dependencies {
    // Данные, которые MCP-сервер объявляет инструментами: правила проекта и профиль.
    api(project(":agent"))
    // Клиент и сервер SDK: сессия отдаёт протокольный клиент наружу, сервер — тип Server.
    api(libs.mcp.client)
    api(libs.mcp.server)
    // Кадры протокола MCP идут по stdio — потоками kotlinx-io, а не строками.
    implementation(libs.kotlinx.io.core)
    // Логи MCP-сервера: stdout занят протоколом, поэтому аппендер настраивается на stderr.
    implementation(libs.logback)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.core)
}

/**
 * Сервер MCP запускают процессом, а не задачей Gradle.
 *
 * Поэтому у модуля есть fat JAR с точкой входа сервера: клиент поднимает
 * `java -cp mcp/build/libs/mcp-1.0.0.jar com.osvin.aichallenge.mcp.ProjectMcpServerKt`.
 * Задача Gradle для запуска сервера не заводится намеренно: Gradle пишет свои строки
 * в стандартный вывод процесса, а вывод MCP-сервера — это канал протокола, и чужая
 * строка ломает кадр. Тот же jar годится для конфига стороннего клиента (Claude Desktop
 * и подобных), которому нужна команда запуска.
 */
tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.osvin.aichallenge.mcp.ProjectMcpServerKt",
            "Implementation-Version" to project.version
        )
    }

    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

/**
 * Демонстрация подключения к MCP: печатает список инструментов сервера.
 *
 * `./gradlew :mcp:mcpDemo` — клиент поднимает локальный MCP-сервер проекта процессом,
 * проходит рукопожатие и печатает инструменты, которые тот объявил.
 */
val mcpDemo by tasks.registering(Test::class) {
    group = "verification"
    description = "Прогон подключения к MCP-серверу с печатью списка инструментов"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("com.osvin.aichallenge.mcp.McpDemoTest") }
    // Только печать демонстрации: статусы тестов в консоль не нужны.
    testLogging { events("standardOut") }
    outputs.upToDateWhen { false }
}
