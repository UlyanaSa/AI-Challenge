plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinxSerialization)
}

group = "com.osvin.aichallenge"
version = "1.0.0"

application {
    mainClass = "com.osvin.aichallenge.indexing.ui.ApplicationKt"
}

/**
 * Интерфейс дня 21: страница, которая запускает индексацию корпуса, показывает её ход и разбирает
 * эталонный вопрос — сколько результатов запрошено, на какой странице книги лежит ответ и каким
 * куском текста он там записан.
 *
 * Модуль отдельный, а не часть `:indexing`, по предмету: `:indexing` — вычислительный конвейер,
 * в котором нет ни сети, ни клиента (это записано и в его build-файле), а здесь ровно наоборот —
 * HTTP-маршруты и статика. Держать сервер внутри конвейера значило бы тащить в него Ktor ради
 * одной страницы, а страницу внутри конвейера — писать её без сервера. Конвенции взяты у `:server`:
 * тот же плагин Ktor, тот же бандл серверных зависимостей, тот же logback.
 *
 * Разбор PDF и скачивание по ссылке живут здесь, а не в `:indexing`: конвейер объявлен модулем без
 * сети (у него ровно две зависимости — сериализация и корутины), а PDFBox — это сеть и разбор
 * чужого формата. Конвейеру на вход идут уже готовые документы, и откуда они взялись, он не знает.
 *
 * Зависимость от `:indexing` — обычная (`implementation`): сервер вызывает конвейер как библиотеку
 * и не знает, как тот устроен внутри.
 *
 * `:indexing-ollama` подключён по той же логике, что и PDFBox: страница выбирает, чем считать
 * векторы (модель через локальный демон или хешированные признаки), а сам провайдер живёт вне
 * конвейера. Модуль для него отдельный, а не этот, потому что он не про страницу, а про
 * embeddings: тот же `EmbeddingProvider` берёт и любой другой вызывающий.
 */
dependencies {
    implementation(project(":indexing"))
    implementation(project(":indexing-ollama"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback)
    implementation(libs.pdfbox)
}
