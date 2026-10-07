plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinxSerialization)
}

group = "com.osvin.aichallenge"
version = "1.0.0"

application {
    mainClass = "com.osvin.aichallenge.rag.ui.ApplicationKt"
}

/**
 * Страница дня 22: интерфейс к агентам двух режимов.
 *
 * Она показывает то, что в прогоне в консоли проходит перед глазами и исчезает: путь запроса
 * по звеньям. Вопрос, вектор вопроса, Top-K с идентификаторами и близостями, собранный контекст,
 * запрос к модели, ответ и сверка с ожиданием — по обеим ветвям рядом, чтобы разницу между
 * «по памяти» и «по найденному» было видно, а не следовало из двух чисел.
 *
 * Модуль отдельный, а не часть `:rag`, по тому же разделению, что у дней 21 и 20: `:rag` — прогон
 * (модель, поиск, набор вопросов, отчёты), в нём нет ни сервера, ни статики, а здесь ровно
 * наоборот — HTTP-маршруты, состояние страницы и файлы ресурсов. Зависимость от `:rag` обычная
 * (`implementation`): сервер вызывает прогон как библиотеку и не знает, как тот устроен внутри.
 *
 * `:agent` подключён потому, что страница называет контракт модели (`LlmClient`), который
 * `:rag` берёт у себя: без него конструктор агента на странице не собрать. `:indexing`
 * и `:indexing-ollama` — за счётчиком токенов и провайдером векторов; и то и другое описано
 * в их собственных build-файлах.
 */
dependencies {
    implementation(project(":rag"))
    implementation(project(":agent"))
    implementation(project(":indexing"))
    implementation(project(":indexing-ollama"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.core)
}

/**
 * Рабочий каталог страницы — корень проекта, а не каталог модуля.
 *
 * Так `build/rag-ui` оказывается рядом с `build/rag` (там же отчёты прогона в консоли), а ключ
 * ищется в `server/.env` — файле, который лежит в корне и не попадает в репозиторий. Запуск
 * из каталога модуля нашёл бы вместо него пустоту и сказал бы, что ключа нет, — то есть выглядел бы
 * как отсутствие ключа, а не как неверный каталог запуска.
 */
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
