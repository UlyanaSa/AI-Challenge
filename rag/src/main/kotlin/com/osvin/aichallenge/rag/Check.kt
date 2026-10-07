package com.osvin.aichallenge.rag

/**
 * Что в ответе сошлось с ожиданием набора.
 *
 * Проверок три, и они отвечают на разные вопросы. **Факты** — знает ли ответ то, о чём спросили;
 * это единственная проверка, которая проходит в обоих режимах. **Попадание в базу** ([retrievedFacts])
 * — нашёл ли поиск то место, где ответ лежит; оно ничего не говорит о самом ответе и нужно, чтобы
 * отделить плохой поиск от плохого ответа. **Ссылки** ([citedFragments], [usedPages]) — опёрся ли
 * ответ на найденное или прошёл мимо; режим, который достал нужный фрагмент и не использовал его,
 * от режима без базы ничем не отличается.
 *
 * Все три вместе и есть сравнение: два режима отличаются друг от друга не «на глазок», а числом
 * засчитанных фактов и тем, откуда эти факты взялись.
 */
data class AnswerCheck(
    /** По факту на каждый пункт ожидания: засчитан или нет. */
    val facts: List<Boolean>,
    /** Номера фрагментов, на которые сослался ответ: «[2]» → 2. Чужие номера отброшены. */
    val citedFragments: List<Int>,
    /** Страницы, названные в самом ответе: «стр. 8» → 8. */
    val citedPages: List<Int>,
    /** Ожидаемые страницы, попавшие в выдачу поиска. Пусто в режиме без RAG: поиска не было. */
    val retrievedPages: List<Int>,
    /** По факту на пункт ожидания: есть ли его текст в найденных фрагментах. */
    val retrievedFacts: List<Boolean>,
    /** Ожидаемые страницы, на которые ответ сослался — номером фрагмента или страницей. */
    val usedPages: List<Int>
) {

    /** Сколько фактов засчитано. */
    val matched: Int get() = facts.count { it }

    /** Сколько фактов ожидалось. */
    val total: Int get() = facts.size

    /** Сколько фактов было в найденных фрагментах. */
    val retrieved: Int get() = retrievedFacts.count { it }

    /**
     * Оценка по шкале задания: 0 — ответа нет или он неверен, 1 — правильный, но неполный,
     * 2 — ответ содержит все ожидаемые факты.
     *
     * Считается из фактов, а не из общего впечатления: у вопроса без фактов оценки не существует,
     * а у вопроса с двумя 1 балл означает «сказал половину». Одним числом такое не выразить, поэтому
     * в отчёте рядом с оценкой всегда стоят факты — «1 из 2» читается, «1» — нет.
     */
    val score: Int get() = when {
        total == 0 || matched == 0 -> 0
        matched < total -> 1
        else -> 2
    }
}

/**
 * Итог вопроса: чего не хватило — поиска, модели или ничего.
 *
 * Разделение нужно заданию и прогону: «ответ неверный» ничего не говорит о том, что улучшать.
 * Ошибка поиска ([RETRIEVAL_ERROR]) означает, что нужного текста в выдаче не было вовсе — тут
 * виноват поиск, и никакая модель ответить правильно не могла. Ошибка генерации
 * ([GENERATION_ERROR]) — что нужный текст был, а ответ всё равно неполный или неверный. Для вопроса,
 * ответа на который в базе нет, второе и есть ошибка: правильным поведением там считается признать
 * нехватку сведений, а не выдать ответ из памяти.
 */
enum class Outcome(val title: String) {
    ANSWERED("верно"),
    RETRIEVAL_ERROR("ошибка поиска"),
    GENERATION_ERROR("ошибка генерации")
}

/**
 * Сверка ответа с ожиданием набора.
 *
 * Сопоставление идёт по тексту ответа, а не по совпадению с эталонной формулировкой: модель
 * пересказывает, и признак факта — основа слова или словосочетание ([Fact.keywords]), а не строка
 * целиком. Хватает любого признака: они перечисляют, как это называют, а не что все слова должны
 * встретиться.
 *
 * Ссылки разбираются отдельно от фактов, потому что это разные утверждения. Ответ «он схватил его
 * за нос» факт содержит, а на источник не ссылается; ответ «[3] там сказано про нос» ссылается,
 * но может не содержать самого факта. Считать их одним числом значило бы потерять, что именно
 * сделала модель — прочитала или вспомнила.
 *
 * Номера фрагментов фильтруются по выдаче: модель может назвать фрагмент, которого ей не давали
 * (например, «[6]» при пяти найденных). Такой номер — не ссылка на источник, а ошибка в ответе,
 * и в отчёт он не попадает, чтобы «ссылался на базу» не оказалось правдой по случайному числу.
 */
object Check {

    /** Текст записывается в одном виде: регистр, «ё», знаки препинания — всё это не о фактах. */
    fun normalize(text: String): String = text
        .lowercase()
        .replace('ё', 'е')
        .replace(NOT_WORD, " ")
        .trim()
        .replace(SPACES, " ")

    /** Есть ли в нормализованном тексте признак факта: словосочетание — подстрокой, слово — основой. */
    fun matches(text: String, keyword: String): Boolean {
        val wanted = normalize(keyword)
        if (wanted.isEmpty()) return false
        if (wanted.contains(' ')) return text.contains(wanted)
        return text.split(' ').any { word -> word.startsWith(wanted) }
    }

    /** Сверяет ответ с ожиданием вопроса и с выдачей поиска, если она была. */
    fun evaluate(question: ControlQuestion, answer: String, sources: List<Source>): AnswerCheck {
        val text = normalize(answer)
        val citedFragments = citedFragments(answer, sources)
        val citedPages = citedPages(answer)
        val citedSourcePages = sources.filter { it.rank in citedFragments }.flatMap { it.pages }
        val fragments = sources.map { normalize(it.text) }

        return AnswerCheck(
            facts = question.facts.map { fact -> factsIn(listOf(text), fact) },
            citedFragments = citedFragments,
            citedPages = citedPages,
            retrievedPages = question.pages.filter { page -> sources.any { page in it.pages } },
            // Попадание считается по тексту фрагментов, а не по номерам страниц: чанк может лежать
            // на нужной странице и не содержать ответа — тогда «источник найден» было бы неправдой,
            // а ответ, собранный из соседнего текста, выглядел бы подтверждённым базой.
            retrievedFacts = question.facts.map { fact -> factsIn(fragments, fact) },
            usedPages = question.pages.filter { page -> page in citedPages || page in citedSourcePages }
        )
    }

    /** Есть ли факт хоть в одном из текстов: признак факта ищется тем же сопоставлением. */
    private fun factsIn(texts: List<String>, fact: Fact): Boolean =
        texts.any { text -> fact.keywords.any { keyword -> matches(text, keyword) } }

    /**
     * Чем кончился вопрос: ответ сошёлся, поиск не нашёл или модель не справилась.
     *
     * Сначала проверяется поиск — и это главное в порядке проверок. Если нужного текста в выдаче
     * не было, вопрос закрыт как поисковая ошибка независимо от того, что ответила модель: правильный
     * ответ тут означал бы ответ по памяти, а заданию нужно знать, что база не сработала, а не то,
     * что модель угадала. По той же причине верным считается только ответ, и сошедшийся с ожиданием,
     * и подкреплённый найденным текстом.
     *
     * Для вопроса без ответа в базе поиска нет вовсе: там ошибка генерации — это ответ по памяти,
     * потому что правильным поведением было признать нехватку сведений.
     */
    fun outcome(question: ControlQuestion, check: AnswerCheck): Outcome = when {
        question.absent -> if (check.score == 2) Outcome.ANSWERED else Outcome.GENERATION_ERROR
        !check.retrievedFacts.any { it } -> Outcome.RETRIEVAL_ERROR
        check.score == 2 -> Outcome.ANSWERED
        else -> Outcome.GENERATION_ERROR
    }

    /** Номера фрагментов, названные в ответе: только те, что действительно были в выдаче. */
    fun citedFragments(answer: String, sources: List<Source>): List<Int> =
        FRAGMENT.findAll(answer)
            .mapNotNull { match -> match.groupValues[1].toIntOrNull() }
            .filter { rank -> sources.any { it.rank == rank } }
            .distinct()
            .sorted()
            .toList()

    /** Страницы, названные в ответе: «стр. 8», «стр. 8–9», «странице 25». */
    fun citedPages(answer: String): List<Int> = PAGES.findAll(answer)
        .flatMap { match ->
            val from = match.groupValues[1].toIntOrNull() ?: return@flatMap emptyList()
            val to = match.groupValues[2].toIntOrNull() ?: from
            if (to < from) listOf(from) else (from..to).toList()
        }
        .distinct()
        .sorted()
        .toList()

    /** Ссылка на фрагмент в ответе: номер в квадратных скобках, как в запросе. */
    private val FRAGMENT = Regex("\\[(\\d+)]")

    /** Страница словами: «стр.», «страница», «странице» — сокращения модель выбирает сама. */
    private val PAGES = Regex("стр[а-я]*\\.?\\s*(\\d+)(?:\\s*[–—-]\\s*(\\d+))?")

    /** Всё, что не буква и не цифра: дефисы и точки внутри слов фактом не являются. */
    private val NOT_WORD = Regex("[^0-9a-zа-я]+")

    private val SPACES = Regex("\\s+")
}
