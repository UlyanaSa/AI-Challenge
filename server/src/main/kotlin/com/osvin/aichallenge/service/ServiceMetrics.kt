package com.osvin.aichallenge.service

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.math.ceil
import kotlin.time.TimeSource

/**
 * Метрики сервиса: счётчики, длительности генераций и срез ресурсов машины.
 *
 * Счётчики — [AtomicLong] и [LongAdder], а не `var` под замком: запросы идут из разных
 * потоков, и метрика не стоит того, чтобы ради неё выстраивать запросы в очередь.
 * Длительности копятся двумя способами: суммой (для среднего) и кольцевым буфером последних
 * замеров (для p95). Среднее по всей истории и процентиль по последним замерам вместе
 * показывают то, что нужно панели: «как в среднем» и «как сейчас на плохих запросах».
 * Хранить все замеры ради процентиля нельзя — сервис работает неделями, а буфер на пятьсот
 * значений даёт ту же картину за постоянную память.
 *
 * Занятость генераций сюда не копируется: её читают из [ServiceLimits] через провайдеры
 * (лямбды), потому что копия разошлась бы с настоящим числом ровно тогда, когда оно важнее
 * всего — в момент, когда очередь растёт. Хранилище опрашивается так же, в момент снимка.
 *
 * @param config Настройки сервиса: из них берутся потолки, которые метрика показывает рядом
 *        с текущими числами (иначе панель не знает, много это или мало).
 * @param activeGenerations Сколько генераций идёт сейчас.
 * @param queuedGenerations Сколько запросов ждёт место.
 * @param sessions Размеры хранилища: опрашиваются в момент снимка, а не кэшируются.
 */
class ServiceMetrics(
    private val config: ServiceConfig,
    private val activeGenerations: () -> Int,
    private val queuedGenerations: () -> Int,
    private val sessions: () -> StoreCounts
) {

    /** Отметка запуска сервиса: от неё считается время работы. */
    private val started = TimeSource.Monotonic.markNow()

    /** Сколько запросов к модели принято. */
    private val requestsTotal = AtomicLong()

    /** Сколько из них ответило успехом. */
    private val requestsSuccess = AtomicLong()

    /** Сколько ответило отказом. */
    private val requestsFailed = AtomicLong()

    /** Сколько запросов отвергнуто лимитом частоты. */
    private val rateRejections = AtomicLong()

    /** Сколько сводок прошлого чата построено. */
    private val summariesBuilt = AtomicLong()

    /** Сколько попыток построить сводку закончилось ничем. */
    private val summariesFailed = AtomicLong()

    /** Суммарные токены запросов. */
    private val tokensPrompt = LongAdder()

    /** Суммарные токены ответов. */
    private val tokensCompletion = LongAdder()

    /** Суммарные токены обоих видов. */
    private val tokensTotal = LongAdder()

    /** Сумма длительностей всех генераций. */
    private val latencyTotal = LongAdder()

    /** Сколько длительностей записано: делитель среднего. */
    private val latencySamples = AtomicLong()

    /** Последние длительности: кольцевой буфер, чтобы память не росла с числом запросов. */
    private val latencyWindow = LongArray(LATENCY_WINDOW)

    /** Замок буфера: двое запросов пишут в него одновременно, а [LongArray] не потокобезопасен. */
    private val latencyLock = Any()

    /** Куда ляжет следующий замер в буфере. */
    private var latencyCursor = 0

    /** Сколько значений буфера занято: до заполнения это меньше его длины. */
    private var latencyFilled = 0

    /** Сколько сервис работает. */
    fun uptimeMillis(): Long = started.elapsedNow().inWholeMilliseconds

    /** Запрос к модели принят: считается до ответа, потому что отказ тоже работа сервиса. */
    fun requestStarted() {
        requestsTotal.incrementAndGet()
    }

    /** Запрос к модели ответил успехом. */
    fun requestSucceeded() {
        requestsSuccess.incrementAndGet()
    }

    /** Запрос к модели ответил отказом. */
    fun requestFailed() {
        requestsFailed.incrementAndGet()
    }

    /**
     * Запрос убран из счётчиков: клиент закрыл вызов, не дождавшись ответа.
     *
     * Такой запрос не успех и не отказ сервиса — человек передумал. Записать его отказом
     * значило бы обвинять сервис в чужом уходе, а успехом — врать про несостоявшийся ответ.
     * Поэтому он снимается и со счёта принятых.
     */
    fun requestAbandoned() {
        requestsTotal.decrementAndGet()
    }

    /** Запрос отвергнут лимитом частоты. */
    fun recordRateRejection() {
        rateRejections.incrementAndGet()
    }

    /**
     * Сводка прошлого чата построена.
     *
     * Отдельно от [recordGeneration]: сводка — тоже вызов модели, но её считает своя группа,
     * потому что «сколько раз сворачивали прошлый чат» — это про разговоры, а не про
     * длительности генераций.
     */
    fun recordSummaryBuilt() {
        summariesBuilt.incrementAndGet()
    }

    /** Сводку построить не удалось: прошлый чат пуст или модель не ответила. */
    fun recordSummaryFailed() {
        summariesFailed.incrementAndGet()
    }

    /**
     * Записывает длительность генерации.
     *
     * Записывается и неудавшаяся генерация: таймаут — это тоже длительность ожидания,
     * и без него p95 показывал бы здоровье сервиса по одним удачным ответам.
     */
    fun recordGeneration(elapsedMillis: Long) {
        latencySamples.incrementAndGet()
        latencyTotal.add(elapsedMillis)
        synchronized(latencyLock) {
            latencyWindow[latencyCursor] = elapsedMillis
            latencyCursor = (latencyCursor + 1) % latencyWindow.size
            if (latencyFilled < latencyWindow.size) latencyFilled++
        }
    }

    /**
     * Записывает расход токенов из `usage` ответа.
     *
     * Вызывается только когда движок расход назвал: [null] в `usage` — это «движок молчит»,
     * и прибавить вместо него ноль значило бы показать на панели расход, которого никто
     * не измерял.
     */
    fun recordTokens(prompt: Int, completion: Int, total: Int) {
        tokensPrompt.add(prompt.toLong())
        tokensCompletion.add(completion.toLong())
        tokensTotal.add(total.toLong())
    }

    /** Снимок работы сервиса: числа для `/api/metrics`. */
    fun snapshot(): MetricsSnapshot = MetricsSnapshot(
        uptimeMs = uptimeMillis(),
        requests = RequestsMetrics(
            total = requestsTotal.get(),
            success = requestsSuccess.get(),
            failed = requestsFailed.get()
        ),
        latency = LatencyMetrics(
            averageMs = averageLatency(),
            p95Ms = percentile95(),
            samples = latencySamples.get()
        ),
        generations = GenerationsMetrics(
            active = activeGenerations(),
            queued = queuedGenerations(),
            maxConcurrent = config.maxConcurrentGenerations,
            queueLimit = config.queueLimit
        ),
        rateLimit = RateLimitMetrics(
            rejections = rateRejections.get(),
            limitPerMinute = config.rateLimitPerMinute
        ),
        tokens = TokenMetrics(
            prompt = tokensPrompt.sum(),
            completion = tokensCompletion.sum(),
            total = tokensTotal.sum()
        ),
        sessions = sessions().let { SessionMetrics(count = it.sessions, messages = it.messages) },
        summaries = SummaryMetrics(
            built = summariesBuilt.get(),
            failed = summariesFailed.get()
        ),
        resources = resources()
    )

    /** Средняя длительность генерации; замеров нет — ноль, а не деление на ноль. */
    private fun averageLatency(): Long {
        val samples = latencySamples.get()
        return if (samples == 0L) 0 else latencyTotal.sum() / samples
    }

    /**
     * Девяносто пятый процентиль длительностей по последним замерам.
     *
     * Ранг округляется вверх: при пятидесяти замерах p95 обязан быть замером, а не средним
     * между соседними, — панель показывает его как «худшее из обычного», и дробное значение
     * там читалось бы как измерение, которого не было.
     */
    private fun percentile95(): Long {
        val samples = synchronized(latencyLock) {
            if (latencyFilled == 0) return 0
            val size = latencyWindow.size
            LongArray(latencyFilled) { index ->
                latencyWindow[(latencyCursor - latencyFilled + index + size) % size]
            }
        }
        samples.sort()
        val rank = ceil(samples.size * P95).toInt().coerceIn(1, samples.size)
        return samples[rank - 1]
    }

    /**
     * Память и процессор.
     *
     * Куча берётся у [Runtime]: она есть на любой JVM. Память машины и загрузка процессора —
     * только у расширенного интерфейса `com.sun.management.OperatingSystemMXBean`, и его
     * наличие проверяется через `isInstance`, а не приведением: на другой реализации JVM
     * приведение уронило бы снимок метрик, то есть ровно тот запрос, который должен
     * показать, что сервис жив. Отрицательное значение загрузки означает «неизвестно»
     * (так отвечает интерфейс, когда счётчика нет) и остаётся пустым, а не минус единицей.
     */
    private fun resources(): ResourceMetrics {
        val runtime = Runtime.getRuntime()
        val bean = ManagementFactory.getOperatingSystemMXBean()
        val extended = if (EXTENDED_OS.isInstance(bean)) {
            bean as com.sun.management.OperatingSystemMXBean
        } else {
            null
        }
        return ResourceMetrics(
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            heapMaxBytes = runtime.maxMemory(),
            ramUsedBytes = extended.read { totalMemorySize - freeMemorySize },
            ramTotalBytes = extended.read { totalMemorySize },
            systemCpuLoad = extended.read { cpuLoad }?.takeIf { it >= 0 },
            processCpuLoad = extended.read { processCpuLoad }?.takeIf { it >= 0 },
            processors = bean.availableProcessors
        )
    }

    /** Читает счётчик расширенного интерфейса; недоступный счётчик — пустое значение, а не отказ. */
    private fun <T> com.sun.management.OperatingSystemMXBean?.read(
        value: com.sun.management.OperatingSystemMXBean.() -> T
    ): T? = this?.let { bean ->
        try {
            bean.value()
        } catch (unsupported: UnsupportedOperationException) {
            null
        } catch (failed: RuntimeException) {
            null
        }
    }

    private companion object {

        /** Сколько последних замеров держится для процентиля. */
        const val LATENCY_WINDOW = 500

        /** Доля распределения, которую отсекает p95. */
        const val P95 = 0.95

        /**
         * Реализация MXBean с памятью и загрузкой процессора: интерфейс, а не класс.
         *
         * Проверка идёт по `isInstance` затем, что расширенный интерфейс есть не у всякой
         * JVM: у реализации без него снимок метрик обязан остаться работоспособным, а не
         * упасть на приведении типа.
         */
        val EXTENDED_OS: Class<com.sun.management.OperatingSystemMXBean> =
            com.sun.management.OperatingSystemMXBean::class.java
    }
}
