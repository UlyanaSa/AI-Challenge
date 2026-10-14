package com.osvin.aichallenge.service

import com.osvin.aichallenge.agent.LlmApiException
import com.osvin.aichallenge.agent.LlmTimeoutException
import com.osvin.aichallenge.agent.LlmUnavailableException
import com.osvin.aichallenge.agent.OllamaProbe
import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DeepSeekRequest
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

/**
 * Поверхность `/api/…`: приватный сервис на локальной модели.
 *
 * Отдельная точка монтирования, а не маршруты внутри общего `routing`: у сервиса свои
 * правила — ключ доступа, лимиты, свой формат ошибки, — и они не должны размазываться
 * по маршрутам `/v1/…`, у которых правила другие. Запуск ([com.osvin.aichallenge.Application.module])
 * собирает [ServiceDeps] и зовёт эту функцию; сам сервис ничего о запуске не знает.
 *
 * Ошибки обрабатывает сам сервис, а не общий `StatusPages` приложения: тот обслуживает
 * `/v1/…` форматом `{success, error}`, и сервисный отказ в нём выглядел бы как ответ
 * чужой поверхности. Поэтому каждый маршрут переводит исключение в [ApiError] здесь же.
 *
 * @param deps Всё, чем пользуются маршруты: настройки, хранилище, метрики, лимиты, доступ,
 *        транспорт к модели.
 */
fun Route.serviceApi(deps: ServiceDeps) {
    // Мьютексы сессий живут ровно столько, сколько живут маршруты: это состояние сервиса,
    // а не приложения, и второй экземпляр поверхности (тесты) получает свои мьютексы.
    val sessions = SessionLocks()
    route("/api") {

        /**
         * Проверка живости — единственный маршрут без ключа.
         *
         * Клиент вводит адрес сервера до того, как человек ввёл ключ, и должен суметь
         * отличить «сервер не отвечает» от «ключ неверный»: без этого любая ошибка ввода
         * выглядела бы одинаково. Ключ здесь не спрашивается, и данные не отдаются:
         * маршрут называет только состояние сервиса и модели.
         */
        get("/health") {
            val requestId = deps.auth.requestId()
            val started = TimeSource.Monotonic.markNow()
            try {
                call.respondHealth(deps, requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unreachable: Throwable) {
                call.respondService(
                    HttpStatusCode.ServiceUnavailable,
                    ServiceHealth(
                        status = STATUS_ERROR,
                        ollama = OLLAMA_UNREACHABLE,
                        message = unreachable.message ?: HEALTH_FAILED
                    )
                )
            } finally {
                call.logAccess(deps, requestId, started)
            }
        }

        /**
         * Снимок метрик — под ключом, но без учёта в счётчиках и без лимита частоты.
         *
         * Панель опрашивает его чаще прочих маршрутов; если считать эти запросы, метрика
         * менялась бы от самого факта её чтения, и число запросов показывало бы частоту
         * опроса панели, а не работу сервиса. По той же причине маршрут не тратит место
         * в окне частоты: панель, показывающая состояние сервиса, не должна его исчерпывать.
         */
        get("/metrics") {
            call.serviceCall(deps, counted = false) { requestId ->
                if (call.requireServiceKey(deps, requestId) == null) return@serviceCall
                val snapshot = withContext(Dispatchers.IO) { deps.metrics.snapshot() }
                call.respondService(HttpStatusCode.OK, snapshot)
            }
        }

        /** Список моделей демона: источник — проверка Ollama, своего HTTP-запроса сервис не пишет. */
        get("/models") {
            call.serviceCall(deps) { requestId ->
                val key = call.requireServiceKey(deps, requestId) ?: return@serviceCall
                if (!call.requireRequestQuota(deps, key, requestId)) return@serviceCall
                call.respondModels(deps, requestId)
            }
        }

        /** Ответ модели: единственный маршрут, из-за которого сервис обращается к Ollama. */
        post("/chat") {
            call.serviceCall(deps) { requestId ->
                val key = call.requireServiceKey(deps, requestId) ?: return@serviceCall
                call.respondChat(deps, sessions, requestId, key)
            }
        }

        /**
         * Создание сессии: клиенту нужен идентификатор до первого вопроса.
         *
         * Место в окне частоты здесь не занимается: создание со сводкой генерацию вызывает,
         * а её занимает [ServiceLimits.withGeneration] — второй раз то же окно тратилось бы
         * дважды. Создание без сводки окно проверяет само, внутри обработчика.
         */
        post("/chats") {
            call.serviceCall(deps) { requestId ->
                val key = call.requireServiceKey(deps, requestId) ?: return@serviceCall
                call.createChat(deps, requestId, key)
            }
        }

        /** Список чатов: клиент восстанавливает боковую панель в режиме «Сервис». */
        get("/chats") {
            call.serviceCall(deps) { requestId ->
                val key = call.requireServiceKey(deps, requestId) ?: return@serviceCall
                if (!call.requireRequestQuota(deps, key, requestId)) return@serviceCall
                call.chatsList(deps, requestId)
            }
        }

        /** История сессии: клиент восстанавливает переписку после перезапуска. */
        get("/chats/{id}") {
            call.serviceCall(deps) { requestId ->
                val key = call.requireServiceKey(deps, requestId) ?: return@serviceCall
                if (!call.requireRequestQuota(deps, key, requestId)) return@serviceCall
                call.chatDetail(deps, requestId)
            }
        }

        /** Удаление сессии вместе с историей. */
        delete("/chats/{id}") {
            call.serviceCall(deps) { requestId ->
                val key = call.requireServiceKey(deps, requestId) ?: return@serviceCall
                if (!call.requireRequestQuota(deps, key, requestId)) return@serviceCall
                call.deleteChat(deps, requestId)
            }
        }
    }
}

/**
 * Общая обвязка маршрута сервиса: учёт запроса, отказ по доступу, перевод исключений и лог.
 *
 * Одна обвязка на все маршруты, потому что правила у них общие: необработанное исключение
 * обязано стать телом [ApiError], а не ответом чужого формата, и каждый запрос оставляет
 * ровно одну строку лога. Проверка доступа идёт здесь же, до тела маршрута: маршрут,
 * который забыл её позвать, оставил бы поверхность открытой.
 *
 * @param counted Считать ли запрос в метриках: проверка живости и чтение метрик из счёта
 *        исключены, иначе числа показывали бы работу самих проверок.
 * @param block Тело маршрута: получает идентификатор запроса для тела ошибки.
 */
private suspend fun ApplicationCall.serviceCall(
    deps: ServiceDeps,
    counted: Boolean = true,
    block: suspend (String) -> Unit
) {
    val requestId = deps.auth.requestId()
    val started = TimeSource.Monotonic.markNow()
    if (counted) deps.metrics.requestStarted()
    var abandoned = false
    try {
        block(requestId)
    } catch (cancelled: CancellationException) {
        // Отмена — не ответ сервиса: клиент ушёл, и записывать ему успех или отказ нечего.
        abandoned = true
        throw cancelled
    } catch (error: Throwable) {
        respondServiceError(requestId, serviceError(error))
    } finally {
        if (counted) {
            when {
                abandoned -> deps.metrics.requestAbandoned()
                response.status()?.isSuccess() == true -> deps.metrics.requestSucceeded()
                else -> deps.metrics.requestFailed()
            }
        }
        logAccess(deps, requestId, started)
    }
}

/**
 * Проверяет доступ и возвращает предъявленный ключ.
 *
 * Порядок проверок не переставляется: сначала «ключ вообще настроен», потом «ключ подходит».
 * Наоборот клиент без ключа получал бы 401 там, где сервис вообще не готов работать, —
 * и искал бы ошибку в ключе, а не в запуске.
 *
 * @return Ключ клиента — он же ключ окна частоты, или `null`: ответ отправлен, маршрут
 *         должен закончиться.
 */
private suspend fun ApplicationCall.requireServiceKey(deps: ServiceDeps, requestId: String): String? {
    if (!deps.auth.configured) {
        respondApiError(
            HttpStatusCode.ServiceUnavailable,
            ApiError(
                code = ApiError.SERVICE_NOT_CONFIGURED,
                message = "Ключ доступа не задан: укажите SERVICE_API_KEY или SERVICE_API_KEYS",
                requestId = requestId
            )
        )
        return null
    }
    val key = deps.auth.presentedKey(this)
    if (key == null || !deps.auth.authorized(key)) {
        respondApiError(
            HttpStatusCode.Unauthorized,
            ApiError(
                code = ApiError.UNAUTHORIZED,
                message = "Ключ доступа не предъявлен или не подходит",
                requestId = requestId
            )
        )
        return null
    }
    return key
}

/**
 * Занимает место в окне частоты для маршрутов, которые модель не вызывают.
 *
 * Генерация проверяет окно сама ([ServiceLimits.withGeneration]) — до того, как займёт место
 * в очереди, — и здесь второй раз не проверяется: один запрос иначе тратил бы два места
 * из десяти. Остальные маршруты поверхности ограничены тем же окном, потому что лимит
 * задания считается на клиента, а не на один маршрут: клиент, обходящий ограничение чтением
 * истории, тратит то же время сервиса, что и спрашивающий.
 *
 * @param key Ключ клиента: окно считается по нему, а не по адресу — за прокси адрес один на всех.
 * @return `false` — окно исчерпано, ответ отправлен; маршрут должен закончиться.
 */
private suspend fun ApplicationCall.requireRequestQuota(
    deps: ServiceDeps,
    key: String,
    requestId: String
): Boolean {
    val retryAfterSeconds = deps.limits.tryAcquireRequest(key) ?: return true
    deps.metrics.recordRateRejection()
    respondApiError(
        status = HttpStatusCode.TooManyRequests,
        error = ApiError(
            code = ApiError.RATE_LIMITED,
            message = deps.limits.rateLimitedReason(),
            requestId = requestId
        ),
        retryAfterSeconds = retryAfterSeconds
    )
    return false
}

/**
 * Ответ модели: сборка контекста, ожидание места, запись истории.
 *
 * Вся работа с сессией идёт под её мьютексом ([SessionLocks]) — от чтения истории до записи
 * ответа. Иначе два одновременных вопроса к одной беседе читали бы историю до того, как
 * сосед дописал свою пару, и отвечали на вопрос, не видя предыдущего; а запись истории
 * расставляла бы пары в порядке завершения генерации, а не в порядке вопросов.
 *
 * Под мьютексом же занимается место генерации: запрос, ждущий своей очереди по сессии,
 * не должен держать место в семафоре — иначе соседние беседы вставали бы в очередь
 * из-за беседы, которая ещё даже не начала генерировать.
 *
 * @param key Ключ клиента: окно частоты и метрики считаются по нему.
 */
private suspend fun ApplicationCall.respondChat(
    deps: ServiceDeps,
    sessions: SessionLocks,
    requestId: String,
    key: String
) {
    val raw = requestText()
    val request = decodeOrNull<ChatRequest>(raw)
        ?: return respondInvalidRequest(requestId, REQUEST_UNREADABLE)
    val chatId = request.chatId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return respondInvalidRequest(requestId, CHAT_ID_REQUIRED)
    val message = request.message?.trim()?.takeIf { it.isNotEmpty() }
        ?: return respondInvalidRequest(requestId, MESSAGE_REQUIRED)
    val session = withContext(Dispatchers.IO) { deps.store.find(chatId) }
        ?: return respondApiError(
            HttpStatusCode.NotFound,
            ApiError(ApiError.CHAT_NOT_FOUND, "Чат $chatId не найден", requestId)
        )
    sessions.withLock(chatId) {
        replyInSession(deps, requestId, key, chatId, message, request, session.summary)
    }
}

/**
 * Генерация внутри одной сессии: контекст, вызов модели, история.
 *
 * Порядок здесь — суть поведения: сначала считается, что уйдёт модели (и отказ, если
 * обязательная часть не влезает), потом занимается место и вызывается модель, и только
 * после успешного ответа пишется история. Запись после ответа нужна затем, что при отказе
 * или отмене в истории не должно остаться вопроса без ответа: следующая генерация приняла
 * бы его за часть беседы и отвечала бы на два вопроса сразу.
 *
 * @param summary Сводка прошлого диалога из сессии; `null` — запрос собирается ровно так,
 *        как до появления сводок. Сводка — часть системной инструкции, а не сообщение:
 *        она не попадает в историю и не показывается человеку как реплика.
 */
private suspend fun ApplicationCall.replyInSession(
    deps: ServiceDeps,
    requestId: String,
    key: String,
    chatId: String,
    message: String,
    request: ChatRequest,
    summary: String?
) {
    val config = deps.config
    val model = request.model?.trim()?.takeIf { it.isNotEmpty() } ?: deps.llm.config.ollamaModel
    val temperature = request.temperature
        ?.takeIf { it >= 0 }
        ?.coerceAtMost(ServiceConfig.TEMPERATURE_CEILING)
        ?: deps.llm.config.ollamaTemperature
    val maxTokens = (request.maxTokens?.takeIf { it > 0 } ?: config.maxGenerationTokens)
        .coerceAtMost(config.maxGenerationTokens)
    val contextWindow = (request.contextWindow?.takeIf { it > 0 } ?: config.maxContextTokens)
        .coerceAtMost(config.maxContextTokens)

    val history = withContext(Dispatchers.IO) { deps.store.messages(chatId) }
    val plan = ContextBudget.plan(
        history = history,
        current = message,
        system = systemInstruction(config.systemPrompt, summary),
        maxContextTokens = contextWindow,
        maxGenerationTokens = maxTokens
    )
    if (plan.outcome == ContextOutcome.OVERFLOW) {
        return respondApiError(
            HttpStatusCode.BadRequest,
            ApiError(
                code = ApiError.CONTEXT_OVERFLOW,
                message = "Обязательная часть запроса не влезает в окно $contextWindow токенов: " +
                    "сократите сообщение или поднимите SERVICE_MAX_CONTEXT_TOKENS",
                requestId = requestId
            )
        )
    }

    val factory = deps.llm.withOverrides(model = model, temperature = temperature, numCtx = contextWindow)
    val client = try {
        factory.create(null)
    } catch (unconfigured: IllegalStateException) {
        // Ключа облачного провайдера у сервиса нет и быть не должно: сервис — поверхность
        // локальной модели, и запуск с `LLM_PROVIDER=cloud` означает, что он не настроен.
        return respondApiError(
            HttpStatusCode.ServiceUnavailable,
            ApiError(
                code = ApiError.SERVICE_NOT_CONFIGURED,
                message = unconfigured.message ?: "Сервис настроен не на локальную модель",
                requestId = requestId
            )
        )
    }

    val requestToModel = DeepSeekRequest(
        model = model,
        messages = plan.messages,
        maxTokens = maxTokens,
        temperature = temperature
    )
    var elapsedMs = 0L
    val response = try {
        deps.limits.withGeneration(key) {
            val started = TimeSource.Monotonic.markNow()
            try {
                withContext(Dispatchers.IO) { client.complete(requestToModel) }
            } finally {
                elapsedMs = started.elapsedNow().inWholeMilliseconds
                deps.metrics.recordGeneration(elapsedMs)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        if (error is ServiceRateLimitedException) deps.metrics.recordRateRejection()
        return respondServiceError(requestId, serviceError(error))
    }

    val reply = response.choices.firstOrNull()?.message?.content
        ?: return respondApiError(
            HttpStatusCode.ServiceUnavailable,
            ApiError(
                code = ApiError.OLLAMA_UNAVAILABLE,
                message = "Модель вернула ответ без сообщения",
                requestId = requestId
            )
        )
    // Пустой текст — отказ, а не ответ: у думающей модели бюджет мог уйти в рассуждения,
    // и `status: ok` с пустой строкой клиент покажет как ответ модели, то есть как
    // утверждение, которого не было. В историю при этом не пишется ничего — ни вопроса,
    // ни пустого ответа: пара «вопрос — пустота» осталась бы в беседе настоящим ходом,
    // и следующий запрос принял бы её за сказанное моделью.
    if (reply.isBlank()) {
        return respondApiError(
            HttpStatusCode.BadGateway,
            ApiError(
                code = ApiError.EMPTY_REPLY,
                message = "Модель не вернула текст ответа: бюджет $maxTokens токенов ушёл " +
                    "в рассуждения — увеличьте maxTokens или выключите рассуждение (OLLAMA_THINK=false)",
                requestId = requestId
            )
        )
    }

    withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        deps.store.append(chatId, ROLE_USER, message, now)
        deps.store.append(chatId, ROLE_ASSISTANT, reply, now)
    }
    response.usage?.let { usage ->
        deps.metrics.recordTokens(usage.promptTokens, usage.completionTokens, usage.totalTokens)
    }

    respondService(
        HttpStatusCode.OK,
        ChatReply(
            requestId = requestId,
            chatId = chatId,
            reply = reply,
            model = model,
            elapsedMs = elapsedMs,
            promptTokens = response.usage?.promptTokens,
            completionTokens = response.usage?.completionTokens,
            totalTokens = response.usage?.totalTokens,
            trimmedMessages = plan.dropped,
            status = STATUS_OK
        )
    )
}

/**
 * Создание сессии со сводкой прошлого диалога.
 *
 * Прошлый чат ищется до того, как новый заведён, и по двум причинам. Первая: сводка строится
 * из его сообщений, а сворачивать нечего — это не отказ, а норма для первого чата сервиса.
 * Вторая: отказ по частоте или перегрузке обязан прийти раньше записи сессии — иначе клиент
 * получил бы `429` и пустой чат в списке, который он не сможет ни открыть, ни связать
 * со своим запросом. По той же причине исключать при поиске нечего: новой сессии ещё нет.
 *
 * Итог сводки не влияет на создание чата: модель может не ответить, а разговор человек
 * всё равно начинает — причина отказа уходит клиенту словами, а не отказом запроса.
 *
 * @param key Ключ клиента: по нему считаются окно частоты и место генерации.
 */
private suspend fun ApplicationCall.createChat(deps: ServiceDeps, requestId: String, key: String) {
    val raw = requestText()
    val request = if (raw.isBlank()) null else decodeOrNull<CreateChatRequest>(raw)
    if (raw.isNotBlank() && request == null) {
        return respondInvalidRequest(requestId, REQUEST_UNREADABLE)
    }

    val config = deps.config
    // Выключенная настройка сильнее запроса: сводку не строим никогда. `summarize=false`
    // сильнее включённой настройки — человек попросил не сворачивать прошлый чат.
    val summarize = config.summaryEnabled && request?.summarize != false

    val previous = if (!summarize) {
        null
    } else {
        val named = request?.previousChatId?.trim()?.takeIf { it.isNotEmpty() }
        if (named == null) {
            withContext(Dispatchers.IO) { deps.store.latestWithMessages(NO_PREVIOUS_CHAT_YET) }
        } else {
            withContext(Dispatchers.IO) { deps.store.find(named) }
                ?: return respondApiError(
                    HttpStatusCode.NotFound,
                    ApiError(ApiError.CHAT_NOT_FOUND, "Чат $named не найден", requestId)
                )
        }
    }
    val source = previous
        ?.let { withContext(Dispatchers.IO) { deps.store.messages(it.id) } }
        .orEmpty()

    // Модель зовётся только когда есть что сворачивать, и зовётся под местом генерации:
    // сводка — такая же работа модели, как ответ на вопрос, и лимиты у неё те же.
    // Пустой прошлый чат места не занимает: причины не построить сводку тут хватает
    // без вызова, о котором нечего спрашивать. Тогда окно частоты проверяется здесь —
    // ровно один раз на запрос, чем бы создание чата ни закончилось.
    val summarized = when {
        previous == null -> {
            if (!requireRequestQuota(deps, key, requestId)) return
            Summarized(summary = null, error = null)
        }

        source.isEmpty() -> {
            if (!requireRequestQuota(deps, key, requestId)) return
            deps.metrics.recordSummaryFailed()
            Summarized(summary = null, error = SUMMARY_NO_MESSAGES)
        }

        else -> deps.limits.withGeneration(key) { buildSummary(deps, config, source) }
    }

    val session = withContext(Dispatchers.IO) {
        deps.store.create(request?.title, System.currentTimeMillis())
    }
    if (previous != null) {
        val messages = if (summarized.summary != null) source.size else 0
        withContext(Dispatchers.IO) {
            deps.store.setSummary(
                chatId = session.id,
                previousChatId = previous.id,
                summary = summarized.summary,
                messages = messages
            )
        }
    }
    respondService(
        HttpStatusCode.Created,
        CreatedChat(
            chatId = session.id,
            title = session.title,
            createdAt = session.createdAt,
            previousChatId = previous?.id,
            summary = summarized.summary,
            summaryMessages = if (summarized.summary != null) source.size else 0,
            summaryError = summarized.error
        )
    )
}

/**
 * Список чатов: свежие первыми, с числом сообщений и сводкой.
 *
 * `limit` из запроса — просьба, а не требование: больше потолка он обрезается, неположительный
 * или нечитаемый означает «не назван» и даёт потолок. Отвергать значение значило бы заставлять
 * клиента знать настройку, которую он и так не может поднять, — а панель показывала бы отказ
 * вместо списка.
 */
private suspend fun ApplicationCall.chatsList(deps: ServiceDeps, requestId: String) {
    val ceiling = deps.config.chatsListLimit
    val requested = parameters["limit"]?.trim()?.toIntOrNull()
    val limit = requested?.takeIf { it > 0 }?.coerceAtMost(ceiling) ?: ceiling

    val sessions = withContext(Dispatchers.IO) { deps.store.list(limit) }
    respondService(
        HttpStatusCode.OK,
        ChatsResponse(
            chats = sessions.map {
                ChatSummary(
                    chatId = it.id,
                    title = it.title,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    messages = it.messageCount,
                    summary = it.summary
                )
            },
            count = sessions.size
        )
    )
}

/**
 * Сворачивает прошлый диалог в сводку одним вызовом модели.
 *
 * Отказ модели здесь превращается в причину, а не летит дальше: чат всё равно будет создан,
 * и недоступная модель — повод начать разговор без сводки, а не ответ `5xx` на создание.
 * Температура нулевая, потому что сводка — не творчество: у одного и того же диалога она
 * должна быть одна, иначе два клиента получат разные факты про одно и то же.
 *
 * @param source Сообщения прошлого чата в порядке записи; последние
 *        [ServiceConfig.summaryMaxMessages] уходят модели.
 */
private suspend fun buildSummary(
    deps: ServiceDeps,
    config: ServiceConfig,
    source: List<StoredMessage>
): Summarized {
    val model = deps.llm.config.ollamaModel
    val request = DeepSeekRequest(
        model = model,
        messages = listOf(
            ChatMessage(role = ContextBudget.SYSTEM_ROLE, content = config.summaryPrompt),
            ChatMessage(
                role = ROLE_USER,
                content = source.takeLast(config.summaryMaxMessages)
                    .joinToString(separator = "\n") { "${it.role}$SUMMARY_ROLE_SEPARATOR${it.content}" }
            )
        ),
        maxTokens = config.summaryMaxTokens,
        temperature = SUMMARY_TEMPERATURE
    )
    return try {
        val client = deps.llm
            .withOverrides(model = model, temperature = SUMMARY_TEMPERATURE, numCtx = config.maxContextTokens)
            .create(null)
        val text = withContext(Dispatchers.IO) { client.complete(request) }
            .choices
            .firstOrNull()
            ?.message
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (text == null) {
            deps.metrics.recordSummaryFailed()
            Summarized(summary = null, error = SUMMARY_EMPTY_REPLY)
        } else {
            deps.metrics.recordSummaryBuilt()
            Summarized(summary = text, error = null)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Throwable) {
        deps.metrics.recordSummaryFailed()
        val reason = failed.message?.takeIf { it.isNotBlank() } ?: SUMMARY_UNKNOWN_REASON
        Summarized(summary = null, error = "$SUMMARY_UNAVAILABLE ($reason)")
    }
}

/**
 * Системная инструкция запроса: инструкция сервиса и, если она есть, сводка прошлого диалога.
 *
 * Порядок и формулировка — часть договора между сервисом и моделью: инструкция сервиса
 * остаётся первой, сводка идёт отдельным блоком, отделённым пустой строкой, и просьба
 * «не пересказывай» стоит рядом с текстом, к которому относится. Сводки нет — возвращается
 * ровно исходная инструкция: иначе запрос дня 30 перестал бы совпадать с прежним.
 *
 * @param systemPrompt Обязательная инструкция сервиса.
 * @param summary Сводка прошлого диалога; пустая строка — это «сводки нет».
 */
private fun systemInstruction(systemPrompt: String, summary: String?): String =
    summary?.takeIf { it.isNotBlank() }?.let { "$systemPrompt\n\n$SUMMARY_CONTEXT_HEADER\n$it" }
        ?: systemPrompt

/**
 * Итог сворачивания прошлого диалога.
 *
 * @param summary Текст сводки; `null` — сводки нет.
 * @param error Причина, по которой сводки нет; `null` — либо она построена, либо сворачивать
 *        было нечего (отсутствие прошлого чата — не отказ).
 */
private data class Summarized(val summary: String?, val error: String?)

/** История сессии: сообщения по возрастанию времени, как они записаны. */
private suspend fun ApplicationCall.chatDetail(deps: ServiceDeps, requestId: String) {
    val chatId = chatIdParameter() ?: return respondInvalidRequest(requestId, CHAT_ID_REQUIRED)
    val session = withContext(Dispatchers.IO) { deps.store.find(chatId) }
        ?: return respondApiError(
            HttpStatusCode.NotFound,
            ApiError(ApiError.CHAT_NOT_FOUND, "Чат $chatId не найден", requestId)
        )
    val messages = withContext(Dispatchers.IO) { deps.store.messages(chatId) }
    respondService(
        HttpStatusCode.OK,
        ChatDetail(
            chatId = session.id,
            title = session.title,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            messages = messages.map { StoredMessageDto(it.role, it.content, it.createdAt) },
            previousChatId = session.previousChatId,
            summary = session.summary,
            summaryMessages = session.summaryMessages
        )
    )
}

/** Удаление сессии: `-1` от хранилища означает «такой сессии не было». */
private suspend fun ApplicationCall.deleteChat(deps: ServiceDeps, requestId: String) {
    val chatId = chatIdParameter() ?: return respondInvalidRequest(requestId, CHAT_ID_REQUIRED)
    val removed = withContext(Dispatchers.IO) { deps.store.delete(chatId) }
    if (removed < 0) {
        return respondApiError(
            HttpStatusCode.NotFound,
            ApiError(ApiError.CHAT_NOT_FOUND, "Чат $chatId не найден", requestId)
        )
    }
    respondService(
        HttpStatusCode.OK,
        DeletedChat(chatId = chatId, deleted = true, messages = removed)
    )
}

/**
 * Список моделей демона.
 *
 * Ответ собирается из итога проверки Ollama ([com.osvin.aichallenge.agent.OllamaProbe]):
 * «демон не ответил» — это отказ сервиса, а не пустой список, иначе клиент показал бы
 * «моделей нет» там, где надо запускать демон.
 */
private suspend fun ApplicationCall.respondModels(deps: ServiceDeps, requestId: String) {
    when (val probe = deps.llm.probeOllama()) {
        is OllamaProbe.Loaded -> respondService(
            HttpStatusCode.OK,
            ModelsResponse(probe.models.map { ModelInfo(name = it) })
        )

        is OllamaProbe.Failed -> respondApiError(
            HttpStatusCode.ServiceUnavailable,
            ApiError(ApiError.OLLAMA_UNAVAILABLE, probe.reason, requestId)
        )
    }
}

/**
 * Проверка живости: сервис, демон и модель — три разных состояния.
 *
 * «Демон не ответил» и «модель не загружена» называются разными строками, потому что
 * требуют разных действий человека (`ollama serve` и `ollama pull`); ответ `ok` отдаётся
 * только когда модель действительно в списке демона, а не когда демон вообще ответил.
 */
private suspend fun ApplicationCall.respondHealth(deps: ServiceDeps, requestId: String) {
    val model = deps.llm.config.ollamaModel
    when (val probe = deps.llm.probeOllama()) {
        is OllamaProbe.Failed -> respondService(
            HttpStatusCode.ServiceUnavailable,
            ServiceHealth(status = STATUS_ERROR, ollama = OLLAMA_UNREACHABLE, message = probe.reason)
        )

        is OllamaProbe.Loaded -> {
            val loaded = probe.models.any { it == model || it.startsWith("$model:") }
            if (loaded) {
                respondService(
                    HttpStatusCode.OK,
                    ServiceHealth(
                        status = STATUS_OK,
                        provider = deps.llm.provider.wire,
                        model = model,
                        ollama = STATUS_OK,
                        modelLoaded = true,
                        uptimeMs = deps.metrics.uptimeMillis()
                    )
                )
            } else {
                respondService(
                    HttpStatusCode.ServiceUnavailable,
                    ServiceHealth(
                        status = STATUS_ERROR,
                        ollama = STATUS_OK,
                        modelLoaded = false,
                        message = "Модель $model не загружена: выполните `ollama pull $model`"
                    )
                )
            }
        }
    }
}

/** Тело запроса текстом: нечитаемое тело — пустое, и разберётся как отсутствие полей. */
private suspend fun ApplicationCall.requestText(): String = try {
    receiveText()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (unreadable: Exception) {
    ""
}

/** Разбор тела: `null` — тело не разобрано, и маршрут отвечает отказом запроса. */
private inline fun <reified T : Any> decodeOrNull(text: String): T? = try {
    serviceJson.decodeFromString<T>(text)
} catch (malformed: SerializationException) {
    null
}

/** Идентификатор сессии из пути; пустой тоже отказ, а не поиск сессии с пустым именем. */
private fun ApplicationCall.chatIdParameter(): String? =
    parameters["id"]?.trim()?.takeIf { it.isNotEmpty() }

/** Отказ «тело запроса не разобрано». */
private suspend fun ApplicationCall.respondInvalidRequest(requestId: String, message: String) {
    respondApiError(
        HttpStatusCode.BadRequest,
        ApiError(code = ApiError.INVALID_REQUEST, message = message, requestId = requestId)
    )
}

/** Отказ в форме сервиса: маршрут не разбирается, какому исключению какой код отвечает. */
private suspend fun ApplicationCall.respondServiceError(requestId: String, error: ServiceError) {
    respondApiError(
        status = error.status,
        error = ApiError(code = error.code, message = error.message, requestId = requestId),
        retryAfterSeconds = error.retryAfterSeconds
    )
}

/**
 * Исключение транспорта в отказ сервиса.
 *
 * Разделение по кодам нужно клиенту: 503 `ollama_unavailable` просит поднять демон,
 * 503 `model_unavailable` — загрузить модель, 504 `timeout` — повторить, а 429 и 503
 * `overloaded` несут секунды до повторной попытки. Сложить их в один «сбой сервиса»
 * значило бы отнять у человека подсказку, что именно делать.
 */
private fun serviceError(error: Throwable): ServiceError = when {
    error is ServiceRateLimitedException -> ServiceError(
        status = HttpStatusCode.TooManyRequests,
        code = ApiError.RATE_LIMITED,
        message = error.reason,
        retryAfterSeconds = error.retryAfterSeconds
    )

    error is ServiceOverloadedException -> ServiceError(
        status = HttpStatusCode.ServiceUnavailable,
        code = ApiError.OVERLOADED,
        message = error.reason,
        retryAfterSeconds = error.retryAfterSeconds
    )

    error is LlmTimeoutException -> ServiceError(
        status = HttpStatusCode.GatewayTimeout,
        code = ApiError.TIMEOUT,
        message = error.message ?: TRANSPORT_FAILED
    )

    error is LlmUnavailableException -> ServiceError(
        status = HttpStatusCode.ServiceUnavailable,
        code = ApiError.OLLAMA_UNAVAILABLE,
        message = error.message ?: TRANSPORT_FAILED
    )

    error is LlmApiException && error.namesMissingModel() -> ServiceError(
        status = HttpStatusCode.ServiceUnavailable,
        code = ApiError.MODEL_UNAVAILABLE,
        message = error.message ?: TRANSPORT_FAILED
    )

    error is LlmApiException -> ServiceError(
        status = HttpStatusCode.ServiceUnavailable,
        code = ApiError.OLLAMA_UNAVAILABLE,
        message = error.message ?: TRANSPORT_FAILED
    )

    else -> ServiceError(
        status = HttpStatusCode.InternalServerError,
        code = ApiError.INTERNAL_ERROR,
        message = error.message ?: INTERNAL_FAILED
    )
}

/**
 * Отказ движка про отсутствующую модель.
 *
 * Два признака, потому что их два у демона: `404` на запрос к незагруженной модели и текст
 * `model not found` в теле. Ждать только статуса нельзя — сообщение приходит и с успешным
 * статусом, когда демон называет причину в теле.
 */
private fun LlmApiException.namesMissingModel(): Boolean =
    status == HttpStatusCode.NotFound.value ||
        message?.contains(MODEL_NOT_FOUND, ignoreCase = true) == true

/** Строка лога доступа: идентификатор, путь, код и длительность. */
private fun ApplicationCall.logAccess(deps: ServiceDeps, requestId: String, started: TimeSource.Monotonic.ValueTimeMark) {
    deps.auth.log(
        requestId = requestId,
        path = request.path(),
        status = response.status()?.value ?: HTTP_SERVER_ERROR,
        elapsedMillis = started.elapsedNow().inWholeMilliseconds
    )
}

/**
 * Отказ сервиса до отправки: код, статус и, для отказов среды, секунды до повтора.
 *
 * @param status Код ответа.
 * @param code Машиночитаемый код тела.
 * @param message Причина словами.
 * @param retryAfterSeconds Секунды до повторной попытки; `null` — повторять бессмысленно.
 */
private data class ServiceError(
    val status: HttpStatusCode,
    val code: String,
    val message: String,
    val retryAfterSeconds: Long? = null
)

/**
 * Мьютексы сессий: свой на каждую беседу, а не один на сервис.
 *
 * Один мьютекс на сервис запретил бы параллельные беседы, а карта «сессия → мьютекс»
 * без уборки росла бы вместе с числом чатов и никогда не уменьшалась. Снимать запись
 * сразу после освобождения тоже нельзя: два запроса к одной беседе получили бы два разных
 * мьютекса и пошли бы одновременно — ровно то, от чего мьютекс и нужен. Поэтому у записи
 * есть счётчик ожидающих, и она исчезает только при нуле, под тем же замком, под которым
 * счётчик увеличивается.
 */
private class SessionLocks {

    /** Запись карты: мьютекс сессии и число запросов, которые им пользуются или ждут его. */
    private class Entry {
        val mutex = Mutex()
        var waiters = 0
    }

    /** Записи по идентификаторам сессий. */
    private val entries = HashMap<String, Entry>()

    /** Замок карты: сама карта и счётчики ожидающих, а не мьютексы сессий. */
    private val guard = Any()

    /**
     * Выполняет работу под мьютексом сессии.
     *
     * @param chatId Сессия, порядок в которой надо сохранить.
     * @param block Работа: чтение истории, генерация, запись пары.
     */
    suspend fun <T> withLock(chatId: String, block: suspend () -> T): T {
        val entry = synchronized(guard) {
            val existing = entries.getOrPut(chatId) { Entry() }
            existing.waiters++
            existing
        }
        try {
            entry.mutex.lock()
            try {
                return block()
            } finally {
                entry.mutex.unlock()
            }
        } finally {
            synchronized(guard) {
                entry.waiters--
                if (entry.waiters == 0) entries.remove(chatId, entry)
            }
        }
    }
}

/** Признак успешного ответа в теле. */
private const val STATUS_OK = "ok"

/** Признак отказа в теле проверки живости. */
private const val STATUS_ERROR = "error"

/** Состояние «демон не ответил». */
private const val OLLAMA_UNREACHABLE = "unreachable"

/** Роль человека в истории: тем же словом её называют и транспорт, и хранилище. */
private const val ROLE_USER = "user"

/** Роль модели в истории. */
private const val ROLE_ASSISTANT = "assistant"

/** Отказ: тело запроса не разобрано. */
private const val REQUEST_UNREADABLE = "Тело запроса не разобрано: ожидается объект JSON"

/** Отказ: не названа сессия. */
private const val CHAT_ID_REQUIRED = "Не назван чат: поле chatId обязательно"

/** Отказ: пустое сообщение. */
private const val MESSAGE_REQUIRED = "Пустое сообщение: поле message обязательно"

/** Текст движка про незагруженную модель: второй признак рядом со статусом 404. */
private const val MODEL_NOT_FOUND = "model not found"

/**
 * Температура сводки: ноль, потому что сводка — не творчество.
 *
 * У одного диалога должна быть одна сводка: при ненулевой температуре два клиента получили бы
 * разные факты про одно и то же, а следующий разговор опирался бы на случайную из версий.
 */
private const val SUMMARY_TEMPERATURE = 0.0

/** Разделитель роли и текста в расшифровке диалога: `user: текст` / `assistant: текст`. */
private const val SUMMARY_ROLE_SEPARATOR = ": "

/** Начало блока сводки в системной инструкции: формулировка из контракта. */
private const val SUMMARY_CONTEXT_HEADER =
    "Контекст прошлого диалога (сводка, не пересказывай её человеку):"

/** Причина отказа сводки: прошлый чат выбран, а сообщений в нём нет. */
private const val SUMMARY_NO_MESSAGES = "Сводка не построена: в прошлом чате нет сообщений"

/** Причина отказа сводки: модель ответила пустым текстом. */
private const val SUMMARY_EMPTY_REPLY = "Сводка не построена: модель вернула пустой ответ"

/** Причина отказа сводки: вызов модели не состоялся. */
private const val SUMMARY_UNAVAILABLE = "Сводка не построена: модель недоступна"

/** Хвост причины отказа, когда исключение её не назвало. */
private const val SUMMARY_UNKNOWN_REASON = "причина не названа"

/**
 * Исключать при поиске прошлого чата нечего: новая сессия ещё не заведена, потому что её
 * идентификатор выдаёт хранилище при создании. Пустая строка никого не исключает —
 * настоящий идентификатор пустым не бывает.
 */
private const val NO_PREVIOUS_CHAT_YET = ""

/** Причина отказа транспорта, когда движок её не назвал. */
private const val TRANSPORT_FAILED = "Модель недоступна"

/** Причина неожиданного сбоя, когда исключение её не назвало. */
private const val INTERNAL_FAILED = "Неожиданный сбой сервиса"

/** Причина отказа проверки живости, когда исключение её не назвало. */
private const val HEALTH_FAILED = "Сервис не смог проверить состояние модели"

/** Код ответа в логе, когда ответа не было: запрос оборвался до него. */
private const val HTTP_SERVER_ERROR = 500
