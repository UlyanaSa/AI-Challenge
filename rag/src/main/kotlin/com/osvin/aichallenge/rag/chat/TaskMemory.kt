package com.osvin.aichallenge.rag.chat

import com.osvin.aichallenge.rag.Check

/**
 * Термин, зафиксированный человеком: слово и то, что оно значит в этом разговоре.
 *
 * Пара «слово — значение», а не одна строка, потому что термин без значения бесполезен: «сын»
 * в разговоре о «Бесах» — это и Пётр Степанович, и Николай Всеволодович, и ответ, построенный
 * на чужом значении, звучит уверенно и ошибается. Значение фиксирует человек, а не модель:
 * её дело — повторить его и держаться дальше.
 */
data class TaskTerm(val term: String, val meaning: String)

/**
 * Что модель предлагает записать в память задачи после очередной реплики человека.
 *
 * Все поля необязательные и означают «состояние целиком», а не приращение: модель читает разговор
 * и возвращает то, что должно быть в памяти. Приращение было бы хуже — оно теряет память при любой
 * неудачной реплике, а восстановить забытое из истории нельзя: история обрезается окном.
 */
data class TaskMemoryUpdate(
    val goal: String? = null,
    val clarifications: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val terms: List<TaskTerm> = emptyList()
)

/**
 * Память задачи: то, что человек зафиксировал в этом диалоге, — цель, уточнения, правила и термины.
 *
 * Задание дня просит «память задачи», и она отличается от памяти предыдущих дней не названием,
 * а тем, что не даёт длинному разговору потерять предмет. История диалога отвечает на вопрос
 * «что было сказано», и её приходится обрезать окном: чем дольше разговор, тем меньше в окне
 * начала. Память задачи отвечает на вопрос «о чём мы вообще и по каким правилам» — она не обрезается
 * и едет в каждый запрос к модели целиком, поэтому цель и договорённости не зависят от того, сколько
 * реплик уже прошло. Это и есть день 25 в одной фразе: длинный диалог держится не историей,
 * а памятью, а история — только последними ходами.
 *
 * Поля названы словами задания, чтобы связь читалась без перевода: [goal] — «что является целью
 * диалога», [clarifications] — «что пользователь уже уточнил», [constraints] — «какие ограничения
 * зафиксированы», [terms] — «какие термины зафиксированы».
 */
data class TaskMemory(
    /** Цель разговора одной фразой: чего человек хочет от диалога в целом, а не от реплики. */
    val goal: String? = null,
    /** Что человек уточнил по ходу: короткие фразы, по одной мысли. */
    val clarifications: List<String> = emptyList(),
    /** Правила и рамки, которые человек установил («только по первой главе», «не больше трёх пунктов»). */
    val constraints: List<String> = emptyList(),
    /** Термины этого разговора и их значения. */
    val terms: List<TaskTerm> = emptyList()
) {

    /** Пустая память: диалог только начался, и в промпт её блок не попадает. */
    val isEmpty: Boolean
        get() = goal.isNullOrBlank() && clarifications.isEmpty() && constraints.isEmpty() && terms.isEmpty()

    /**
     * Блок памяти для промпта: тот же текст, что видит человек в отчёте.
     *
     * Один и тот же текст на промпт и на отчёт — решение не косметическое: разбирая ответ, человек
     * должен видеть ровно то, что видела модель, иначе «модель забыла ограничение» и «ограничение
     * в промпт не попало» выглядят одинаково. Пустые поля не печатаются: заголовок без строк
     * читался бы как «память есть, а в ней ничего».
     */
    fun render(): String {
        if (isEmpty) return ""
        val lines = ArrayList<String>()
        goal?.takeIf { it.isNotBlank() }?.let { lines += "$GOAL_LABEL: $it" }
        if (clarifications.isNotEmpty()) lines += "$CLARIFIED_LABEL: " + clarifications.joinToString("; ")
        if (constraints.isNotEmpty()) lines += "$CONSTRAINTS_LABEL: " + constraints.joinToString("; ")
        if (terms.isNotEmpty()) {
            lines += "$TERMS_LABEL: " + terms.joinToString("; ") { "«${it.term}» — ${it.meaning}" }
        }
        return (listOf(HEADER) + lines).joinToString("\n")
    }

    /**
     * Дополняет память тем, что вернула модель, и ничего из уже зафиксированного не выбрасывает.
     *
     * Слияние, а не замена, потому что забыть здесь дороже, чем запомнить лишнее: обрезанное окно
     * истории уже не вернёт потерянное ограничение, и разговор поедет дальше без него — ровно тот
     * провал, ради которого память и заводилась. Повтор не дублируется, а переезжает в конец
     * (свежее считается важнее) — то же правило, что у записей памяти в `:agent` (`MemoryRules.merge`),
     * и та же причина: одинаковые правила слияния в двух местах не должны расходиться.
     *
     * Цель — единственное поле, которое модель может переписать, и только непустой строкой:
     * уточнение цели по ходу разговора нормально, а пустой ответ модели означает «не сказано»,
     * и терять из-за него цель нельзя.
     */
    fun merge(update: TaskMemoryUpdate): TaskMemory = TaskMemory(
        goal = update.goal?.escape() ?: goal,
        clarifications = mergeLines(clarifications, update.clarifications, MAX_CLARIFICATIONS),
        constraints = mergeLines(constraints, update.constraints, MAX_CONSTRAINTS),
        terms = mergeTerms(terms, update.terms)
    )

    /** Сколько пунктов памяти заполнено: по этому числу отчёт видит, растёт память или стоит. */
    val size: Int
        get() = (if (goal.isNullOrBlank()) 0 else 1) + clarifications.size + constraints.size + terms.size

    companion object {

        /** Заголовок блока — тот же приём, что у профиля и состояния задачи в `:agent`. */
        const val HEADER = "Память задачи (учитывай в каждом ответе):"

        const val GOAL_LABEL = "цель"
        const val CLARIFIED_LABEL = "уточнено"
        const val CONSTRAINTS_LABEL = "ограничения"
        const val TERMS_LABEL = "термины"

        /**
         * Пределы памяти: списки короткие, потому что память едет в каждый запрос к модели.
         *
         * Числа взяты как у записей памяти в `:agent` (`MemoryRules`): двадцать уточнений, десять
         * ограничений, двести символов на пункт. Ограничения строже уточнений намеренно — правило
         * действует на весь разговор, и десяток правил уже не удержать в голове ни человеку, ни модели.
         */
        const val MAX_CLARIFICATIONS = 20
        const val MAX_CONSTRAINTS = 10
        const val MAX_TERMS = 20
        const val MAX_CHARS = 200

        /**
         * Слияние списка фраз: новое добавляется, повтор переезжает в конец, лишнее вытесняется
         * с начала.
         *
         * Сравнение идёт по нормализованному тексту ([Check.normalize]): «Отвечай кратко» и «отвечай
         * кратко.» — одно правило, и два его экземпляра в памяти были бы видны человеку как две
         * разные договорённости. Вытесняется начало списка, потому что конец — это то, о чём
         * договорились позже.
         */
        private fun mergeLines(fixed: List<String>, incoming: List<String>, limit: Int): List<String> {
            val merged = LinkedHashMap<String, String>()
            (fixed + incoming).forEach { line ->
                val text = line.escape() ?: return@forEach
                val key = Check.normalize(text)
                if (key.isEmpty()) return@forEach
                merged.remove(key)
                merged[key] = text
            }
            return merged.values.toList().takeLast(limit)
        }

        /** Слияние терминов: ключ — само слово, значение берётся из свежего упоминания. */
        private fun mergeTerms(fixed: List<TaskTerm>, incoming: List<TaskTerm>): List<TaskTerm> {
            val merged = LinkedHashMap<String, TaskTerm>()
            (fixed + incoming).forEach { term ->
                val word = term.term.escape() ?: return@forEach
                val meaning = term.meaning.escape() ?: return@forEach
                val key = Check.normalize(word)
                if (key.isEmpty()) return@forEach
                merged.remove(key)
                merged[key] = TaskTerm(word, meaning)
            }
            return merged.values.toList().takeLast(MAX_TERMS)
        }

        /** Пункт памяти: пустое отбрасывается, длинное обрезается — память не растёт бесконечно. */
        private fun String.escape(): String? {
            val text = trim().replace(Regex("\\s+"), " ")
            if (text.isEmpty()) return null
            return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS - 1).trimEnd() + "…"
        }
    }
}
