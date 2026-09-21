package com.osvin.aichallenge.agent

/** Чем закончилось объявление правила: снимком правил или отказом с причиной. */
sealed interface InvariantWrite {

    /** Правило прошло проверки и легло в список; [snapshot] — правила после записи. */
    data class Written(val snapshot: InvariantSnapshot) : InvariantWrite

    /** Вид неизвестен, формулировка пуста, длинна или места в списке нет: причина — в [reason]. */
    data class Rejected(val reason: String) : InvariantWrite
}

/** Чем закончилось удаление правила: снимком правил после него или отказом с причиной. */
sealed interface InvariantForget {

    /** Удаление прошло; [snapshot] — правила после него. */
    data class Forgotten(val snapshot: InvariantSnapshot) : InvariantForget

    /** Вид неизвестен или формулировка пуста: причина — в [reason]. */
    data class Rejected(val reason: String) : InvariantForget
}

/**
 * Инварианты как явное действие человека: объявить правило и убрать правило.
 *
 * Правила объявляет человек, а не модель, — ровно так же, как в память пишет человек
 * ([MemoryWriter]), и по той же причине: инвариант — не следствие диалога, а условие над ним,
 * поэтому извлекать его из переписки нечем и незачем ([Invariant]). Модель к формулировке
 * правил не подпускают и дальше: она их читает и по ним отказывается работать, но написать
 * себе правило, которое потом определит её же ответ, не может — это было бы решением о работе,
 * принятым исполнителем.
 *
 * Код проверяет то, что проверить может ([InvariantRules]): вид известен, формулировка не пуста
 * и влезает в предел, места в списке хватает. Чего код не решает — «это правило проекта или
 * пожелание на один раз»: разницу видно только человеку, поэтому правило лежит в общей шторке,
 * уходит в каждый запрос и убирается так же явно, как объявляется.
 *
 * Дальше всё как у машинной записи: та же замена по тексту ([InvariantRules.add]) — правило
 * отличается от записи памяти только источником и местом хранения.
 *
 * Правила живут по профилю ([DEFAULT_PROFILE]), как память и профиль, поэтому объявленное
 * в одном чате видно из любого другого. Хранилище отдельное ([InvariantStore]): правило —
 * не запись памяти, и стратегия контекста над ним не властна.
 */
class InvariantWriter(private val store: InvariantStore) {

    /**
     * Снимок правил: сами инварианты и каталог видов для выбора.
     *
     * Отдаются рабочие правила — те, что уйдут в запрос ([InvariantStore.invariantsOrDefault]),
     * а не только лежащие в хранилище: человек правит список, который действует, и умолчание
     * проекта в нём уже подставлено.
     */
    fun snapshot(profileId: String = DEFAULT_PROFILE): InvariantSnapshot =
        InvariantSnapshot(invariants = store.invariantsOrDefault(profileId), kinds = InvariantKind.info)

    /**
     * Объявляет правило: вид выбирает человек, формулировку пишет одной фразой.
     *
     * Правило добавляется к действующим ([InvariantStore.invariantsOrDefault]), а не к пустому
     * списку: иначе первое же объявленное правило стёрло бы умолчание проекта целиком.
     * Итог — [InvariantWrite]: отказ приходит причиной, а не прежним списком, чтобы человек
     * видел, почему «Запомнить» ничего не сохранило.
     */
    fun remember(kind: String?, value: String?): InvariantWrite {
        val invariant = when (val decision = InvariantRules.normalize(kind, value)) {
            is InvariantDecision.Accepted -> decision.invariant
            is InvariantDecision.Rejected -> return InvariantWrite.Rejected(decision.reason)
        }
        val merged = InvariantRules.add(store.invariantsOrDefault(), invariant)
        // Предел проверяется по списку после слияния: замена формулировки места не занимает,
        // и правилу, объявленному вместо прежнего, предел не мешает.
        InvariantRules.limitReason(merged.size)?.let { return InvariantWrite.Rejected(it) }
        store.put(DEFAULT_PROFILE, merged)
        return InvariantWrite.Written(snapshot())
    }

    /**
     * Убирает правило: правило опознаётся по формулировке, как и при записи, поэтому список
     * фильтруется по тексту; вид сужает поиск, если назван.
     *
     * Список берётся действующий ([InvariantStore.invariantsOrDefault]) и сохраняется явно:
     * человек убрал одно правило из умолчания проекта — в хранилище ложится остаток, а не
     * пустой ответ. Иначе «ещё не задавали» вернуло бы умолчание целиком, и убранное правило
     * объявилось бы снова. По той же причине, убрав все правила, в хранилище остаётся пустой
     * список: это «правил нет», а не «не задавали».
     *
     * Убрать то, чего нет, — не ошибка: снимок вернётся как был, чтобы повторное нажатие «✕»
     * у строки правила не выглядело сбоем.
     */
    fun forget(kind: String?, value: String?): InvariantForget {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return InvariantForget.Rejected(InvariantRules.EMPTY_VALUE)
        // Вид необязателен: у строки шторки он есть, но правило, которое человек видит, опознаётся
        // формулировкой — поэтому пустой вид не мешает убрать правило, а неизвестный отклоняется:
        // такого вида нет, и искать по нему нечего.
        val named = kind?.trim()?.takeIf { it.isNotEmpty() }?.let {
            InvariantKind.ofWire(it) ?: return InvariantForget.Rejected(InvariantRules.UNKNOWN_KIND)
        }
        val left = store.invariantsOrDefault().filterNot { rule ->
            val sameText = rule.value.trim().equals(text, ignoreCase = true)
            val sameKind = named == null || InvariantKind.ofWire(rule.kind) == named
            sameText && sameKind
        }
        store.put(DEFAULT_PROFILE, left)
        return InvariantForget.Forgotten(snapshot())
    }
}
