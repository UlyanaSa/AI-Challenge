plugins {
    alias(libs.plugins.kotlinJvm)
    // Ответ GitHub разбирается в @Serializable-форму, поэтому сериализация — не плагин «на будущее».
    alias(libs.plugins.kotlinxSerialization)
}

group = "com.osvin.aichallenge"
version = "1.0.0"

/**
 * MCP-сервер GitHub: отдельный процесс, который отдаёт агенту репозитории пользователя.
 *
 * Почему отдельный модуль, а не инструмент внутри `:mcp`: у сервера проекта данные — правила
 * и профиль, у сервера GitHub — сеть и токен. Общий модуль связал бы их одним fat JAR и одной
 * точкой входа, а это разные процессы с разными настройками (у GitHub-сервера появляется
 * `GITHUB_TOKEN`, которого у сервера проекта нет вовсе). Транспорт, объявление инструмента
 * и режимы запуска при этом общие — отсюда зависимость на `:mcp`, а не копия `StdioServer`.
 *
 * Инструмент ходит в GitHub REST API по HTTP, поэтому нужен Ktor-клиент. Серверный движок
 * (Netty) не нужен: сервер общается с клиентом по stdio, а наружу сам ходит как клиент.
 */
dependencies {
    // JSON ответа GitHub и ответа инструмента.
    implementation(libs.kotlinx.serialization.json)
    // Общая часть MCP: объявление инструментов, сборка сервера и запуск на stdio. Через неё же
    // приходит MCP SDK и `:agent`.
    api(project(":mcp"))
    // HTTP-клиент наружу, в GitHub. Движок CIO: это JVM-процесс без Ktor-сервера, и CIO —
    // штатный клиентский движок Ktor, не тянущий за собой Netty.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.core)
    // Подставной движок Ktor: ответы GitHub и сетевые сбои проверяются без сети.
    testImplementation(libs.ktor.client.mock)
}

/**
 * Fat JAR с точкой входа сервера: клиент поднимает GitHub-сервер процессом.
 *
 * `java -cp mcp-github/build/libs/mcp-github-1.0.0-all.jar com.osvin.aichallenge.mcp.github.GitHubMcpServerKt`
 * — та же схема, что у сервера проекта (`:mcp`): токен и адрес API приходят переменными
 * окружения, а не аргументами, поэтому команда запуска годится и для конфига стороннего
 * MCP-клиента. Gradle-задача для запуска сервера не заводится: Gradle пишет в стандартный
 * вывод процесса свои строки, а вывод MCP-сервера — канал протокола.
 *
 * Fat JAR — отдельная задача ([fatJar]), а обычный [Jar] остаётся тонким. Это не украшение:
 * тонкий jar — основной артефакт модуля, и его получают те, кто зависит от `:mcp-github`
 * при компиляции (`:server`). Собранный fat JAR в этой роли ломает потребителя: внутри
 * лежат классы зависимостей, и компилятор Kotlin, ища версию kotlinx-serialization по классу
 * `Serializable` из первой записи classpath, читает её из манифеста этого jar (1.0.0 — версия
 * проекта) и отказывается собирать. Классификатор на основном [Jar] не спасает: он остаётся
 * артефактом модуля, и fat JAR всё равно уехал бы в compileClasspath, — поэтому нужна именно
 * отдельная задача, как и в `:mcp`.
 */
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Тонкий jar модуля не нужен для запуска: собирает выполнимый fat JAR сервера"
    archiveClassifier.set("all")

    manifest {
        attributes(
            "Main-Class" to "com.osvin.aichallenge.mcp.github.GitHubMcpServerKt",
            "Implementation-Version" to project.version
        )
    }

    from(sourceSets["main"].output)
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

/**
 * Инструменты сервера в консоли: `./gradlew :mcp-github:mcpTools`.
 *
 * Сервер запускается с флагом `--list-tools`, печатает объявленный инструмент и выходит.
 * Токен при этом не нужен: список берётся из объявлений, а в GitHub никто не ходит.
 */
val mcpTools by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Печатает инструменты, которые объявляет MCP-сервер GitHub"
    mainClass.set("com.osvin.aichallenge.mcp.github.GitHubMcpServerKt")
    classpath = sourceSets["main"].runtimeClasspath
    args("--list-tools")
    outputs.upToDateWhen { false }
}
