package com.osvin.aichallenge.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Отказ по частоте: ключ исчерпал окно запросов.
 *
 * Отдельный тип, а не флаг «не пустили»: клиенту нужны секунды до освобождения окна
 * (`Retry-After`), и их неоткуда взять, кроме как у того, кто считает окно. Маршрут
 * переводит тип в ответ 429, метрики — в счётчик отказов.
 *
 * @param retryAfterSeconds Сколько секунд до освобождения окна.
 * @param reason Причина словами для тела ошибки.
 */
class ServiceRateLimitedException(
    val retryAfterSeconds: Long,
    val reason: String
) : IllegalStateException(reason)

/**
 * Отказ по параллельности: место в очереди занято или ожидание вышло.
 *
 * Секунды до следующей попытки тоже нужны клиенту: без `Retry-After` клиент повторяет
 * запрос сразу и отбирает место у тех, кто уже ждёт, — очередь от повторов растёт.
 *
 * @param retryAfterSeconds Сколько секунд до разумной повторной попытки.
 * @param reason Причина словами для тела ошибки.
 */
class ServiceOverloadedException(
    val retryAfterSeconds: Long,
    val reason: String
) : IllegalStateException(reason)

/**
 * Ограничения сервиса: частота запросов на ключ и параллельность генераций.
 *
 * Две разные защиты в одном типе, потому что обе считают одно и то же — сколько работы
 * сервис уже взял. Частота (скользящее окно в шестьдесят секунд на ключ) защищает сервис
 * от одного назойливого клиента — и хранилище, и модель: окно проверяют все маршруты
 * поверхности, кроме проверки живости и чтения метрик. Семафор с ограниченной очередью
 * защищает машину от всех клиентов сразу: генераций не больше потолка, ждущих не больше
 * очереди, а ожидание ограничено сроком. Без очереди отказ приходил бы тем, кто пришёл
 * в неудачную секунду, хотя места освободились бы через доли секунды; без срока очередь
 * копила бы запросы
 * бесконечно, и клиент ждал бы ответа на вопрос, который уже неактуален.
 *
 * Счётчики занятых и ждущих читает [ServiceMetrics] — через свойства, а не через глобальное
 * состояние: маршруты и метрики должны видеть одни и те же числа, и передать их дальше
 * можно только оттуда, где они считаются.
 *
 * @param rateLimitPerMinute Потолок запросов в минуту на ключ.
 * @param maxConcurrent Потолок одновременных генераций.
 * @param queueLimit Сколько запросов ждут место в очереди, прежде чем получить отказ.
 * @param queueWaitMillis Сколько ждут место, прежде чем получить отказ.
 * @param clock Часы окна: параметр, а не [System.currentTimeMillis] внутри, чтобы проверка
 *        окна не зависела от реального времени.
 */
class ServiceLimits(
    private val rateLimitPerMinute: Int,
    private val maxConcurrent: Int,
    private val queueLimit: Int,
    private val queueWaitMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Окна частоты по ключам: ключ — тот, что предъявил клиент, уже проверенный доступом. */
    private val windows = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** Места генераций: семафор считает их и сам будит ждущих, когда место освободилось. */
    private val permits = Semaphore(permits = maxConcurrent)

    /** Сколько генераций идёт сейчас. */
    private val active = AtomicInteger()

    /** Сколько запросов ждёт место сейчас. */
    private val queued = AtomicInteger()

    /** Сколько генераций идёт сейчас: число для метрик. */
    val activeGenerations: Int get() = active.get()

    /** Сколько запросов ждёт место: число для метрик. */
    val queuedGenerations: Int get() = queued.get()

    /** Потолок одновременных генераций: число для метрик. */
    val concurrentLimit: Int get() = maxConcurrent

    /** Потолок очереди ожидания: число для метрик. */
    val waitingLimit: Int get() = queueLimit

    /** Потолок запросов в минуту на ключ: число для метрик. */
    val perMinuteLimit: Int get() = rateLimitPerMinute

    /**
     * Занимает место в окне частоты.
     *
     * @return `null` — запрос можно пускать; иначе сколько секунд до освобождения окна.
     *
     * Окно скользящее, а не «счётчик на минуту»: счётчик, сбрасываемый по календарной минуте,
     * пропускал бы двойную порцию на границе (десять запросов в 10:00:59 и десять в 10:01:00).
     * Держать отметки всех запросов можно, потому что старые выбрасываются: в окне их не
     * больше потолка, сколько бы запросов ни пришло.
     */
    fun tryAcquireRequest(key: String): Long? {
        val window = windows.computeIfAbsent(key) { ArrayDeque() }
        return synchronized(window) {
            val now = clock()
            while (window.isNotEmpty() && now - window.first() >= WINDOW_MILLIS) {
                window.removeFirst()
            }
            if (window.size >= rateLimitPerMinute) {
                secondsUntil(window.first() + WINDOW_MILLIS - now)
            } else {
                window.addLast(now)
                null
            }
        }
    }

    /**
     * Выполняет генерацию, соблюдая оба ограничения.
     *
     * Сначала частота: она дешевле и не тратит место в очереди на запрос, который всё равно
     * не должен дойти до модели. Затем место: `block` исполняется, только если место занято,
     * и до тех пор никто не считает запрос активной генерацией — иначе панель показывала бы
     * работу, которой ещё нет.
     *
     * @param key Ключ клиента: окно частоты считается по нему, а не по адресу, потому что
     *        за прокси адрес один на всех.
     * @param block Генерация: вызов модели и всё, что должно произойти под её местом.
     * @throws ServiceRateLimitedException ключ исчерпал окно.
     * @throws ServiceOverloadedException очередь заполнена или ожидание вышло.
     */
    suspend fun <T> withGeneration(key: String, block: suspend () -> T): T {
        tryAcquireRequest(key)?.let { seconds ->
            throw ServiceRateLimitedException(retryAfterSeconds = seconds, reason = rateLimitedReason())
        }
        acquirePermit()
        try {
            return block()
        } finally {
            active.decrementAndGet()
            permits.release()
        }
    }

    /**
     * Причина отказа по частоте словами.
     *
     * Одна формулировка на оба места проверки — генерацию и маршруты без генерации: клиент
     * разбирает отказ по коду, но человеку читать текст, и он не должен зависеть от того,
     * какой маршрут ответил.
     */
    fun rateLimitedReason(): String = "Превышен лимит запросов: $rateLimitPerMinute в минуту"

    /**
     * Занимает место генерации: сразу, если есть, иначе в очереди и с ограниченным сроком.
     *
     * Немедленная попытка стоит первой не ради экономии: запрос при свободном месте не должен
     * попадать под потолок очереди — иначе при очереди длиной ноль сервис отвергал бы всё,
     * хотя генераций нет вовсе. По той же причине счётчик очереди растёт только у тех, кто
     * действительно ждёт: он же уходит в метрики, и «длина очереди» с занявшими место
     * показывала бы ожидание там, где запрос уже отвечается.
     */
    private suspend fun acquirePermit() {
        if (permits.tryAcquire()) {
            active.incrementAndGet()
            return
        }
        val waiting = queued.incrementAndGet()
        try {
            if (waiting > queueLimit) {
                throw ServiceOverloadedException(
                    retryAfterSeconds = overloadedRetrySeconds(),
                    reason = overloadedReason()
                )
            }
            val waited = withTimeoutOrNull(queueWaitMillis) { permits.acquire() }
            if (waited == null) {
                throw ServiceOverloadedException(
                    retryAfterSeconds = overloadedRetrySeconds(),
                    reason = overloadedReason()
                )
            }
            active.incrementAndGet()
        } finally {
            queued.decrementAndGet()
        }
    }

    /** Секунды до разумной повторной попытки после отказа по параллельности. */
    private fun overloadedRetrySeconds(): Long = secondsUntil(queueWaitMillis)

    /** Причина отказа по параллельности: одна формулировка на оба случая. */
    private fun overloadedReason(): String =
        "Все генерации заняты ($maxConcurrent), очередь ждёт до $queueWaitMillis мс"

    private companion object {

        /** Длина окна частоты: минута, как в задании. */
        const val WINDOW_MILLIS = 60_000L

        /** Миллисекунд в секунде. */
        const val MILLIS_PER_SECOND = 1_000L

        /**
         * Сколько секунд осталось до момента, но не меньше одной.
         *
         * Меньше секунды — это ноль, а ноль в `Retry-After` клиент читает как «прямо сейчас»,
         * и повтор приходит в то же забитое окно.
         */
        fun secondsUntil(millis: Long): Long = ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND)
            .coerceAtLeast(1)
    }
}
