package com.osvin.aichallenge.indexing.pipeline

import com.osvin.aichallenge.indexing.model.Document
import com.osvin.aichallenge.indexing.model.DocumentFile

/**
 * Токены текста — слова, на которые текст режет токенизатор.
 *
 * Токен здесь — последовательность Unicode-букв и цифр; знаки препинания, пробелы и разметка
 * Markdown токенами не считаются, дефис слово делит. Это то же деление, что делает
 * `HashingEmbeddingProvider` перед подсчётом веса, поэтому «сколько токенов в документе» — не
 * абстрактная мера длины, а объём работы, которая ложится на embedding: чанк из 1 500 символов
 * и чанк из 1 500 символов с другим составом слов стоят одинаково по символам и по-разному
 * по токенам.
 *
 * Служебные слова здесь не отбрасываются (в отличие от [TextOverlap.tokens]): там это слова,
 * различающие смысл, здесь — то, что реально уходит в модель. Поэтому счётчики и не совпадают,
 * и это не ошибка: «в», «и», «не» — тоже токены.
 */
object Tokens {

    private val word = Regex("[\\p{L}\\p{N}]+")

    /** Сколько токенов в тексте. */
    fun count(text: String): Int = word.findAll(text).count()

    /** Сколько токенов во всех файлах документа. */
    fun count(documents: List<Document>): Int = documents.sumOf { document -> count(document) }

    /** Сколько токенов во всех файлах одного документа. */
    fun count(document: Document): Int = document.files.sumOf { count(it) }

    /** Сколько токенов в файле. */
    fun count(file: DocumentFile): Int = count(file.content)
}
