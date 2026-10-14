package com.osvin.aichallenge.service

import com.osvin.aichallenge.agent.AgentLogger
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Проверка ключа доступа к `/api/…` и опознание запроса в логе.
 *
 * Ключ сравнивается постоянным по времени способом ([MessageDigest.isEqual]): обычное
 * `==` прекращает сравнение на первом различии, и по времени ответа ключ подбирается
 * посимвольно. Сравниваются все настроенные ключи без раннего выхода — иначе по времени
 * ответа видно, какой ключ угадан, а какой нет, и постоянное сравнение одного ключа
 * не спасало бы.
 *
 * Логируется только то, что не является чужим содержимым: идентификатор запроса, путь,
 * код ответа и длительность. Ключ не попадает в лог даже обрезанным: строка лога уходит
 * в общий вывод контейнера, а ключ — это доступ. Текст сообщения и текст ответа модели
 * не логируются тоже: это переписка человека, и по умолчанию она не остаётся на диске
 * сервера.
 *
 * @param keys Настроенные ключи: пусто — доступ по ключу не настроен вовсе, и поверхность
 *        отвечает отказом конфигурации (см. [configured]).
 * @param logger Лог доступа: одна строка на запрос.
 */
class ServiceAuth(
    private val keys: Set<String>,
    private val logger: AgentLogger = DEFAULT_LOG
) {

    /** Настроен ли доступ: без ключей `/api/…`, кроме проверки живости, не отвечает. */
    val configured: Boolean get() = keys.isNotEmpty()

    /**
     * Ключ, предъявленный запросом: заголовок `X-API-Key` или `Authorization: Bearer`.
     *
     * Два способа, потому что оба живут в клиентах: короткий заголовок удобен панели,
     * `Authorization` — тому, кто уже носит токен в стандартном месте. Схема `Bearer`
     * разбирается без учёта регистра: значение приходит от разных клиентов, и падать
     * на `bearer` значило бы отказывать в доступе из-за регистра.
     *
     * @return Ключ как его прислал клиент, или `null` — ключа в запросе нет.
     */
    fun presentedKey(call: ApplicationCall): String? {
        val direct = call.request.headers[HEADER]?.trim()?.takeIf { it.isNotEmpty() }
        if (direct != null) return direct
        val authorization = call.request.headers[HttpHeaders.Authorization]?.trim() ?: return null
        if (!authorization.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
        return authorization.substring(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Подходит ли предъявленный ключ хоть одному настроенному.
     *
     * Сравнение не прекращается на совпадении: цикл всегда проходит все ключи, иначе
     * по времени ответа читалось бы число ключей и место найденного в списке.
     */
    fun authorized(presented: String): Boolean {
        if (!configured) return false
        val candidate = presented.toByteArray(Charsets.UTF_8)
        var matched = false
        keys.forEach { key ->
            if (MessageDigest.isEqual(key.toByteArray(Charsets.UTF_8), candidate)) matched = true
        }
        return matched
    }

    /**
     * Идентификатор запроса: восемь шестнадцатеричных символов.
     *
     * Восемь — не коротко ради экономии, а потому что это единственное, по чему человек
     * находит строку лога: длинный идентификатор читают глазами, а такой набирают.
     * [SecureRandom], а не счётчик: по номеру запроса видно число запросов сервиса, и это
     * лишнее знание для чужого клиента.
     */
    fun requestId(): String {
        val chars = CharArray(REQUEST_ID_LENGTH) { HEX[random.nextInt(HEX.size)] }
        return String(chars)
    }

    /**
     * Строка доступа: идентификатор запроса, путь, код и длительность.
     *
     * Единственное место, где сервис пишет в лог: так легче проверить, что в лог не попало
     * ничего лишнего, чем вычитывать каждый маршрут. Ключ, вопрос и ответ в строку не
     * передаются даже параметрами — их здесь просто нет.
     */
    fun log(requestId: String, path: String, status: Int, elapsedMillis: Long) {
        logger.log("requestId=$requestId path=$path status=$status duration=${elapsedMillis}ms")
    }

    companion object {

        /**
         * Заголовок ключа доступа: тот же, что читает сервис.
         *
         * Публичный, потому что тем же именем сервис объявляет клиенту право на свой заголовок
         * в CORS ([com.osvin.aichallenge.Application.module]): браузерная страница живёт на
         * другом источнике, и без этого её предварительный запрос отвергается — «ошибка
         * авторизации» оказалась бы ошибкой CORS. Второго написания имени нет: строка одна.
         */
        const val HEADER = "X-API-Key"

        /** Схема стандартного заголовка; разбирается без учёта регистра. */
        private const val BEARER_PREFIX = "Bearer "

        /** Длина идентификатора запроса в символах. */
        private const val REQUEST_ID_LENGTH = 8

        /** Шестнадцатеричные цифры: по ним собирается идентификатор без обращений к формату строк. */
        private val HEX = "0123456789abcdef".toCharArray()

        /**
         * Источник случайности: один на сервис.
         *
         * [SecureRandom] потокобезопасен, поэтому общий экземпляр дешевле создания на каждый
         * запрос (создание подтягивает системный источник энтропии).
         */
        private val random = SecureRandom()

        /** Лог доступа сервиса по умолчанию: отдельная метка, чтобы строки сервиса не путались с агентом. */
        private val DEFAULT_LOG: AgentLogger = AgentLogger { println("[service] $it") }
    }
}
