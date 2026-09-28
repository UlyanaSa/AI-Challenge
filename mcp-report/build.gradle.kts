plugins {
    alias(libs.plugins.kotlinJvm)
    // Секции приходят строкой JSON, а ответ инструмента создания — JSON с markdown: и разбор,
    // и сборка идут по ключам, поэтому плагин тот же, что у сервера пайплайна.
    alias(libs.plugins.kotlinxSerialization)
}

group = "com.osvin.aichallenge"
version = "1.0.0"

/**
 * MCP-сервер отчётов: два инструмента, которыми агент собирает отчёт и кладёт его в файл.
 *
 * Модуль отдельный, а не пара инструментов внутри `:mcp-pipeline`, по двум причинам. Первая:
 * у него другой предмет — отчёт по собранному материалу, а не обработка курсов; пайплайн про
 * отчёты ничего не знает и знать не должен, а общий список инструментов смешал бы две предметные
 * области в одном сервере. Вторая: оркестрация нескольких серверов — то, ради чего модуль и
 * заведён; отдать отчёты чужому серверу значило бы показать в дне оркестрации один сервер
 * вместо нескольких.
 *
 * Ни хранилища, ни службы за модулем нет: сервер только собирает markdown и пишет файл. Поэтому
 * в зависимостях нет ни `:currency-monitor`, ни драйвера SQLite — сервер, которому нечего читать,
 * не тащит в свой процесс чужую базу.
 */
dependencies {
    // Разбор секций из вызова и сборка ответов инструментов.
    implementation(libs.kotlinx.serialization.json)
    // Общая часть MCP: объявление инструментов, сборка сервера и запуск на stdio.
    api(project(":mcp"))
    implementation(libs.kotlinx.coroutines.core)
}

/**
 * Fat JAR с точкой входа сервера отчётов: один файл для запуска и для конфига MCP-клиента.
 *
 * `java -jar mcp-report-1.0.0-all.jar` поднимает сервер на stdio, `--list-tools` печатает
 * инструменты и выходит. Отдельная задача, как у остальных серверов проекта: `application`-плагин
 * завёл бы второй способ запуска и разошёлся бы с тем, как сервер поднимает клиент.
 */
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Собирает выполнимый fat JAR сервера отчётов"
    archiveClassifier.set("all")

    manifest {
        attributes(
            "Main-Class" to "com.osvin.aichallenge.report.ApplicationKt",
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
 * Инструменты отчётов в консоли: `./gradlew :mcp-report:mcpTools`.
 *
 * Каталог отчётов при этом не нужен и не создаётся: список берётся из объявлений, а не из
 * поднятого сервера, — так же, как у остальных серверов проекта.
 */
val mcpTools by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Печатает инструменты, которые объявляет MCP-сервер отчётов"
    mainClass.set("com.osvin.aichallenge.report.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath
    args("--list-tools")
    outputs.upToDateWhen { false }
}
