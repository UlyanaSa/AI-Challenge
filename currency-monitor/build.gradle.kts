plugins {
    alias(libs.plugins.kotlinJvm)
    // Ответ поставщика курсов и ответ инструмента — JSON, поэтому сериализация нужна и в коде,
    // а не только на проводе: разбор чужого ответа идёт по ключам, и форма ответа описана здесь.
    alias(libs.plugins.kotlinxSerialization)
}

group = "com.osvin.aichallenge"
version = "1.0.0"

/**
 * Сервис мониторинга курсов валют: сбор по расписанию, история в SQLite и MCP-инструменты.
 *
 * Модуль отдельный, а не инструмент внутри `:mcp-github` или `:server`, потому что это
 * единственная часть проекта, которая живёт сама по себе: на VPS он запускается одним
 * процессом, собирает курсы по часам независимо от того, подключён ли клиент, и у него своё
 * хранилище. Общий модуль связал бы его одним fat JAR и одной точкой входа с сервером
 * инструментов GitHub, а у них разные источники данных, разные настройки и разное время жизни.
 *
 * Планировщик и MCP-сервер живут в одном процессе намеренно: MCP на stdio — это процесс,
 * которым владеет подключившийся клиент, и «сервер отдельно, сбор отдельно» потребовало бы
 * второго процесса и связи между ними. Агент при этом ничего не теряет: сбор идёт и без
 * клиента (сбор идёт по расписанию, а не по вызову инструмента), поэтому «независимо от
 * клиентского приложения» выполняется и так.
 *
 * Хранилище — SQLite через JDBC, без ORM. Таблица одна, запросов три, и вся выборка — это
 * «последняя запись по валюте» и «записи за окно»: ORM здесь дала бы разбор схемы и прокси
 * ради запросов, которые видно целиком. Room не подходит: это Android-библиотека с KSP,
 * а модуль живёт на JVM. Курсы хранятся текстом (десятичное значение как есть), а не числом
 * с плавающей точкой: `REAL` в SQLite — это `Double`, и 95.42 из него возвращается уже
 * не тем же числом, а деньги округлять при чтении нечем.
 */
dependencies {
    // Разбор ответа поставщика и сборка ответа инструментов.
    implementation(libs.kotlinx.serialization.json)
    // Общая часть MCP: объявление инструментов, сборка сервера и запуск на stdio.
    api(project(":mcp"))
    // Планировщик и обращение к сети — корутины.
    implementation(libs.kotlinx.coroutines.core)
    // HTTP-клиент наружу, к поставщику курсов. Движок CIO: это JVM-процесс без Ktor-сервера.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    // Драйвер SQLite: в модуле, а не в `:server`, потому что история курсов принадлежит
    // этому сервису. Заодно драйвер попадает в classpath того процесса, который инструменты
    // поднимают из `:server` (сервер инструментов запускается тем же classpath).
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.core)
    // Подставной движок Ktor: ответы поставщика и сетевые сбои проверяются без сети.
    testImplementation(libs.ktor.client.mock)
}

/**
 * Fat JAR с точкой входа сервиса: на VPS запускается один файл, без Gradle и без каталога сборки.
 *
 * `java -jar currency-monitor-1.0.0-all.jar` — рабочая команда, она же команда для MCP-клиента
 * (сервер говорит на stdio, поэтому та же команда годится конфигу стороннего клиента).
 * `--list-tools` печатает инструменты и выходит, `--once` делает одно обновление и выходит.
 *
 * Задача Gradle для запуска сервиса не заводится по той же причине, что у MCP-серверов проекта:
 * Gradle пишет свои строки в стандартный вывод процесса, а стандартный вывод сервера — это
 * канал протокола.
 *
 * Fat JAR — отдельная задача ([fatJar]), а обычный [Jar] остаётся тонким: тонкий jar берут те,
 * кто зависит от модуля при компиляции (`:server` — за именем класса точки входа), и fat JAR
 * в этой роли ломает им сборку (внутри лежат классы зависимостей с чужими версиями в манифесте).
 */
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Тонкий jar модуля не нужен для запуска: собирает выполнимый fat JAR сервиса"
    archiveClassifier.set("all")

    manifest {
        attributes(
            "Main-Class" to "com.osvin.aichallenge.currency.ApplicationKt",
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
 * Инструменты сервиса в консоли: `./gradlew :currency-monitor:mcpTools`.
 *
 * Тот же процесс, но с флагом `--list-tools`: печатает то, что сервер объявляет клиенту, и
 * выходит. База и сеть при этом не нужны — список берётся из объявлений.
 */
val mcpTools by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Печатает инструменты, которые объявляет MCP-сервер курсов"
    mainClass.set("com.osvin.aichallenge.currency.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath
    args("--list-tools")
    outputs.upToDateWhen { false }
}

/**
 * Одно обновление курсов: `./gradlew :currency-monitor:currencyOnce`.
 *
 * Рабочий режим сервиса без расписания: сходить к поставщику, сохранить, напечатать что
 * получилось и выйти. Тем же режимом пользуются демонстрации: он не ждёт часа, а показывает
 * тот же путь — поставщик, проверка, запись в историю.
 */
val currencyOnce by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Одно обновление курсов с записью в историю"
    mainClass.set("com.osvin.aichallenge.currency.ApplicationKt")
    classpath = sourceSets["main"].runtimeClasspath
    args("--once")
    outputs.upToDateWhen { false }
}
