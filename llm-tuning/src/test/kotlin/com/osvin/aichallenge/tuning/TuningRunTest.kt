package com.osvin.aichallenge.tuning

import com.osvin.aichallenge.agent.LlmClient
import com.osvin.aichallenge.agent.LlmUnavailableException
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import com.osvin.aichallenge.models.DeepSeekResponse
import com.osvin.aichallenge.models.config.AppConfig
import com.osvin.aichallenge.rag.ControlQuestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Тесты прогона: числа сравнения складываются из ответов, а не из обещаний.
 *
 * Проверяется здесь ровно то, на чём держится вывод дня: средняя оценка считается по всем
 * вопросам набора (отказ — ноль, а не пропуск), расход токенов и время берутся из ответа и часов,
 * а отказ одного вопроса назван причиной и не мешает ответить на остальные. Ошибка в любом из
 * этих мест не видна в отчёте как ошибка — она видна как вывод о профилях, поэтому цена проверки
 * здесь выше обычной.
 *
 * Клиент — подставной ([StubClient]), и в этом главное решение теста: он заранее знает ответы,
 * расход токенов и причину отказа, поэтому ожидаемые числа известны до прогона. Ни сети, ни
 * демона Ollama тест не касается: живой прогон зависит от машины, повторяется минутами и
 * не может быть условием сборки.
 *
 * Ответы подставного клиента собраны по фактам настоящих вопросов набора ([KmpQuestions]):
 * полный ответ называет все факты вопроса, частичный — один, а на третий вопрос клиент отвечает
 * отказом. Это и делает проверку проверкой: оценка считается той же функцией `:rag`, и если
 * факты вопроса изменятся, числа теста изменятся вместе с ними — а не останутся «правильными»
 * сами по себе.
 */
class TuningRunTest {

    private val q01 = question("q01")
    private val q02 = question("q02")
    private val q03 = question("q03")

    /** Ответ, закрывающий все три факта `q01`: expect, actual и реализация для каждой платформы. */
    private val fullAnswer =
        "В общем коде объявляют expect-функцию без тела, а в исходниках каждой платформы пишут её " +
            "actual-реализацию: для каждой платформы нужен свой actual."

    /**
     * Ответ, закрывающий один факт `q02` из двух: называет `commonMain`, но не говорит, что он
     * компилируется во все таргеты. Такой ответ и должен получить 1 балл из 2 — половину.
     */
    private val partialAnswer =
        "commonMain — это набор исходников, где лежит код, общий для разных платформ."

    @Test
    fun `итог профиля складывается из оценок, фактов, времени и токенов ответов`() = runBlocking {
        val client = StubClient(
            replies = mapOf(
                q01.question to Reply(fullAnswer, promptTokens = 100, completionTokens = 50),
                q02.question to Reply(partialAnswer, promptTokens = 100, completionTokens = 30),
                q03.question to Reply(reason = TEST_FAILURE)
            )
        )

        val result = TuningRunner(client).run(baseline(), listOf(q01, q02, q03))

        assertEquals(listOf("q01", "q02", "q03"), result.answers.map { it.id }, "порядок вопросов набора")
        assertEquals(listOf(2, 1, 0), result.answers.map { it.score }, "оценка: все факты, часть, отказ")
        assertEquals(1.0, result.averageScore, "средняя по трём вопросам: (2 + 1 + 0) / 3")
        assertEquals(1, result.full)
        assertEquals(1, result.partial)
        assertEquals(1, result.wrong, "отказ считается нулём и входит в неверные")
        assertEquals(1, result.failed, "отказ назван отдельно")
        assertEquals(listOf(3, 2, 3), result.answers.map { it.total }, "факты ожиданий вопросов набора")
        assertEquals(listOf(3, 1, 0), result.answers.map { it.matched })
        assertEquals(4, result.matchedFacts)
        assertEquals(8, result.totalFacts)
        assertEquals(200, result.promptTokens, "токены запроса двух ответивших вопросов")
        assertEquals(80, result.completionTokens, "токены ответа двух ответивших вопросов")

        // Время измеряется часами, а не берётся из чисел теста: каждый вопрос подставной клиент
        // отвечает не мгновенно, и среднее обязано быть не меньше задержки одного ответа.
        assertTrue(result.averageMillis >= DELAY_MILLIS, "среднее время вопроса: ${result.averageMillis}")
        assertTrue(result.millis >= DELAY_MILLIS * 3, "время прогона: ${result.millis}")
        // Скорость — токены ответа, делённые на время генерации: больше, чем даёт задержка
        // клиента, она быть не может, и это ловит перепутанный делитель.
        assertTrue(result.tokensPerSecond > 0.0, "скорость: ${result.tokensPerSecond}")
        assertTrue(
            result.tokensPerSecond <= 80 * MILLIS_PER_SECOND / (DELAY_MILLIS * 3),
            "скорость: ${result.tokensPerSecond}"
        )
    }

    @Test
    fun `отказ вопроса назван причиной и не мешает ответить на остальные`() = runBlocking {
        val client = StubClient(
            replies = mapOf(
                q03.question to Reply(reason = TEST_FAILURE),
                q01.question to Reply(fullAnswer, promptTokens = 10, completionTokens = 5),
                q02.question to Reply(partialAnswer, promptTokens = 10, completionTokens = 5)
            )
        )

        // Отказавший вопрос идёт первым: если бы прогон падал на отказе, следующие ответы
        // не появились бы вовсе, и в отчёте вопросов стало бы меньше, чем в наборе.
        val result = TuningRunner(client).run(baseline(), listOf(q03, q01, q02))

        assertEquals(3, result.answers.size, "отказ не выкидывает вопрос из набора")
        val failed = result.answers.first()
        assertEquals(TEST_FAILURE, failed.failed, "причина отказа — та, которую назвал клиент")
        assertEquals("", failed.answer)
        assertEquals(0, failed.score)
        assertEquals(3, failed.total, "факты вопроса остаются в знаменателе и после отказа")
        assertEquals(listOf(2, 1), result.answers.drop(1).map { it.score }, "остальные вопросы отвечены")
        assertTrue(result.answers.drop(1).all { it.failed == null })
        assertEquals(1, result.failed)
        assertEquals(1.0, result.averageScore, "средняя: (0 + 2 + 1) / 3")
    }

    @Test
    fun `базовый профиль спрашивает как чат дня 27, профиль с инструкцией — системным сообщением`() =
        runBlocking {
            val client = StubClient(replies = mapOf(q01.question to Reply(fullAnswer)))
            val runner = TuningRunner(client)
            val baseline = baseline()

            runner.run(baseline, listOf(q01))

            val baseRequest = client.requests.single()
            assertEquals(listOf("system", "user"), baseRequest.messages.map { it.role })
            assertEquals(
                listOf(q01.question, q01.question),
                baseRequest.messages.map { it.content },
                "без своей инструкции роль системного промпта играет сам вопрос"
            )
            assertEquals(AppConfig.DEFAULT_MAX_TOKENS, baseRequest.maxTokens, "бюджет чата дня 27")
            assertNull(baseRequest.temperature, "температуру задаёт транспорт из конфигурации профиля")
            assertEquals(baseline.config.ollamaModel, baseRequest.model)
            assertNull(baseline.systemPrompt)
            assertEquals(4096, baseline.config.ollamaNumCtx, "окно «как сейчас» — умолчание демона")

            val optimized = assertNotNull(Tunings.byId("optimized"))
            client.requests.clear()
            runner.run(optimized, listOf(q01))

            val optimizedRequest = client.requests.single()
            assertEquals(listOf("system", "user"), optimizedRequest.messages.map { it.role })
            assertEquals(
                listOf(Tunings.KMP_RULES, q01.question),
                optimizedRequest.messages.map { it.content },
                "инструкция уходит системным сообщением, вопрос — репликой пользователя"
            )
            assertEquals(512, optimizedRequest.maxTokens, "бюджет оптимизированного профиля")
            assertEquals(8192, optimized.config.ollamaNumCtx, "окно оптимизированного профиля")
            assertEquals(0.0, optimized.config.ollamaTemperature)
            assertTrue(optimized.config.ollamaThink, "рассуждение профиль не трогает")
        }

    @Test
    fun `прогон из одних отказов даёт нули и не падает`() = runBlocking {
        val client = StubClient(
            replies = mapOf(
                q01.question to Reply(reason = TEST_FAILURE),
                q02.question to Reply(reason = TEST_FAILURE)
            )
        )

        val result = TuningRunner(client).run(baseline(), listOf(q01, q02))

        assertEquals(0.0, result.averageScore, "средняя по отказам — ноль, а не отсутствие числа")
        assertEquals(0.0, result.tokensPerSecond, "скорость при нулевом расходе — ноль, а не деление на ноль")
        assertEquals(2, result.failed)
        assertEquals(2, result.wrong)
        assertEquals(0, result.full + result.partial)
        assertEquals(0, result.completionTokens)
        assertEquals(listOf(0, 0), result.answers.map { it.score })
    }

    /** Вопрос набора по номеру: тест работает с настоящими вопросами, а не со своими копиями. */
    private fun question(id: String): ControlQuestion =
        assertNotNull(KmpQuestions.byId(id), "в наборе кейса нет вопроса $id")

    /** Профиль «как сейчас» — тот, с которым сравниваются все остальные. */
    private fun baseline(): Tuning = assertNotNull(Tunings.byId("baseline"))

    /**
     * Подставной клиент: отвечает тем, что ему положили, и запоминает запросы.
     *
     * Задержка перед ответом нужна, чтобы время прогона было измеримым: без неё среднее время
     * вопроса было бы нулём, и проверка «время считается часами» ничего не проверяла бы.
     * Ключ ответа — последнее сообщение запроса (вопрос кейса): так подставные ответы привязаны
     * к вопросам набора, а не к порядку вызовов.
     */
    private class StubClient(
        private val replies: Map<String, Reply>,
        private val delayMillis: Long = DELAY_MILLIS
    ) : LlmClient {

        /** Запросы как есть: по ним проверяется вид запроса каждого профиля. */
        val requests = mutableListOf<DeepSeekRequest>()

        override suspend fun complete(request: DeepSeekRequest): DeepSeekResponse {
            requests += request
            delay(delayMillis)
            val question = request.messages.last().content
            val reply = replies[question] ?: error("подставного ответа на вопрос «$question» нет")
            reply.reason?.let { throw LlmUnavailableException(it) }
            return DeepSeekResponse(
                choices = listOf(
                    DeepSeekResponse.Choice(
                        message = ChatMessage(role = "assistant", content = reply.text),
                        finishReason = "stop"
                    )
                ),
                usage = DeepSeekResponse.Usage(
                    promptTokens = reply.promptTokens,
                    completionTokens = reply.completionTokens,
                    totalTokens = reply.promptTokens + reply.completionTokens
                )
            )
        }
    }

    /** Ответ подставного клиента: текст, расход токенов или причина отказа. */
    private data class Reply(
        val text: String = "",
        val reason: String? = null,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0
    )

    private companion object {

        /** Задержка подставного ответа: время вопроса обязано быть измеримым. */
        const val DELAY_MILLIS = 30L

        /** Причина отказа, которую называет подставной клиент: она и должна попасть в отчёт. */
        const val TEST_FAILURE = "тестовый отказ демона"

        const val MILLIS_PER_SECOND = 1000.0
    }
}
