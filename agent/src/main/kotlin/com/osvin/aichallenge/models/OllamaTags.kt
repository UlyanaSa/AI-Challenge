package com.osvin.aichallenge.models

import kotlinx.serialization.Serializable

/**
 * Ответ демона Ollama на `GET /api/tags`: список загруженных моделей.
 *
 * Нужен проверке здоровья локального режима: демон отвечает и тогда, когда модели нет,
 * поэтому «Ollama запущена» и «модель загружена» — два разных состояния, и различить
 * их можно только по списку. Поля сверх имени в ответе демона нас не интересуют, но
 * неизвестные ключи разбор не ломают: клиент настроен на их игнорирование.
 *
 * @param name Полное имя модели с тегом (`qwen3:1.7b`).
 */
@Serializable
data class OllamaTags(val models: List<OllamaTag> = emptyList()) {

    /** Одна модель в списке демона. */
    @Serializable
    data class OllamaTag(val name: String = "", val model: String = "")
}
