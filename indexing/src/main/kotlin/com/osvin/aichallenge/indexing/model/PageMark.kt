package com.osvin.aichallenge.indexing.model

import kotlinx.serialization.Serializable

/**
 * Начало страницы в тексте файла: номер страницы и смещение её первого блока в тексте файла.
 *
 * Страницы приходят из вёрстки книги, а не из модели предметной области: конвертер
 * `tools/pdf-to-corpus.swift` печатает маркер `<!-- page:N -->` на границе блоков, а код, читающий
 * корпус ([PageMarkers.stripAndIndex]), снимает маркеры и запоминает смещения. Смещение считается
 * в чистом тексте (без маркеров), поэтому оно указывает ровно на тот символ, с которого начинается
 * блок, лежащий на странице [page], — и по нему чанк узнаёт свои страницы, не разбирая текст заново.
 *
 * Маркер ставится только там, где начинается новый блок, и никогда внутри абзаца: иначе абзац,
 * перенесённый через страницу, пришлось бы разрезать, и разметка страниц меняла бы сам корпус.
 */
@Serializable
data class PageMark(val page: Int, val offset: Int)

/** Текст файла без маркеров страниц и карта страниц, посчитанная в смещениях этого текста. */
data class PagedText(val content: String, val pages: List<PageMark>)

/**
 * Маркеры страниц в файлах корпуса: разбор (`<!-- page:115 -->`) и подпись для человека.
 *
 * Разбор идёт по строкам, потому что маркер занимает строку целиком: он печатается как отдельный
 * блок, а не вклеивается в текст. Вместе с маркером уходит и одна из двух пустых строк вокруг
 * него — иначе на месте маркера остался бы двойной разрыв абзаца, а текст корпуса перестал бы
 * совпадать с текстом до разметки страниц.
 *
 * Подпись страниц живёт здесь же, рядом с их разбором: и отчёт, и интерфейс показывают одно и то
 * же — «стр. 115–116», — и второе место, где страницы форматируются, разошлось бы с первым.
 */
object PageMarkers {

    private val MARKER = Regex("""^<!--\s*page:(\d+)\s*-->$""")

    /** Номер страницы, если строка — маркер страницы; `null` для обычной строки текста. */
    fun pageOf(line: String): Int? =
        MARKER.matchEntire(line.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Снимает маркеры со текста и считает, где начинается каждая страница. */
    fun stripAndIndex(raw: String): PagedText {
        val kept = ArrayList<String>()
        val marks = ArrayList<PageMark>()
        var offset = 0
        var pending: Int? = null

        for (line in raw.split("\n")) {
            val page = pageOf(line)
            if (page != null) {
                pending = page
                continue
            }
            // Пустая строка перед маркером уже отделяет блоки: вторая пустая строка — след маркера.
            if (line.isBlank() && kept.lastOrNull()?.isBlank() == true) continue
            if (line.isNotBlank() && pending != null) {
                marks += PageMark(pending, offset)
                pending = null
            }
            kept += line
            offset += line.length + 1
        }

        return PagedText(kept.joinToString("\n"), marks)
    }

    /**
     * Подпись страниц для человека: `стр. 115`, `стр. 115–116`, `стр. 115, 118`; `null` — страницы
     * неизвестны. Непрерывный ряд печатается диапазоном: у чанка, целиком лежащего на двух
     * страницах, «115–116» читается лучше перечисления.
     */
    fun format(pages: List<Int>): String? {
        val sorted = pages.distinct().sorted()
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return "стр. ${sorted.first()}"
        if (sorted.last() - sorted.first() == sorted.size - 1) {
            return "стр. ${sorted.first()}–${sorted.last()}"
        }
        return "стр. " + sorted.joinToString(", ")
    }
}
