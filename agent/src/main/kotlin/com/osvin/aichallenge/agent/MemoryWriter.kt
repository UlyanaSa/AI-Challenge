package com.osvin.aichallenge.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Тип памяти в каталоге: как он называется, что в него попадает и можно ли писать
 * в него вручную.
 *
 * Каталог собирается из [MemoryLayer] и уезжает клиенту вместе со снимком: выбор типа
 * в интерфейсе рисуется по нему, поэтому своей копии таблицы типов у клиента нет —
 * подписи и пояснения не могут разойтись с сервером.
 *
 * @param layer Тип памяти: значение [MemoryLayer.wire], оно же уходит обратно при записи.
 * @param title Название типа для интерфейса.
 * @param hint Пояснение к названию из задания дня.
 * @param writable Можно ли записать в этот тип вручную.
 */
@Serializable
data class MemoryType(
    val layer: String,
    val title: String,
    val hint: String,
    val writable: Boolean
)

/**
 * Снимок памяти: что лежит в типах и какие типы бывают.
 *
 * Слои здесь полные, а не те, что ушли в последний запрос: по снимку работает шторка
 * памяти и явная запись, поэтому он не должен зависеть от выбранной стратегии.
 * Краткосрочного слоя в снимке нет: это сообщения диалога, они живут на устройстве,
 * и сервер их не хранит.
 */
@Serializable
data class MemoryLayers(
    val working: List<MemoryRecord> = emptyList(),
    @SerialName("long_term") val longTerm: List<MemoryRecord> = emptyList(),
    val types: List<MemoryType> = emptyList()
) {
    companion object {

        /** Каталог типов памяти: порядок — как объявлены типы, подписи — из [MemoryLayer]. */
        fun catalogue(): List<MemoryType> = MemoryLayer.entries.map { layer ->
            MemoryType(layer = layer.wire, title = layer.title, hint = layer.hint, writable = layer.writable)
        }
    }
}

/** Чем закончилась явная запись: записью в типе или отказом с причиной. */
sealed interface MemoryWrite {

    /** Запись прошла проверки и легла в свой тип. */
    data class Written(
        val record: MemoryRecord,
        val layer: MemoryLayer,
        /** Сколько записей вытеснено пределами типа. */
        val evicted: Int
    ) : MemoryWrite

    /** Тип неизвестен, недоступен для записи или текст пуст: причина — в [reason]. */
    data class Rejected(val reason: String) : MemoryWrite
}

/** Чем закончилось забывание: снимком памяти после удаления или отказом с причиной. */
sealed interface MemoryForget {

    /** Удаление прошло; [layers] — снимок после него. */
    data class Forgotten(val layers: MemoryLayers) : MemoryForget

    /** Тип неизвестен, недоступен для записи или текст пуст: причина — в [reason]. */
    data class Rejected(val reason: String) : MemoryForget
}

/**
 * Явная запись в память: пользователь выбирает тип памяти, а фразу пишет одной строкой.
 *
 * Тип называет человек — ровно так же, как его называет модель в служебном вызове
 * ([MemoryExtractor]). Код проверяет то, что проверить может: тип известен и в него
 * вообще можно писать ([MemoryLayer.writable]). Чего код не решает — «это данные задачи
 * или данные о пользователе»: раньше это решала таблица видов, и вместе с ней ушла
 * защита от того, что данные задачи уедут в долговременную память. Поэтому записи видно
 * в шторке памяти, и лишнюю можно забыть.
 *
 * Дальше всё как у машинной записи: то же слияние по тексту и те же пределы типа
 * ([MemoryRules]) — явная запись отличается от неё только источником.
 *
 * Оба типа, в которые можно писать, живут по профилю ([DEFAULT_PROFILE]) — как и долговременная
 * память. Рабочая память не привязана к чату: данные текущей задачи нужны и в новом чате,
 * поэтому запись из одного чата видна из любого другого. Различаются слои не областью,
 * а хранилищем и сроком жизни: рабочая лежит в памяти процесса и перезапуск сервера её
 * обнуляет, долговременная лежит в файле и перезапуск переживает.
 */
class MemoryWriter(
    private val workingMemory: MemoryStore,
    private val longTermMemory: MemoryStore
) {

    /** Снимок памяти: слои целиком и каталог типов для выбора. */
    fun layers(): MemoryLayers = MemoryLayers(
        working = workingMemory.get(DEFAULT_PROFILE),
        longTerm = longTermMemory.get(DEFAULT_PROFILE),
        types = MemoryLayers.catalogue()
    )

    /**
     * Записывает фразу в выбранный тип памяти. Сессия не нужна: оба типа живут по профилю,
     * и запись сразу видна из всех чатов.
     *
     * Итог — [MemoryWrite]: отказ приходит причиной, а не пустой памятью, чтобы
     * пользователь видел, почему «Запомнить» ничего не сохранило.
     */
    fun remember(layer: String, value: String): MemoryWrite {
        val memory = MemoryLayer.ofWire(layer) ?: return MemoryWrite.Rejected(UNKNOWN_LAYER)
        if (!memory.writable) return MemoryWrite.Rejected(NOT_WRITABLE)
        val record = MemoryRules.normalize(MemoryRecord(memory.wire, value))
            ?: return MemoryWrite.Rejected(EMPTY_VALUE)
        val store = storeOf(memory)
        val merge = MemoryRules.merge(store.get(DEFAULT_PROFILE), listOf(record), memory)
        store.put(DEFAULT_PROFILE, merge.records)
        return MemoryWrite.Written(record, memory, merge.evicted)
    }

    /**
     * Забывает фразу в выбранном типе памяти: удаляются записи с тем же текстом,
     * потому что текст — единственное, чем запись опознаётся.
     *
     * Забыть то, чего нет, — не ошибка: снимок возвращается как был, чтобы повторное
     * нажатие «✕» в интерфейсе не выглядело сбоем.
     */
    fun forget(layer: String, value: String): MemoryForget {
        val memory = MemoryLayer.ofWire(layer) ?: return MemoryForget.Rejected(UNKNOWN_LAYER)
        if (!memory.writable) return MemoryForget.Rejected(NOT_WRITABLE)
        if (value.isBlank()) return MemoryForget.Rejected(EMPTY_VALUE)
        val store = storeOf(memory)
        store.put(DEFAULT_PROFILE, MemoryRules.forget(store.get(DEFAULT_PROFILE), value))
        return MemoryForget.Forgotten(layers())
    }

    /**
     * Хранилище типа: рабочая память и долговременная лежат врозь, потому что по-разному
     * живут — память процесса против файла. Ключ у них общий ([DEFAULT_PROFILE]): область
     * одна, профиль.
     */
    private fun storeOf(layer: MemoryLayer): MemoryStore = when (layer) {
        MemoryLayer.LONG_TERM -> longTermMemory
        MemoryLayer.SHORT_TERM, MemoryLayer.WORKING -> workingMemory
    }

    companion object {

        /** Причина отказа: тип памяти не из трёх, названных в задании. */
        const val UNKNOWN_LAYER = "тип памяти неизвестен"

        /** Причина отказа: в краткосрочную память писать нечего — это сообщения диалога. */
        const val NOT_WRITABLE = "краткосрочная память — это сообщения диалога, её не пишут вручную"

        /** Причина отказа: текст записи пуст. */
        const val EMPTY_VALUE = "запись пуста"
    }
}
