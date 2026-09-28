plugins {
    alias(libs.plugins.kotlinJvm)
    // Ответ инструментов и разбор ответа предыдущего шага — JSON: сводка считается по тому,
    // что вернул первый инструмент, и разбор идёт по ключам.
    alias(libs.plugins.kotlinxSerialization)
}

group = "com.osvin.aichallenge"
version = "1.0.0"

/**
 * MCP-сервер пайплайна: три инструмента, которые агент вызывает цепочкой — курсы, сводка, файл.
 *
 * Модуль отдельный, а не три инструмента внутри `:currency-monitor`, по трём причинам. Первая:
 * у него другой предмет — обработка и запись файла, а не сбор курсов; служба курсов о файлах
 * не знает и знать не должна. Вторая: у него своя настройка (каталог отчётов) и своё право
 * не писать в историю — он только читает. Третья: он нужен агенту как отдельный сервер
 * инструментов, и тогда объявление цепочки видно целиком в одном месте, а не разбросано
 * между сбором курсов и записью на диск.
 *
 * Инструменты курсов остаются у службы (`:currency-monitor`): они — её часть, отвечают её
 * данными и работают на её процессе. Здесь объявлено то, чего у службы нет.
 */
dependencies {
    // Разбор ответа первого шага цепочки и сборка ответов инструментов.
    implementation(libs.kotlinx.serialization.json)
    // Общая часть MCP: объявление инструментов, сборка сервера и запуск на stdio.
    api(project(":mcp"))
    // История курсов читается тем же хранилищем, что у службы: второй SQL по той же таблице
    // разошёлся бы с ней молча, и пайплайн складывал бы в файл не те курсы, которые видит лента.
    implementation(project(":currency-monitor"))
    implementation(libs.kotlinx.coroutines.core)
    // Драйвер SQLite нужен процессу инструментов: он читает базу службы в своём процессе.
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.core)
}

/**
 * Fat JAR с точкой входа сервера пайплайна: один файл для запуска и для конфига MCP-клиента.
 *
 * `java -jar mcp-pipeline-1.0.0-all.jar` поднимает сервер на stdio, `--list-tools` печатает
 * инструменты и выходит. Отдельная задача, как у остальных серверов проекта: `application`-плагин
 * завёл бы второй способ запуска и разошёлся бы с тем, как сервер поднимает клиент.
 */
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Собирает выполнимый fat JAR сервера пайплайна"
    archiveClassifier.set("all")

    manifest {
        attributes(
            "Main-Class" to "com.osvin.aichallenge.pipeline.ApplicationKt",
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
 * Инструменты пайплайна в консоли: `./gradlew :mcp-pipeline:mcpTools`.
 *
 * База и каталог отчётов при этом не нужны: список берётся из объявлений, а не из поднятого
 * сервера, — так же, как у остальных серверов проекта.
 */
val mcpTools by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Печатает инструменты, которые объявляет MCP-сервер пайплайна"
    mainClass.set("com.osvin.aichallenge.pipeline.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath
    args("--list-tools")
    outputs.upToDateWhen { false }
}
