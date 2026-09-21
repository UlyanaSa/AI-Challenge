package com.osvin.aichallenge.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Тип памяти агента: где живут данные и кто ими владеет.
 *
 * Типов ровно три, и названы они так же, как в задании дня, — в коде, на проводе
 * и в интерфейсе:
 *
 * - [SHORT_TERM] — краткосрочная (текущий диалог): сообщения этого диалога. Лежат
 *   на устройстве ([ChatMessage] в хранилище чатов), сервер их не держит: в запрос
 *   уходит то, что выбрала стратегия.
 * - [WORKING] — рабочая (данные текущей задачи): цель, ограничения, числа, сроки,
 *   договорённости. Живут по профилю, как долговременная, и переживают сообщения,
 *   отброшенные окном.
 * - [LONG_TERM] — долговременная (профиль, решения, знания): то, что не зависит от
 *   текущего диалога. Живут по профилю и переживают и чат, и перезапуск сервера.
 *
 * Рабочая и долговременная память отличаются не областью — область у них одна, профиль
 * ([DEFAULT_PROFILE]), и видно их из любого чата, — а сроком жизни: рабочая лежит в памяти
 * процесса сервера, и перезапуск её обнуляет, а долговременная лежит в файле и перезапуск
 * переживает.
 *
 * Подписи и пояснения живут только здесь: интерфейс берёт их из каталога снимка
 * ([MemoryLayers.catalogue]), поэтому разойтись с сервером не могут.
 *
 * @param wire Значение типа в отчёте агента, в теле запроса и в интерфейсе.
 * @param title Название типа для интерфейса и логов.
 * @param hint Пояснение к названию — то, что стоит в скобках: «рабочая (данные текущей задачи)».
 */
enum class MemoryLayer(val wire: String, val title: String, val hint: String) {
    SHORT_TERM("short_term", "краткосрочная", "текущий диалог"),
    WORKING("working", "рабочая", "данные текущей задачи — общие для всех чатов"),
    LONG_TERM("long_term", "долговременная", "профиль, решения, знания");

    /**
     * Можно ли записать в этот тип вручную. Краткосрочная память — это сообщения
     * диалога: их пишет сам пользователь, когда пишет в чат, отдельного хранилища
     * за ней нет, и запись туда была бы записью в никуда.
     */
    val writable: Boolean get() = this != SHORT_TERM

    /** Подпись типа с пояснением: «краткосрочная (текущий диалог)». */
    val caption: String get() = "$title ($hint)"

    companion object {

        /** Тип памяти по значению провода; null — тип неизвестен, и запись не принимается. */
        fun ofWire(wire: String): MemoryLayer? = entries.firstOrNull { it.wire == wire.trim().lowercase() }
    }
}

/**
 * Запись памяти: в каком типе она лежит и что в ней сказано — одна фраза.
 *
 * Ключа у записи нет: повтор распознаётся по самому тексту, поэтому та же фраза
 * обновляет запись, а пересказанная становится второй. Предел типа тогда ограничивает
 * не дубли, а число разных фраз, и это осознанная цена за запись без разметки:
 * раньше ключ приходилось называть и модели, и человеку.
 *
 * @param layer Тип памяти, в котором лежит запись: значение [MemoryLayer.wire].
 * @param value Текст записи.
 */
@Serializable
data class MemoryRecord(val layer: String, val value: String)

/**
 * Память агента по типам: что ушло в запрос и чего это стоило.
 * Уезжает в отчёте ответа, по ней же собирается шторка памяти в интерфейсе.
 *
 * @param working Записи рабочей памяти, которые ушли в запрос.
 * @param longTerm Записи долговременной памяти, которые ушли в запрос.
 * @param workingTokens Токены блока рабочей памяти.
 * @param longTermTokens Токены блока долговременной памяти.
 * @param shortTermMessages Сколько сообщений диалога ушло в запрос.
 * @param shortTermDropped Сколько сообщений не ушло: отброшено окном или осталось вне ветки.
 * @param rejected Сколько записей отклонено: неизвестный тип или тип, которого стратегия не ведёт.
 * @param evicted Сколько записей вытеснено пределами типа ([MemoryRules]).
 * @param updateTokens Токены служебного вызова обновления памяти (запрос и ответ).
 * @param updateCostUsd Цена этого вызова в USD; null — тариф не опубликован.
 */
@Serializable
data class MemoryReport(
    val working: List<MemoryRecord> = emptyList(),
    @SerialName("long_term") val longTerm: List<MemoryRecord> = emptyList(),
    @SerialName("working_tokens") val workingTokens: Int = 0,
    @SerialName("long_term_tokens") val longTermTokens: Int = 0,
    @SerialName("short_term_messages") val shortTermMessages: Int = 0,
    @SerialName("short_term_dropped") val shortTermDropped: Int = 0,
    val rejected: Int = 0,
    val evicted: Int = 0,
    @SerialName("update_tokens") val updateTokens: Int = 0,
    @SerialName("update_cost_usd") val updateCostUsd: Double? = null
)

/** Записи типа после слияния с новыми и число вытесненных. */
data class MemoryMerge(val records: List<MemoryRecord>, val evicted: Int)

/**
 * Пределы типов и слияние записей.
 *
 * Рабочая память ограничена сильнее: данных текущей задачи немного — цель, числа, сроки,
 * — и живут они, пока задача жива. Долговременная держит больше, но и она не бесконечна —
 * иначе память станет дороже истории, которую она заменяет.
 */
object MemoryRules {

    /** Сколько записей держит рабочая память. */
    const val MAX_WORKING = 20

    /** Сколько записей держит долговременная память. */
    const val MAX_LONG_TERM = 30

    /** Предел длины записи: память — выжимка, а не пересказ. */
    const val MAX_CHARS = 200

    /** Предел типа. */
    fun limit(layer: MemoryLayer): Int = when (layer) {
        MemoryLayer.WORKING -> MAX_WORKING
        MemoryLayer.LONG_TERM -> MAX_LONG_TERM
        MemoryLayer.SHORT_TERM -> 0
    }

    /**
     * Сливает прежние записи с новыми: та же фраза заменяет прежнюю запись и становится
     * самой свежей, записи сверх предела вытесняются самые старые.
     */
    fun merge(existing: List<MemoryRecord>, incoming: List<MemoryRecord>, layer: MemoryLayer): MemoryMerge {
        val merged = LinkedHashMap<String, MemoryRecord>()
        (existing + incoming).forEach { record ->
            // Повтор фразы не просто заменяет запись, а делает её самой свежей: иначе
            // подтверждённое ещё раз вытеснялось бы вместе со старыми записями.
            merged.remove(textOf(record))
            merged[textOf(record)] = record
        }
        val kept = merged.values.toList().takeLast(limit(layer))
        return MemoryMerge(kept, merged.size - kept.size)
    }

    /** Записи без той, чей текст совпал: по тексту запись опознаётся и при удалении. */
    fun forget(records: List<MemoryRecord>, value: String): List<MemoryRecord> {
        val text = value.trim().lowercase()
        return records.filterNot { textOf(it) == text }
    }

    /** Приводит запись к границам памяти: известный тип, непустой текст и обрезка длины. */
    fun normalize(record: MemoryRecord): MemoryRecord? {
        val layer = MemoryLayer.ofWire(record.layer) ?: return null
        val value = record.value.trim()
        if (value.isEmpty()) return null
        return MemoryRecord(layer.wire, value.take(MAX_CHARS))
    }

    /** По чему видно повтор: текст без регистра и внешних пробелов. */
    private fun textOf(record: MemoryRecord): String = record.value.trim().lowercase()
}
