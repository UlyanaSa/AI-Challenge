package com.osvin.aichallenge.indexing.index

import com.osvin.aichallenge.indexing.search.CosineSimilarity
import com.osvin.aichallenge.indexing.search.SearchResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Индекс на диске: чанки с embeddings лежат одним JSON-файлом и перечитываются после перезапуска.
 *
 * Файл, а не база: индекс — результат прогона, его нужно уметь показать и сравнить, поэтому форма
 * хранения человекочитаема (`prettyPrint`), и один прогон не требует поднятого сервиса.
 *
 * Запись сквозная (write-through): [save] сразу перезаписывает файл целиком. Иначе после падения
 * процесса в памяти и на диске оказались бы разные индексы, а «переживает перезапуск» было бы
 * неправдой. Индекс — множество по `chunk.id`, поэтому повторная запись того же чанка заменяет
 * прежний и не удваивает размер.
 *
 * Конструктор закрыт: индекс нельзя создать «пустым в обход файла» — единственный вход [open],
 * который честно читает то, что лежит на диске.
 */
class JsonVectorStore private constructor(
    private val path: Path
) : VectorStore {

    private val chunks = LinkedHashMap<String, IndexedChunk>()

    /** Сколько чанков в индексе — для отчёта о прогоне. */
    val size: Int get() = chunks.size

    /**
     * Сохраняет чанк и сразу пишет индекс на диск.
     *
     * Проверка длины embedding защищает инвариант «одно хранилище — одна модель»: смешать в одном
     * индексе векторы из разных пространств нельзя, иначе близости станут несравнимыми. Проверяем
     * по уже сохранённым векторам, потому что до первой записи эталон брать неоткуда.
     */
    override suspend fun save(chunk: IndexedChunk) {
        val reference = chunks.values.firstOrNull()
        if (reference != null && reference.embedding.size != chunk.embedding.size) {
            require(false) {
                "Длина embedding ${chunk.embedding.size} не совпадает с длиной векторов индекса " +
                    "${reference.embedding.size}: в одном индексе должна быть одна модель"
            }
        }
        chunks[chunk.chunk.id] = chunk
        persist()
    }

    /**
     * Отдаёт сохранённый чанк вместе с его вектором. Читается из памяти: файл — форма хранения,
     * а не рабочее состояние, и перечитывать его на каждый показ вектора значило бы платить
     * разбором всего индекса за одно число.
     */
    override suspend fun get(id: String): IndexedChunk? = chunks[id]

    /**
     * Возвращает до [limit] ближайших чанков по убыванию косинусной близости.
     *
     * Пустой индекс даёт пустой список — это не ошибка, а честный ответ «искать не в чем». А вот
     * вектор запроса чужой длины — ошибка: он посчитан другой моделью, и близость была бы мусором.
     */
    override suspend fun search(embedding: List<Float>, limit: Int): List<SearchResult> {
        require(limit > 0) { "limit должен быть положительным, получено $limit" }
        if (chunks.isEmpty()) return emptyList()

        val expected = chunks.values.first().embedding.size
        require(embedding.size == expected) {
            "Длина вектора запроса ${embedding.size} не совпадает с длиной векторов индекса " +
                "$expected: запрос посчитан другой моделью"
        }

        return chunks.values
            .map { SearchResult(it.chunk, CosineSimilarity.cosine(embedding, it.embedding)) }
            .sortedByDescending { it.similarity }
            .take(limit)
    }

    /** Перезаписывает файл целиком, создавая при необходимости родительские каталоги. */
    private fun persist() {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, json.encodeToString(chunks.values.toList()))
    }

    companion object {

        private val json = Json { prettyPrint = true }

        /**
         * Поднимает индекс из файла. Отсутствие файла или пустой файл — пустой индекс: до первого
         * прогона файла ещё нет, и это нормальный старт, а не ошибка. Битый JSON — ошибка: молча
         * начать с пустого индекса значило бы потерять прежний результат без следа.
         */
        fun open(path: Path): JsonVectorStore {
            val store = JsonVectorStore(path)
            if (!Files.exists(path) || Files.size(path) == 0L) return store

            val text = Files.readString(path)
            if (text.isBlank()) return store

            val loaded = try {
                json.decodeFromString<List<IndexedChunk>>(text)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Не удалось прочитать индекс из файла $path: ${e.message}", e
                )
            }
            for (chunk in loaded) store.chunks[chunk.chunk.id] = chunk
            return store
        }

        /**
         * Создаёт индекс заново, стирая прежний файл.
         *
         * Прогону эксперимента нужен именно этот вход: [open] поднимает индекс с диска и дальше
         * сливает записи по `chunk.id`, поэтому в файле остались бы чанки прошлого корпуса —
         * они не попали в отчёт, но участвовали бы в поиске, и числа описывали бы не тот набор,
         * который проиндексирован. «Индекс этого прогона» и «индекс, который переживает
         * перезапуск» — разные задачи, и вход у них разный.
         */
        fun create(path: Path): JsonVectorStore {
            Files.deleteIfExists(path)
            return JsonVectorStore(path)
        }
    }
}
