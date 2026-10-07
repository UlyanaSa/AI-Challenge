plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
}

group = "com.osvin.aichallenge"
version = "1.0.0"

/**
 * RAG-агент: вопрос человека превращается в запрос к модели двумя путями — с найденными
 * фрагментами базы и без них.
 *
 * Модуль отдельный, потому что это первый код дня, который соединяет две существующие половины
 * проекта: поиск по базе (`:indexing` + `:indexing-ollama`, день 21) и запрос к модели
 * (`:agent`). Ни та, ни другая половина о такой связке знать не должна: конвейер индексации —
 * вычислительный модуль без клиента и сети, а `:agent` — транспорт и агент, которым всё равно,
 * откуда взялся текст запроса. Связка живёт здесь и только здесь.
 *
 * Зависимости — ровно те четыре, без которых связку не собрать:
 * - `:indexing` — индекс и поиск (`SemanticSearch`, `JsonVectorStore`);
 * - `:indexing-ollama` — выбор провайдера векторов окружением, тот же, что на странице дня 21
 *   (вопрос и чанки обязаны считаться одним провайдером, иначе близость бессмысленна);
 * - `:indexing-ui` — разбор PDF и корпус дня: база дня лежит во встроенном PDF, а его разбор
 *   живёт в модуле страницы, и вторая копия разбора разошлась бы с первой;
 * - `:agent` — контракт `LlmClient` и DTO DeepSeek: агент дня не изобретает второй транспорт,
 *   а принимает клиент в конструкторе (в тестах — подставной, в прогоне — `DeepSeekClient`).
 *
 * Ktor-клиент нужен как зависимость модуля, а не приходит транзитивно: `:agent` отдаёт наружу
 * только `ktor-client-core`, а HTTP-запросу нужен ещё движок и JSON-режим клиента.
 */
dependencies {
    implementation(project(":indexing"))
    implementation(project(":indexing-ollama"))
    implementation(project(":indexing-ui"))
    implementation(project(":agent"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.core)
}

/**
 * Живой режим: ключ и флаг демонстрации для прогонов и тестов модуля.
 *
 * Флаг `-Pdemo.live=1` и ключ из `server/.env` (или `-Pdemo.api.key=…`) пробрасываются в JVM —
 * тем же способом, что у `:agent` и `:server`: `./gradlew :rag:ragDemo -Pdemo.live=1` идёт
 * на живой API, без флага прогон печатает, чего не хватает, и не тратит запросы.
 */
tasks.withType<JavaExec>().configureEach {
    (project.findProperty("demo.live") as String?)?.let { systemProperty("demo.live", it) }
    demoApiKey()?.let { systemProperty("demo.api.key", it) }
}

/** Ключ из `server/.env`: прогон идёт из того же каталога, что у сервера, и ключ у них общий. */
fun demoApiKey(): String? = rootProject.file("server/.env").takeIf { it.isFile }
    ?.readLines()
    ?.map { it.trim() }
    ?.firstOrNull { it.startsWith("DEEPSEEK_API_KEY=") }
    ?.substringAfter('=')
    ?.trim()
    ?.trim('"')
    ?.takeIf { it.isNotEmpty() }

/**
 * Прогон дня: `./gradlew :rag:ragDemo` (живой режим — `-Pdemo.live=1`).
 *
 * Прогон отвечает на десять контрольных вопросов дважды — с найденными фрагментами базы и без
 * них, — сверяет ответы с ожиданиями набора, печатает сравнение в консоль и кладёт отчёт
 * в каталог индексов.
 *
 * Печать идёт в стандартный вывод: сравнение — это работа задачи, а не побочный эффект теста,
 * поэтому запуск отдельной задачей, как у `:indexing:indexReport`. Каталог индексов и параметры
 * поиска задаются аргументами: `--args="--dir=/tmp/rag --topK=5"`.
 */
val ragDemo by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Сравнение ответов модели с RAG и без RAG на контрольных вопросах базы дня"
    mainClass.set("com.osvin.aichallenge.rag.RagCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    // Каталогом запуска берётся корень проекта, а не модуль: рабочий каталог прогона по умолчанию
    // (`build/rag`) и ключ в `server/.env` — оба пути от корня, и при запуске из каталога модуля
    // они указывали бы в `rag/build/rag` и `rag/server/.env`, то есть никуда.
    workingDir = rootProject.projectDir
    // Отчёт пишется заново при каждом запуске: он зависит от базы, модели и ключа, а не от
    // входов задачи, и его цель — показать числа текущего прогона.
    outputs.upToDateWhen { false }
}
