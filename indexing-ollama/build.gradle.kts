plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "com.osvin.aichallenge"
version = "1.0.0"

/**
 * Embeddings из локальной модели через Ollama.
 *
 * Модуль отдельный, а не часть `:indexing`, по той же причине, по которой отдельно лежит `:indexing-ui`:
 * конвейер объявлен модулем без клиента и без сети (у него ровно две зависимости — сериализация и
 * корутины), а здесь ровно наоборот — HTTP-клиент и адрес демона. `EmbeddingProvider` — интерфейс
 * конвейера, и провайдер, который в него входит, не обязан жить рядом: PDF тоже разбирается не
 * в конвейере, а в `:indexing-ui`, потому что это сеть и чужой формат. Так `:indexing` остаётся
 * тем, чем объявлен, — вычислительным ядром, — а выбор модели становится выбором того, какой
 * провайдер подать конвейеру.
 *
 * HTTP — клиентом JDK (`java.net.http`), а не Ktor: модулю нужен один POST, и третья библиотека
 * ради него была бы лишней. Из зависимостей только разбор ответа (kotlinx-serialization) и
 * корутины — контракт провайдера приостанавливаемый.
 *
 * Сети наружу модуль не требует: Ollama — локальный демон на `127.0.0.1:11434`. Но и не
 * притворяется офлайн-модулем: без запущенного демона провайдер не поднимется, и это видно
 * вызывающему ([OllamaException]), а не спрятано за пустым вектором.
 */
dependencies {
    implementation(project(":indexing"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.core)
}
