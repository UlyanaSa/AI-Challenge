package com.osvin.aichallenge.rag

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.models.DeepSeekRequest

/**
 * Поисковый запрос: что спросил человек и что ушло в поиск.
 *
 * Хранятся оба, и это не удобство отчёта, а требование к конвейеру: исходный вопрос идёт в запрос
 * к модели, а переписанный — только в embedding и поиск. Если хранить одно число, замена одного
 * другим при генерации ответа проходит незаметно, и сравнение начинает мерить переписанный вопрос
 * вместо заданного.
 *
 * [rewritten] совпадает с [original], когда переписывания нет (`QueryRewriter` не задан) или когда
 * модель вернула пустой ответ: поиск по пустой строке вернул бы произвольные чанки, и выглядело бы
 * это как промах поиска, а не как отсутствие переписывания.
 */
data class Rewrite(
    val original: String,
    val rewritten: String,
    val millis: Long
) {

    /** Переписывание изменило запрос — по этому признаку считается польза этапа (§16 задания). */
    val changed: Boolean get() = rewritten.trim() != original.trim()
}

/**
 * Преобразование вопроса в поисковый запрос.
 *
 * Отдельный контракт, потому что вариантов переписывания несколько (модель, правила, отсутствие)
 * и задание требует менять этап, не трогая конвейер. Как и у поиска, у переписывания нет права
 * уйти в сеть незаметно: подставной переписыватель в тестах возвращает заданную строку, и по ней
 * видно, что в поиск ушёл именно переписанный запрос, а в модель — исходный вопрос.
 */
interface QueryRewriter {

    /** Как называется вариант переписывания в отчёте. */
    val name: String

    suspend fun rewrite(question: String): Rewrite
}

/**
 * Query Rewrite моделью: вопрос и правила этапа уходят в тот же клиент, что отвечает на вопросы.
 *
 * `temperature = 0.0` — по той же причине, что и у ответов: набор сравнивается, и переписывание
 * от прогона к прогону не должно меняться само по себе.
 *
 * Этап идёт в режиме без рассуждения (`reasoningEffort = none`), и это исправленная ошибка первого
 * прогона. Отвечающая модель рассуждающая: мысли приходят отдельным полем, но тратят тот же бюджет
 * завершения, что и ответ. На прогоне с бюджетом в 256 токенов ответ пришёл пустым, переписывание
 * вернуло исходный вопрос, и все десять вопросов показали «переписывание изменило запрос в 0».
 * Поднять бюджет не помогло: на одном и том же вопросе рассуждение заняло один раз 1338 токенов,
 * а другой раз — больше 4096, и ответ снова пришёл пустым. Гарантии у бюджета нет, потому что
 * длину мысли модель выбирает сама, — а извлечению ключевых слов рассуждение не нужно вовсе.
 * Замер после отказа от него: 9–16 токенов ответа и около секунды на вопрос вместо тысяч токенов
 * и десятков секунд. Бюджет в 512 токенов оставлен с запасом на длинный запрос.
 *
 * Разбор ответа прощает модели две привычки, и это не небрежность, а условие сопоставимости: метку
 * «Запрос:» в начале строки и переносы. Метка — не часть запроса, а переносы embedding читает как
 * тот же пробел. Обещание «одна строка» остаётся требованием к модели, но конвейер не падает, если
 * она его не выполнила.
 */
class LlmQueryRewriter(
    private val llm: LlmClient,
    private val model: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val temperature: Double = DEFAULT_TEMPERATURE
) : QueryRewriter {

    override val name: String = "модель $model (без рассуждения)"

    override suspend fun rewrite(question: String): Rewrite {
        val started = System.nanoTime()
        val response = llm.complete(
            DeepSeekRequest(
                model = model,
                messages = Prompt.rewrite(question),
                maxTokens = maxTokens,
                temperature = temperature,
                reasoningEffort = REASONING_EFFORT
            )
        )
        val millis = (System.nanoTime() - started) / 1_000_000

        val choice = response.choices.firstOrNull()
        val query = choice?.message?.content?.let(::tidyQuery)
        return Rewrite(question, query ?: question, millis)
    }

    private companion object {

        /** Бюджет на строку запроса: см. KDoc класса (на прогоне — 9–16 токенов). */
        const val DEFAULT_MAX_TOKENS = 512

        /** Режим без рассуждения: этап механический, мысли здесь только съедают бюджет ответа. */
        const val REASONING_EFFORT = "none"

        const val DEFAULT_TEMPERATURE = 0.0
    }
}

/**
 * Строка запроса из ответа модели: снимается метка, склеиваются строки, сжимаются пробелы.
 *
 * Метки снимаются списком, потому что модель выбирает их сама («Запрос:», «Поисковый запрос:»,
 * «Query:»); непонятую метку удалять нечем, и она остаётся частью запроса — одно лишнее слово
 * в embedding весит меньше, чем выброшенная по ошибке половина запроса.
 *
 * Функция вынесена из класса дня 22 и объявлена общей не ради экономии строк: чистку делает
 * и переписыватель дня 25 ([com.osvin.aichallenge.rag.chat.ChatRewriter]), а разойтись эти два
 * правила не должны — иначе запрос одного и того же вида приходил бы в поиск в двух разных формах,
 * и «чат ищет хуже» объяснялось бы разбором ответа, а не качеством запроса.
 */
internal fun tidyQuery(raw: String?): String? {
    val text = (raw ?: return null).lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .replace(REWRITE_SPACES, " ")
        .trim()
    val withoutLabel = REWRITE_LABELS.fold(text) { acc, label ->
        if (acc.startsWith(label, ignoreCase = true)) acc.substring(label.length).trim() else acc
    }
    return withoutLabel.ifEmpty { null }
}

private val REWRITE_LABELS = listOf("Поисковый запрос:", "Запрос:", "Query:", "Rewritten query:")

private val REWRITE_SPACES = Regex("\\s+")
