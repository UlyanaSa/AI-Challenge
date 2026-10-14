package com.osvin.aichallenge.tuning

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Что демон занимает под модель: снимок одной загрузки.
 *
 * Память — вторая половина цены ответа рядом со временем: профиль, который отвечает быстрее,
 * но держит модель в памяти вдвое дольше, стоит дороже машине, а не дешевле. Единицы — байты
 * демона как есть, без пересчёта: округление до мегабайтов сделало бы разницу между сборками
 * (она и есть предмет сравнения) неразличимой, а форматирование — дело отчёта, не снимка.
 *
 * @param name Имя загруженной модели у демона, с тегом (`qwen3:1.7b`).
 * @param sizeBytes Размер модели в памяти: у демона это поле `size` из `/api/ps` — веса вместе
 *        с кэшем контекста. У снимка с диска (`/api/tags`) это поле `size` — файл модели,
 *        и он меньше: кэш контекста занимается только при загрузке в память.
 * @param sizeVramBytes Сколько из этого лежит в видеопамяти (поле `size_vram`); у снимка
 *        с диска — `0`, потому что на диске видеопамяти нет.
 * @param quantization Уровень квантования из `details.quantization_level` (`Q4_K_M` и т. п.) —
 *        то, чем сборки модели отличаются друг от друга, когда запуск задал другую модель.
 * @param parameterSize Размер модели в параметрах из `details.parameter_size` (`2.0B`).
 */
data class ResourceSnapshot(
    val name: String,
    val sizeBytes: Long,
    val sizeVramBytes: Long,
    val quantization: String?,
    val parameterSize: String?
)

/**
 * Потребление ресурсов демоном Ollama: снимок загруженной модели и размер модели на диске.
 *
 * Два разных вопроса и два разных ответа демона, поэтому и два вызова: `/api/ps` отвечает про
 * модель, которая сейчас в памяти, — из него берётся то, что демон занял ради ответов; `/api/tags`
 * отвечает про модели на диске вообще, и в нём есть размер и у той, которую в памяти ещё нет.
 * Снимок берётся своим [Json], а не разбором клиента: ресурсы — не часть транспорта, и снимок
 * не должен зависеть от того, настроен ли у клиента разбор JSON (`:agent` просит его для чата).
 *
 * Отказ демона здесь — не отказ прогона: без `ollama serve` ответов всё равно не будет, и об этом
 * скажет прогон, а снимок ресурсов остаётся пустым — колонка «в памяти» в отчёте тогда пустая,
 * и это честнее выдуманного числа.
 */
object Resources {

    /** Путь снимка загруженных моделей: демон отвечает про то, что сейчас в памяти. */
    private const val PS_PATH = "/api/ps"

    /** Путь списка моделей: у демона есть и то, что лежит на диске и ждёт загрузки. */
    private const val TAGS_PATH = "/api/tags"

    /**
     * Модель в памяти: `null` — модель не загружена или демон не ответил.
     *
     * Берётся после первого ответа, потому что до него модели в памяти может не быть вовсе:
     * демон загружает её по запросу, и первый раунд отвечает ещё с пустым кэшем контекста.
     */
    suspend fun inMemory(http: HttpClient, baseUrl: String, model: String): ResourceSnapshot? {
        val body = fetch(http, "$baseUrl$PS_PATH") ?: return null
        val loaded = parsePs(body) ?: return null
        val found = loaded.firstOrNull { it.name.isModel(model) } ?: return null
        return ResourceSnapshot(
            name = found.name,
            sizeBytes = found.size,
            sizeVramBytes = found.sizeVram,
            quantization = found.details?.quantizationLevel,
            parameterSize = found.details?.parameterSize
        )
    }

    /** Модель на диске: `null` — модели нет на диске или демон не ответил. */
    suspend fun onDisk(http: HttpClient, baseUrl: String, model: String): ResourceSnapshot? {
        val body = fetch(http, "$baseUrl$TAGS_PATH") ?: return null
        val tags = parseTags(body) ?: return null
        val found = tags.firstOrNull { it.name.isModel(model) } ?: return null
        return ResourceSnapshot(
            name = found.name,
            sizeBytes = found.size,
            // Видеопамяти у файла на диске нет: ноль здесь значит «неизвестно из этого снимка»,
            // а не «ничего не занято», и в отчёт он так и попадает — строкой без VRAM.
            sizeVramBytes = 0,
            quantization = found.details?.quantizationLevel,
            parameterSize = found.details?.parameterSize
        )
    }

    /**
     * Снимок модели: из памяти, если она загружена, иначе с диска.
     *
     * Запасной путь нужен снимку конца прогона: демон держит модель в памяти ограниченное время
     * и к концу долгого сравнения может её выгрузить. Тогда размер берётся с диска — колонка
     * отчёта остаётся числом (сборка модели видна и без загрузки), а не прочерком, и по ней
     * всё равно видно, какую модель сравнивали.
     */
    suspend fun snapshot(http: HttpClient, baseUrl: String, model: String): ResourceSnapshot? =
        inMemory(http, baseUrl, model) ?: onDisk(http, baseUrl, model)

    /** Тело ответа демона; `null` — демон не ответил или ответил отказом. */
    private suspend fun fetch(http: HttpClient, url: String): String? = runCatching {
        val response = http.get(url)
        if (response.status.isSuccess()) response.bodyAsText() else null
    }.getOrNull()

    /** Модели из ответа `/api/ps`; `null` — ответ пришёл не тем, что ожидалось. */
    private fun parsePs(body: String): List<PsModel>? =
        runCatching { json.decodeFromString<PsResponse>(body).models }.getOrNull()

    /** Модели из ответа `/api/tags`; `null` — ответ пришёл не тем, что ожидалось. */
    private fun parseTags(body: String): List<TagModel>? =
        runCatching { json.decodeFromString<TagsResponse>(body).models }.getOrNull()

    /**
     * Та ли это модель: демон называет модель с тегом (`qwen3:1.7b`), запуск может назвать её
     * короче (`qwen3`) — тогда совпадением считается любой тег этой модели.
     */
    private fun String.isModel(model: String): Boolean = this == model || startsWith("$model:")

    /**
     * Разбор ответов демона: лишние поля не ломают снимок (демон добавляет их от версии к версии),
     * а отсутствующие значения заменяются умолчаниями — снимок без одной строки полезнее отказа.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
}

/** Ответ `GET /api/ps`: загруженные модели. Поля — как их называет демон. */
@Serializable
internal data class PsResponse(val models: List<PsModel> = emptyList())

/**
 * Модель в памяти.
 *
 * @param size Размер модели в памяти вместе с кэшем контекста: у qwen3:1.7b на живом демоне
 *        1 924 304 731 Б против 1 359 293 444 Б у файла на диске.
 * @param sizeVram Сколько из этого лежит в видеопамяти; у машины без выделенной памяти демон
 *        отдаёт то же число, что и `size`.
 */
@Serializable
internal data class PsModel(
    val name: String = "",
    val size: Long = 0,
    @SerialName("size_vram") val sizeVram: Long = 0,
    val details: ModelDetails? = null
)

/** Ответ `GET /api/tags`: модели, которые есть у демона на диске. */
@Serializable
internal data class TagsResponse(val models: List<TagModel> = emptyList())

/** Модель на диске: имя, размер файла и те же подробности сборки, что у загруженной. */
@Serializable
internal data class TagModel(
    val name: String = "",
    val size: Long = 0,
    val details: ModelDetails? = null
)

/** Подробности сборки модели: демон называет их в `details` у обоих ответов. */
@Serializable
internal data class ModelDetails(
    @SerialName("quantization_level") val quantizationLevel: String? = null,
    @SerialName("parameter_size") val parameterSize: String? = null
)
