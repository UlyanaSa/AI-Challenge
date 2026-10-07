package com.osvin.aichallenge.indexing.ollama

import com.osvin.aichallenge.indexing.embedding.EmbeddingProvider
import com.osvin.aichallenge.indexing.embedding.Feature
import com.osvin.aichallenge.indexing.embedding.HashingEmbeddingProvider

/**
 * Выбранный провайдер embeddings вместе с тем, что о нём нужно сказать человеку.
 *
 * [note] — не украшение: числа близости читаются по модели. У хеширования шкала сжата (одно общее
 * слово в длинном чанке мало что значит), у модели — своя, и одна и та же similarity 0.5 у них
 * означает разное. Поэтому страница показывает и имя модели, и то, почему выбран именно этот
 * провайдер.
 *
 * [features] заполнен только у провайдера с признаками: хешированный вектор можно объяснить
 * словами и триграммами, которые в него попали, а вектор нейросети — нет, и придумывать ему
 * подписи значило бы показывать неправду.
 */
class Embedding(
    val provider: EmbeddingProvider,
    /** `hashing` или `ollama` — как выбрано, а не как выглядит. */
    val kind: String,
    /** Имя модели у Ollama; у хеширования её нет. */
    val model: String?,
    val note: String,
    /** Признаки вектора по тексту — только там, где провайдер их знает. */
    val features: ((String) -> List<Feature>)?
) {

    /** Имя для показа на странице. */
    val name: String = if (model == null) "хеширование признаков" else "Ollama $model"
}

/**
 * Выбор провайдера embeddings: переменные окружения, а не догадки в коде.
 *
 * `INDEXING_EMBEDDING` = `auto` (по умолчанию) | `ollama` | `hashing`. Разница между `auto`
 * и `ollama` — в том, что делать, когда демона нет: `auto` берёт хеширование и говорит об этом
 * прямо, а `ollama` падает с адресом и подсказкой. Молча подменять модель хешированием — худшее
 * из решений: близости на странице стали бы другими, а причина — невидимой.
 *
 * `INDEXING_OLLAMA_URL` и `INDEXING_OLLAMA_MODEL` задают демон и модель; по умолчанию это
 * `http://127.0.0.1:11434` и `bge-m3` — многоязычная модель, потому что корпус дня русский.
 *
 * Проверка демона ([auto] и `available`) — до выбора, но не вместо него: важно, отвечает ли порт,
 * а не то, скачана ли модель. Отсутствующая модель остаётся ошибкой с подсказкой `ollama pull`.
 */
object EmbeddingProviders {

    /** Переменная выбора: `auto`, `ollama` или `hashing`. */
    const val KIND_ENV: String = "INDEXING_EMBEDDING"

    /** Переменная адреса демона Ollama. */
    const val URL_ENV: String = "INDEXING_OLLAMA_URL"

    /** Переменная имени модели Ollama. */
    const val MODEL_ENV: String = "INDEXING_OLLAMA_MODEL"

    const val AUTO: String = "auto"
    const val OLLAMA: String = "ollama"
    const val HASHING: String = "hashing"

    /** Все допустимые значения [KIND_ENV] — для проверки и для подсказки в ошибке. */
    val KINDS: List<String> = listOf(AUTO, OLLAMA, HASHING)

    /** Провайдер по настройкам окружения [env]. */
    suspend fun fromEnv(env: (String) -> String? = System::getenv): Embedding {
        val kind = env(KIND_ENV)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: AUTO
        require(kind in KINDS) {
            "$KIND_ENV=$kind не поддерживается, допустимо: ${KINDS.joinToString(", ")}"
        }
        val url = env(URL_ENV)?.trim()?.takeIf { it.isNotEmpty() } ?: OllamaEmbeddingProvider.DEFAULT_URL
        val model = env(MODEL_ENV)?.trim()?.takeIf { it.isNotEmpty() } ?: OllamaEmbeddingProvider.DEFAULT_MODEL

        return when (kind) {
            OLLAMA -> ollama(url, model, note = "выбрано переменной $KIND_ENV=$OLLAMA")
            HASHING -> hashing(note = "выбрано переменной $KIND_ENV=$HASHING")
            else -> if (OllamaEmbeddingProvider.available(url)) {
                ollama(url, model, note = "демон Ollama отвечает на $url")
            } else {
                hashing(
                    note = "Ollama не отвечает на $url, поэтому считаются хешированные признаки; " +
                        "чтобы включить модель: ollama serve и $KIND_ENV=$OLLAMA ($MODEL_ENV=$model)"
                )
            }
        }
    }

    private suspend fun ollama(url: String, model: String, note: String): Embedding = Embedding(
        provider = OllamaEmbeddingProvider.connect(url, model),
        kind = OLLAMA,
        model = model,
        note = note,
        features = null
    )

    private fun hashing(note: String): Embedding {
        val provider = HashingEmbeddingProvider()
        return Embedding(
            provider = provider,
            kind = HASHING,
            model = null,
            note = note,
            features = provider::features
        )
    }
}
