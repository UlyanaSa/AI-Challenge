package com.osvin.aichallenge.currency

/**
 * Сбор курсов: получить у поставщика, проверить и дописать в историю — одной операцией.
 *
 * Операция одна намеренно: у планировщика, у ручного запуска (`--once`) и у проверок должен
 * быть один и тот же путь. Собранный отдельно «получить» и отдельно «сохранить» позволил бы
 * сохранить то, что не проверяли, или забыть записать то, что получили, — и заметить это можно
 * было бы только по пустой истории.
 *
 * Проверка здесь ровно одна: курс должен быть больше нуля. Ноль или отрицательное значение —
 * это не курс, а признак того, что поставщик отдал заглушку или ошибку в поле; записав такое
 * в историю, сводка потом считала бы минимум по нулю, и «минимум за сутки» перестал бы что-либо
 * значить. Всё остальное — дело поставщика: формат, диапазон, дата.
 *
 * Неполный ответ не считается отказом: если одна из валют не пришла, остальные всё равно
 * сохраняются, а пропущенная попадает в [CurrencyUpdate.Saved.skipped]. Так требует задание —
 * отсутствие курса одной валюты не должно останавливать сбор, — и так же ведёт себя сама
 * история: прежние значения остаются на месте, потому что удалять их здесь нечем.
 *
 * Ошибка возвращается значением ([CurrencyUpdate.Failed]), а не исключением: у сбора нет
 * вызывающего, который мог бы её обработать, — это цикл планировщика, и его задача записать
 * причину в лог и продолжить по расписанию. Исключение здесь означало бы, что конец цикла
 * зависит от того, не забыл ли его кто-нибудь обернуть.
 *
 * @param provider Откуда берутся курсы.
 * @param repository Куда они дописываются.
 */
class CurrencyService(
    private val provider: CurrencyRateProvider,
    private val repository: CurrencyRateRepository
) {

    /**
     * Одно обновление: получить, проверить, сохранить.
     *
     * Возврат [CurrencyUpdate] вместо исключения — см. описание класса.
     */
    suspend fun update(): CurrencyUpdate = try {
        val received = provider.getRates()
        val accepted = received.filter { it.rateToRub.signum() > 0 }
        val named = accepted.map { it.currency }.toSet()
        // Пропущенные — это и те, кого поставщик не назвал, и те, чей курс не прошёл проверку:
        // для истории и для лога это одно и то же событие «курса нет», а разница между
        // «не пришёл» и «пришёл непригодным» видна в тексте причины у отказа поставщика.
        val skipped = Currency.entries.filterNot { it in named }
        val saved = repository.save(accepted)
        CurrencyUpdate.Saved(rates = accepted, skipped = skipped, rows = saved)
    } catch (error: CurrencyRateException) {
        CurrencyUpdate.Failed(error.message ?: "курсы не получены")
    } catch (error: CurrencyStorageException) {
        CurrencyUpdate.Failed(error.message ?: "курсы не сохранены")
    }

    /**
     * Последние сохранённые курсы — то, что отдаёт инструмент `get_currency_rates`.
     *
     * Отказ хранилища здесь исключение, а не [CurrencyUpdate.Failed]: у инструмента есть
     * вызывающий (модель), и он должен получить ответ с пометкой отказа, а не пустой список.
     */
    suspend fun latest(): List<CurrencyRate> = repository.latest()
}

/**
 * Итог одного обновления.
 *
 * @see CurrencyUpdate.Saved
 * @see CurrencyUpdate.Failed
 */
sealed interface CurrencyUpdate {

    /**
     * Курсы сохранены.
     *
     * @param rates Что легло в историю.
     * @param skipped Валюты, которых в этом обновлении не оказалось: не пришли от поставщика
     *        или не прошли проверку.
     * @param rows Сколько строк дописано в историю: их число отличается от [rates], только если
     *        хранилище отказало частично, — и это видно в логе, а не выясняется по базе.
     */
    data class Saved(
        val rates: List<CurrencyRate>,
        val skipped: List<Currency>,
        val rows: Int = rates.size
    ) : CurrencyUpdate

    /** Обновления не было: [reason] — словами, что случилось. */
    data class Failed(val reason: String) : CurrencyUpdate
}
