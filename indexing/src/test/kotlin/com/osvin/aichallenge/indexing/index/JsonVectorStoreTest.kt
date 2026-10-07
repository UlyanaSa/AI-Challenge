package com.osvin.aichallenge.indexing.index

import com.osvin.aichallenge.indexing.model.ChunkMetadata
import com.osvin.aichallenge.indexing.model.ChunkingStrategyType
import com.osvin.aichallenge.indexing.model.DocumentChunk
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Файловый индекс: запись, поиск по близости, переживание перезапуска и поведение на пустых и
 * битых файлах. Векторы задаются вручную, чтобы ожидаемые близости были точными и тест не зависел
 * от embedding-провайдера.
 */
class JsonVectorStoreTest {

    private val query = listOf(1f, 0f)

    @Test
    fun `сохранение увеличивает size`() = runBlocking {
        val store = newStore()
        store.save(indexed("a", 1f, 0f))
        store.save(indexed("b", 0f, 1f))
        assertEquals(2, store.size)
    }

    @Test
    fun `поиск возвращает ближайший чанк первым`() = runBlocking {
        val store = newStore()
        store.save(indexed("far", 0f, 1f))
        store.save(indexed("near", 1f, 0f))
        store.save(indexed("mid", 0.6f, 0.8f))

        val result = store.search(query, limit = 3)
        assertEquals("near", result.first().chunk.id)
    }

    @Test
    fun `limit ограничивает число результатов`() = runBlocking {
        val store = newStore()
        store.save(indexed("a", 1f, 0f))
        store.save(indexed("b", 0f, 1f))
        store.save(indexed("c", 0.6f, 0.8f))
        assertEquals(2, store.search(query, limit = 2).size)
    }

    @Test
    fun `результаты отсортированы по убыванию близости`() = runBlocking {
        val store = newStore()
        store.save(indexed("far", 0f, 1f))
        store.save(indexed("near", 1f, 0f))
        store.save(indexed("mid", 0.6f, 0.8f))

        val similarities = store.search(query, limit = 3).map { it.similarity }
        assertEquals(similarities.sortedDescending(), similarities)
    }

    @Test
    fun `повторное сохранение того же id не удваивает индекс`() = runBlocking {
        val store = newStore()
        store.save(indexed("a", 1f, 0f))
        store.save(indexed("a", 0f, 1f))
        assertEquals(1, store.size)
    }

    @Test
    fun `get отдаёт сохранённый вектор`() = runBlocking {
        val store = newStore()
        store.save(indexed("a", 0.6f, 0.8f))

        assertEquals(listOf(0.6f, 0.8f), store.get("a")?.embedding)
    }

    @Test
    fun `get на неизвестный id даёт null`() = runBlocking {
        val store = newStore()
        store.save(indexed("a", 1f, 0f))

        assertEquals(null, store.get("b"))
    }

    @Test
    fun `вектор, прочитанный из переоткрытого индекса, тот же`() = runBlocking {
        val path = tempDir().resolve("index.json")
        JsonVectorStore.open(path).save(indexed("a", 0.6f, 0.8f))

        assertEquals(listOf(0.6f, 0.8f), JsonVectorStore.open(path).get("a")?.embedding)
    }

    @Test
    fun `open читает индекс с диска с теми же чанками и близостями`() = runBlocking {
        val path = tempDir().resolve("index.json")
        val first = JsonVectorStore.open(path)
        first.save(indexed("a", 1f, 0f))
        first.save(indexed("b", 0f, 1f))
        first.save(indexed("c", 0.6f, 0.8f))
        val before = first.search(query, limit = 3)

        val reopened = JsonVectorStore.open(path)
        val after = reopened.search(query, limit = 3)

        assertEquals(before.map { it.chunk.id }, after.map { it.chunk.id })
        before.zip(after).forEach { (x, y) ->
            assertEquals(x.similarity, y.similarity, 1e-9)
        }
    }

    @Test
    fun `create начинает индекс заново, стирая прежние чанки`() = runBlocking {
        val path = tempDir().resolve("index.json")
        JsonVectorStore.open(path).save(indexed("a", 1f, 0f))

        val fresh = JsonVectorStore.create(path)

        assertEquals(0, fresh.size)
        assertTrue(fresh.search(query, limit = 5).isEmpty())
        assertEquals(0, JsonVectorStore.open(path).size, "файл прежнего индекса не сохранился на диске")
    }

    @Test
    fun `open на несуществующем пути даёт пустой магазин`() = runBlocking {
        val store = JsonVectorStore.open(tempDir().resolve("missing.json"))
        assertEquals(0, store.size)
        assertTrue(store.search(query, limit = 5).isEmpty())
    }

    @Test
    fun `вектор запроса чужой длины не ищется`() = runBlocking<Unit> {
        val store = newStore()
        store.save(indexed("a", 1f, 0f))
        assertFailsWith<IllegalArgumentException> { store.search(listOf(1f, 0f, 0f), limit = 1) }
    }

    @Test
    fun `вектор чужой длины не сохраняется в индекс`() = runBlocking<Unit> {
        val store = newStore()
        store.save(indexed("a", 1f, 0f))
        assertFailsWith<IllegalArgumentException> { store.save(indexed("b", 1f, 0f, 0f)) }
    }

    @Test
    fun `битый JSON не читается молча`() {
        val path = tempDir().resolve("broken.json")
        Files.writeString(path, "{ это не индекс")
        assertFailsWith<IllegalStateException> { JsonVectorStore.open(path) }
    }

    private fun tempDir(): Path = createTempDirectory("vector-store")

    private fun newStore(): JsonVectorStore =
        JsonVectorStore.open(tempDir().resolve("index.json"))

    private fun indexed(id: String, vararg embedding: Float): IndexedChunk {
        val chunk = DocumentChunk(
            id = id,
            content = "content $id",
            metadata = ChunkMetadata(
                source = "doc.md",
                title = null,
                section = null,
                chunkIndex = 0,
                strategy = ChunkingStrategyType.FIXED_SIZE
            )
        )
        return IndexedChunk(chunk, embedding.toList())
    }
}
