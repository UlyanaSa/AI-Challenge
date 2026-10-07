package com.osvin.aichallenge.indexing.pipeline

import com.osvin.aichallenge.indexing.model.Document

/**
 * Достаёт текст раздела из набора документов — эталон, с которым сверяется поиск.
 *
 * Эталон берётся из исходных документов, а не из индекса: если бы эталон собирался из чанков
 * одной из стратегий, оценка качества измеряла бы согласие стратегии с самой собой.
 *
 * Раздел обрывается на следующем заголовке любого уровня: заголовок и в структурной стратегии
 * открывает новый чанк, и эталон обязан резать текст по той же границе, иначе «попаданием»
 * считался бы чанк, набравший слова соседних разделов.
 *
 * В книге это важно буквально: у разделов принципов есть подразделы, и эталоном служит собственный
 * текст раздела до первого подраздела — ровно тот фрагмент, который структурная стратегия отдаёт
 * первым чанком раздела. Взять вместе с подразделами значило бы сделать эталон в разы длиннее
 * любого чанка, и правильный чанк перестал бы проходить порог релевантности по построению.
 */
object Sections {

    private val heading = Regex("^#{1,6}\\s+(.+?)\\s*$")

    /** Текст раздела [section] из файла [source]; `null`, если файла или заголовка нет. */
    fun text(documents: List<Document>, source: String, section: String): String? {
        val file = documents.asSequence().flatMap { it.files }.firstOrNull { it.name == source } ?: return null
        val lines = file.content.lines()

        val start = lines.indexOfFirst { line ->
            val match = heading.matchEntire(line.trim()) ?: return@indexOfFirst false
            match.groupValues[1] == section
        }
        if (start < 0) return null

        val body = lines.drop(start + 1).takeWhile { line -> heading.matchEntire(line.trim()) == null }

        return body.joinToString("\n").trim()
    }
}
