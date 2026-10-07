package com.osvin.aichallenge.rag

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest

/**
 * Режим ответа: с опорой на базу и без неё.
 *
 * Режим — это не настройка качества, а условие сравнения: один и тот же вопрос, одна и та же
 * модель, один и тот же бюджет ответа, и разница только в том, дали ли модели найденный текст.
 */
enum class Mode(val title: String) {

    /** Вопрос → поиск по базе → фрагменты в запросе. */
    WITH_RAG("с RAG"),

    /** Вопрос → запрос как есть: модель отвечает тем, что помнит. */
    WITHOUT_RAG("без RAG")
}

/**
 * Ответ модели на один вопрос в одном режиме.
 *
 * Рядом с текстом лежит всё, чем этот ответ объясняется: найденные фрагменты (в режиме без RAG
 * список пуст — поиск не выполнялся), расход токенов как его посчитал API и время. Без этих чисел
 * сравнение режимов свелось бы к «кажется, с базой лучше»: непонятно, за что заплачено временем
 * и токенами.
 *
 * Токены необязательны: их сообщает API, и если он их не вернул, честнее показать прочерк
 * в отчёте, чем ноль, который выглядел бы как измерение.
 */
data class Answer(
    val question: String,
    val mode: Mode,
    val text: String,
    /**
     * Звено поиска: фрагменты, вектор вопроса и его время.
     *
     * Хранится целиком, потому что по нему страница дня 22 рисует конвейер: странице нужен и сам
     * вектор (звено «вопрос → вектор»), и время (звено «вектор → фрагменты»), а в готовом
     * [DeepSeekRequest] их уже не видно.
     */
    val retrieval: Retrieval,
    /**
     * Запрос к модели в том виде, в каком он ушёл: система и пользователь с контекстом.
     *
     * Хранится ради лога: требование дня — восстановить весь путь от вопроса до ответа, и контекст
     * в нём — то звено, которое нельзя восстановить задним числом. Собрать его заново из [sources]
     * можно было бы, но это был бы второй сборщик того же текста, и он мог бы разойтись с первым.
     */
    val messages: List<ChatMessage>,
    val promptTokens: Int?,
    val completionTokens: Int?,
    /** Причина остановки от API: `stop` — ответ кончился сам, `length` — упёрся в бюджет. */
    val finishReason: String?,
    /** Сколько шло обращение к модели. */
    val elapsedMillis: Long
) {

    /** Фрагменты, ушедшие в запрос: пусто в режиме без RAG, где поиск не выполнялся. */
    val sources: List<Source> get() = retrieval.sources
}

/**
 * Агент двух режимов: вопрос человека → ответ модели.
 *
 * Агент не решает, где искать, и не знает про индекс: поиск приходит контрактом [SourceFinder],
 * а модель — контрактом [LlmClient]. Отсюда два свойства, ради которых он так устроен. Первое:
 * режим без RAG не ищет — ветка, в которой поиск не вызывается, читается в [ask] целиком, и это
 * проверяется тестом с источником, который падает при обращении. Второе: агент не ходит в сеть
 * сам, поэтому сравнение режимов проверяется на подставном клиенте, а живой прогон отличается
 * только тем, какой клиент передан снаружи.
 *
 * Параметры генерации — поля конструктора, а не аргументы [ask]: один прогон обязан спрашивать
 * модель одинаково во всех десяти вопросах и в обоих режимах. Разные `temperature` по режимам
 * (например, ниже для RAG) дали бы разницу в ответах, которую нельзя списать ни на поиск,
 * ни на модель.
 *
 * `temperature = 0.0` — потому что набор проверяет факты, а не слог: при нуле ответы ближе
 * к повторяемым, и второй прогон не переписывает отчёт заново. Это не «правильная» температура
 * для агента вообще, а условие контрольного сравнения; менять её — параметр прогона.
 */
class RagAgent(
    private val llm: LlmClient,
    private val finder: SourceFinder,
    private val model: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val temperature: Double = DEFAULT_TEMPERATURE
) {

    /**
     * Отвечает на вопрос в заданном режиме.
     *
     * Порядок шагов — это и есть определение RAG: сначала вопрос уходит в поиск, и только найденное
     * определяет, что увидит модель. В режиме без RAG поиска нет, поэтому и фрагментов нет —
     * ни в запросе, ни в ответе.
     */
    suspend fun ask(question: String, mode: Mode): Answer {
        val retrieval = when (mode) {
            Mode.WITH_RAG -> finder.find(question)
            // Ноль здесь — не измерение, а его отсутствие: поиска не было, и это видно числом,
            // а не только пустым списком фрагментов.
            Mode.WITHOUT_RAG -> Retrieval(emptyList(), emptyList(), 0)
        }

        val messages = when (mode) {
            Mode.WITH_RAG -> Prompt.withContext(question, retrieval.sources)
            Mode.WITHOUT_RAG -> Prompt.withoutContext(question)
        }

        val started = System.nanoTime()
        val response = llm.complete(
            DeepSeekRequest(
                model = model,
                messages = messages,
                maxTokens = maxTokens,
                temperature = temperature
            )
        )
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        // Пустой список вариантов — не «ответ без текста», а сломанный обмен: молча вернуть
        // пустую строку значило бы записать в отчёт провал как результат сравнения.
        val choice = requireNotNull(response.choices.firstOrNull()) {
            "Модель вернула ответ без вариантов"
        }

        return Answer(
            question = question,
            mode = mode,
            text = choice.message.content,
            retrieval = retrieval,
            messages = messages,
            promptTokens = response.usage?.promptTokens,
            completionTokens = response.usage?.completionTokens,
            finishReason = choice.finishReason,
            elapsedMillis = elapsedMillis
        )
    }

    private companion object {

        /**
         * Бюджет ответа — значение приложения (`AppConfig.DEFAULT_MAX_TOKENS`): прогон спрашивает
         * модель так же, как её спрашивает приложение, и оба режима получают один потолок.
         *
         * Запас нужен не для трёх–пяти предложений: на прогоне с бюджетом вдвое меньше два ответа
         * без RAG пришли пустыми при полном расходе — модель тратит токены и на то, что в ответ
         * не попадает. Пустой ответ — честный результат сравнения (и он виден в отчёте вместе
         * с причиной остановки), но получать его из-за лимита, а не из-за отсутствия базы, значило бы
         * мерить не то, что мы сравниваем.
         */
        const val DEFAULT_MAX_TOKENS = 8192

        /** Ноль — ради повторяемости контрольного сравнения (см. KDoc класса). */
        const val DEFAULT_TEMPERATURE = 0.0
    }
}
