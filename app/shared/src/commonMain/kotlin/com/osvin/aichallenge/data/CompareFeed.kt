package com.osvin.aichallenge.data

/**
 * Один ход сравнения: вопрос человека и ответ каждой сборки на него.
 *
 * Ход, а не два отдельных разговора: вопрос у сборок один, и окна читаются построчно —
 * слева ответ «как сейчас», справа оптимизированный, — поэтому вопрос и оба ответа
 * лежат вместе. Ответа ещё нет (`null`) у окна, которое не договорило: пока идёт запрос,
 * незаполнены оба, и каждое окно показывает своё ожидание.
 *
 * @param question Что спросил человек.
 * @param baseline Ответ сборки «как сейчас»; null — ответа ещё нет.
 * @param optimized Ответ оптимизированной сборки; null — ответа ещё нет.
 */
data class CompareTurn(
    val question: String,
    val baseline: PaneAnswer? = null,
    val optimized: PaneAnswer? = null
) {

    /** Ответ окна по метке панели; null — ответа этого окна ещё нет. */
    fun answer(pane: String): PaneAnswer? = when (pane) {
        Pane.BASELINE -> baseline
        Pane.OPTIMIZED -> optimized
        else -> null
    }
}

/**
 * Лента сравнения: ходы, описание окон и состояние запроса.
 *
 * Живёт в памяти приложения, а не в хранилище чатов, и это намеренно: у сравнения нет
 * своей истории в чате — окна производят по два ответа на один вопрос, и запись их
 * в чат сделала бы обычную ленту нечитаемой (на каждый вопрос по три сообщения).
 * Хранилище поэтому не меняется вовсе, а сравнение начинается заново при перезапуске —
 * так же, как заново собирается и всё, что оно измеряет.
 *
 * @param turns Ходы сравнения, старые — первыми.
 * @param panes Описание окон с сервера: чем сборки отличаются.
 * @param loading Идёт запрос: оба окна ждут ответа.
 * @param pendingQuestion Заданный и ещё не отвеченный вопрос; null — такого нет.
 * @param error Отказ всего запроса (сервер недоступен, набор отвергнут); null — отказа нет.
 */
data class CompareFeed(
    val turns: List<CompareTurn> = emptyList(),
    val panes: List<PaneInfo> = emptyList(),
    val loading: Boolean = false,
    val pendingQuestion: String? = null,
    val error: String? = null
) {

    /** Описание окна по метке панели; null — описание не прочиталось. */
    fun pane(pane: String): PaneInfo? = panes.firstOrNull { it.pane == pane }
}
