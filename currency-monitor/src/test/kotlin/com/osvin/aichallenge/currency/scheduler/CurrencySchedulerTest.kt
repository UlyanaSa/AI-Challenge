package com.osvin.aichallenge.currency.scheduler

import com.osvin.aichallenge.currency.Currency
import com.osvin.aichallenge.currency.CurrencyRate
import com.osvin.aichallenge.currency.CurrencyRateException
import com.osvin.aichallenge.currency.CurrencyRateProvider
import com.osvin.aichallenge.currency.CurrencyService
import com.osvin.aichallenge.currency.FakeCurrencyHourlySummaryRepository
import com.osvin.aichallenge.currency.FakeCurrencyRateProvider
import com.osvin.aichallenge.currency.FakeCurrencyRateRepository
import com.osvin.aichallenge.currency.summary.CurrencyHourlySummaryService
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Расписание сбора: обновление сразу, повтор по интервалу, живучесть при отказе и остановка
 * отменой.
 *
 * Проверки идут на настоящем времени и коротком интервале, а не на подставных часах: подмены
 * диспетчера (`kotlinx-coroutines-test`) в модуле нет, а собирать её ради трёх проверок значило
 * бы тянуть зависимость, которой пользуется только этот файл. Цена — окно в четверть секунды:
 * при интервале 30 мс оно даёт около девяти обновлений, и «не меньше двух» выполняется
 * с запасом. Конкретных дат проверки не касаются: поддельный поставщик отдаёт курсы с одним
 * и тем же моментом получения, а планировщику важна последовательность, а не календарь.
 *
 * Здесь проверяется поведение самого цикла; получение, проверка и запись курсов — дело
 * [CurrencyService] и его проверок. Поэтому поставщик и хранилище поддельные: единственное,
 * что этот файл хочет видеть, — сколько раз спросили и что дошло до истории.
 */
class CurrencySchedulerTest {

    @Test
    fun `первое обновление сразу, дальше по интервалу`() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        val provider = FakeCurrencyRateProvider()
        val summaries = FakeCurrencyHourlySummaryRepository()
        val scheduler = CurrencyScheduler(
            CurrencyService(provider, repository),
            summaryService(repository, summaries),
            INTERVAL
        )

        val job = scheduler.start(this)
        delay(WINDOW_MS)
        job.cancelAndJoin()

        assertTrue(
            provider.calls >= 2,
            "за $WINDOW_MS мс при интервале $INTERVAL_MS мс обновлений должно быть не меньше " +
                "двух, а поставщика спросили ${provider.calls} раз"
        )
        // Каждое обращение к поставщику — это законченное обновление: отказ сервис возвращает
        // значением, а не молчанием, поэтому число записей в истории обязано ему соответствовать.
        assertEquals(
            provider.calls,
            repository.saved.size,
            "каждое обновление должно дойти до истории"
        )
        assertEquals(
            provider.calls * Currency.entries.size,
            repository.history.size,
            "каждое обновление должно записать по строке на валюту"
        )
        // Сводка часа — производное от той же истории, и закрывает её тот же цикл: не появись она,
        // суточное изменение в приложении считать было бы не по чему.
        assertEquals(
            Currency.entries.size,
            summaries.stored.size,
            "после состоявшихся обновлений прошедший час должен быть закрыт сводками"
        )
    }

    @Test
    fun `отказ поставщика не останавливает сбор`() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        val provider = FailsOnceProvider()
        val summaries = FakeCurrencyHourlySummaryRepository()
        val scheduler = CurrencyScheduler(
            CurrencyService(provider, repository),
            summaryService(repository, summaries),
            INTERVAL
        )

        val job = scheduler.start(this)
        delay(WINDOW_MS)
        job.cancelAndJoin()

        assertTrue(
            provider.calls > 2,
            "после отказа цикл должен продолжиться и спросить поставщика ещё раз, " +
                "а обращений было ${provider.calls}"
        )
        assertTrue(
            repository.history.isNotEmpty(),
            "следующее после отказа обновление должно было записать курсы в историю"
        )
    }

    @Test
    fun `отмена останавливает сбор`() = runBlocking {
        val repository = FakeCurrencyRateRepository()
        val provider = FakeCurrencyRateProvider()
        val summaries = FakeCurrencyHourlySummaryRepository()
        val scheduler = CurrencyScheduler(
            CurrencyService(provider, repository),
            summaryService(repository, summaries),
            INTERVAL
        )

        val job = scheduler.start(this)
        delay(WINDOW_MS)
        job.cancelAndJoin()
        val afterCancel = provider.calls

        assertTrue(job.isCancelled, "после отмены цикл должен быть завершён")
        delay(WINDOW_MS * 2)
        assertEquals(
            afterCancel,
            provider.calls,
            "после отмены обращения к поставщику продолжаются: было $afterCancel, " +
                "стало ${provider.calls}"
        )
    }

    /**
     * Пауза ведёт к следующей точке сетки, а не отмеряется шагом после работы.
     *
     * Проверяется само правило и его границы: удар занимает время, и это время не должно
     * отодвигать расписание — иначе сетка сбора уезжает вместе с длительностью запроса,
     * а обновление, перекрывшее точку, не должно ни терять её, ни повторяться немедленно.
     */
    @Test
    fun `пауза ведёт к следующей точке сетки`() {
        val grid = 1_000_000L
        val interval = 60_000L

        assertEquals(
            interval,
            pauseToNextTick(grid, grid, interval),
            "удар без времени оставляет полный шаг до следующей точки"
        )
        assertEquals(
            59_500L,
            pauseToNextTick(grid, grid + 500, interval),
            "полсекунды работы не должны сдвигать сетку"
        )
        assertEquals(
            49_900L,
            pauseToNextTick(grid, grid + 70_100, interval),
            "перекрытая точка пропускается, а не проходит немедленно"
        )
        assertEquals(
            60_005L,
            pauseToNextTick(grid, grid - 5, interval),
            "часы, ушедшие назад, дают паузу вперёд, а не нулевую"
        )
    }

    /**
     * Служба сводок с часами, поставленными на середину одиннадцатого.
     *
     * Курсы двойника записаны в десять, поэтому закрывается час 10:00–11:00 и сводки выходят
     * непустыми. Системные часы здесь не годятся: «предыдущий час» зависел бы от времени запуска,
     * и проверка, запущенная в начале часа, собирала бы сводку по пустому часу.
     */
    private fun summaryService(
        repository: FakeCurrencyRateRepository,
        summaries: FakeCurrencyHourlySummaryRepository
    ): CurrencyHourlySummaryService = CurrencyHourlySummaryService(
        repository,
        summaries,
        Clock.fixed(Instant.parse("2026-09-28T11:30:00Z"), ZoneOffset.UTC)
    )

    private companion object {
        /** Интервал проверки: короткий, чтобы окно проверки не удлиняло прогон. */
        const val INTERVAL_MS = 30L

        /** Окно проверки: при таком интервале в него укладывается около девяти обновлений. */
        const val WINDOW_MS = 250L

        val INTERVAL: Duration = Duration.ofMillis(INTERVAL_MS)
    }
}

/**
 * Поставщик, который отказывает только на первом обращении.
 *
 * [FakeCurrencyRateProvider] задаёт отказ на всю свою жизнь и потому не может показать то, что
 * здесь важно: цикл пережил отказ и на следующем круге всё-таки собрал курсы. Отдельный двойник,
 * а не флаг в общем: общий уже описывает поведение сервиса, и менять его ради этой проверки
 * значило бы переписать чужие ожидания.
 */
private class FailsOnceProvider : CurrencyRateProvider {

    /** Сколько раз спрашивали — по этому видно, что отказ не оборвал цикл. */
    var calls: Int = 0
        private set

    private val rates: List<CurrencyRate> = FakeCurrencyRateProvider.allCurrencies()

    override suspend fun getRates(): List<CurrencyRate> {
        calls++
        if (calls == 1) throw CurrencyRateException("поставщик недоступен")
        return rates
    }
}
