package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.util.Locale
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertTrue

/** Вопрос, ответ на который лежит в самом начале диалога: после сжатия его видно только в сводке. */
private const val BUDGET_QUESTION = "Какой бюджет я называл в самом начале?"

/** Ответы подставленного транспорта: и сводка, и ответ содержат названный бюджет. */
private const val CANNED_SUMMARY =
    "Сводка: бюджет на дорогу 5 000 рублей в месяц; такси 1 680, метро по проездному 2 000, " +
        "поезд 1 200, самокат 640; решено убрать самокат и половину такси."
private const val CANNED_REPLY = "Вы называли бюджет 5 000 рублей в месяц на дорогу."

/** Сессия демонстрации: по ней хранилище держит сводку отдельно от сообщений. */
private const val SESSION = "demo-compression"

/** Размеры истории, на которых сравниваем ветки: видно, когда сжатие включается. */
private val HISTORY_SIZES = listOf(12, 16, 20, 24)

/**
 * Диалог из 12 пар сообщений. Бюджет назван в первом же сообщении, ответы модели —
 * такими же многословными, как на живом API: именно они и раздувают историю.
 */
private val BUDGET_DIALOG: List<ChatMessage> = listOf(
    ChatMessage(
        "user",
        "Считаем дорогу на месяц. Мой бюджет — 5 000 рублей: такси до работы, метро и поездки к родителям. " +
            "Всё, что выйдет за эту сумму, придётся вычеркнуть."
    ),
    ChatMessage(
        "assistant",
        "Записал: бюджет на дорогу — 5 000 рублей в месяц, и считаем его жёстким. Разбираем по статьям: " +
            "такси до работы, метро, поезда к родителям и мелочи вроде самоката или каршеринга. " +
            "Как только суммарно выйдем за 5 000 рублей, будем убирать самые дорогие позиции — сначала те, " +
            "без которых месяц можно прожить, потом остальное. Говорите цифры по статьям, я считаю сумму " +
            "и держу в голове остаток бюджета, чтобы вы всегда видели, сколько ещё осталось."
    ),
    ChatMessage("user", "Такси до работы стоит 420 рублей в одну сторону, езжу дважды в неделю."),
    ChatMessage(
        "assistant",
        "Считаю такси. Дважды в неделю в одну сторону — это восемь поездок за месяц, то есть 8 × 420 = 3 360 рублей. " +
            "Из 5 000 рублей бюджета на такси уходит чуть больше двух третей, и это самая дорогая статья. " +
            "Остатка в 1 640 рублей не хватит на метро и поезда одновременно, так что дальше придётся выбирать, " +
            "что именно сокращать: либо число поездок на такси, либо поездки к родителям. Скажите, сколько " +
            "поездок на такси для вас действительно обязательно, и я пересчитаю остаток."
    ),
    ChatMessage("user", "Метро — 62 рубля за поездку, получается около сорока поездок в месяц."),
    ChatMessage(
        "assistant",
        "Считаю метро: 40 поездок × 62 рубля = 2 480 рублей за месяц. Вместе с такси получается 3 360 + 2 480 = " +
            "5 840 рублей, то есть бюджет уже превышен на 840 рублей. Это перерасход без поездов к родителям, " +
            "а они в планах тоже есть, поэтому нужны решения. Самые простые: уменьшить число поездок на такси " +
            "с восьми до четырёх (освободит 1 680 рублей), взять проездной на метро вместо разовых поездок " +
            "или уменьшить число поездок к родителям с двух до одной."
    ),
    ChatMessage("user", "Поезд к родителям — 1 200 рублей туда-обратно, я планировал две поездки."),
    ChatMessage(
        "assistant",
        "Считаю поезда: две поездки туда-обратно — 2 400 рублей. С такси и метро суммарно выходит " +
            "3 360 + 2 480 + 2 400 = 8 240 рублей, а бюджет 5 000, то есть перерасход 3 240 рублей. " +
            "Это уже слишком много, чтобы закрыть мелкими отказами. Рабочий набор решений: оставить одну " +
            "поездку на поезде вместо двух (−1 200), взять проездной на метро вместо разовых поездок (−480), " +
            "и сократить такси вдвое (−1 680). Вместе это вычитает 3 360 рублей и возвращает расходы " +
            "к 4 880 рублям, то есть внутрь бюджета."
    ),
    ChatMessage("user", "Тогда оставляю одну поездку на поезде и убираю половину такси."),
    ChatMessage(
        "assistant",
        "Пересчитываю по вашему решению: такси 4 поездки × 420 = 1 680 рублей, метро 2 480 рублей, " +
            "поезд одна поездка туда-обратно 1 200 рублей. Итого 5 360 рублей — бюджет превышен на 360 рублей. " +
            "Перерасход небольшой, его закрывает проездной на метро за 2 000 рублей вместо разовых поездок: " +
            "тогда метро дешевеет на 480 рублей и суммарно выходит 4 880 рублей, остаток бюджета 120 рублей."
    ),
    ChatMessage("user", "А если я буду ходить пешком до метро, что получится?"),
    ChatMessage(
        "assistant",
        "Если пешком до метро и обратно, такси остаётся только на дни с плохой погодой — это примерно " +
            "две поездки в месяц вместо четырёх. Такси: 2 × 420 = 840 рублей, экономия к прежнему варианту " +
            "840 рублей. Метро по проездному 2 000 рублей, поезд 1 200 рублей. Итого 4 040 рублей, " +
            "остаток бюджета 960 рублей. Такой вариант самый устойчивый: он оставляет запас почти в тысячу " +
            "рублей на непредвиденные поездки и не требует отказываться от визитов к родителям."
    ),
    ChatMessage("user", "Ещё есть подписка на каршеринг за 900 рублей в месяц, но я ею почти не пользуюсь."),
    ChatMessage(
        "assistant",
        "Подписка на каршеринг за 900 рублей в месяц при почти нулевом использовании — это чистый убыток: " +
            "за год она съедает 10 800 рублей, а выгоду даёт только при регулярных поездках, которых у вас нет. " +
            "Рекомендую отказаться: это самая простая экономия во всём плане, она не меняет ваш образ жизни. " +
            "С отказом от подписки и вариантом «пешком до метро» расходы падают до 4 040 рублей, " +
            "а свободный остаток бюджета становится 960 + 900 = 1 860 рублей."
    ),
    ChatMessage("user", "Летом беру самокат, это ещё 640 рублей за месяц."),
    ChatMessage(
        "assistant",
        "Самокат за 640 рублей в месяц — сезонная статья, и её стоит держать отдельно от обязательных " +
            "расходов. Если оставить самокат, из остатка в 1 860 рублей вычитается 640 и остаётся 1 220 рублей. " +
            "Это всё ещё внутри бюджета, поэтому отказываться от самоката не обязательно: он экономит время " +
            "на коротких поездках. Если же бюджет нужен с запасом, самокат — первая статья, которую можно " +
            "убрать без последствий для работы и визитов к родителям."
    ),
    ChatMessage("user", "Может, взять проездной на метро за 2 000 рублей?"),
    ChatMessage(
        "assistant",
        "Проездной за 2 000 рублей выгоден, если вы ездите больше 32 раз в месяц: сорок разовых поездок " +
            "по 62 рубля стоят 2 480 рублей, то есть проездной экономит 480 рублей и окупается уже на третьей " +
            "неделе. При вашем режиме брать его стоит. Он же снимает вопрос о том, ехать ли в отдельный день " +
            "на метро ради одной поездки: с проездным лишняя поездка не стоит ничего дополнительно."
    ),
    ChatMessage("user", "Собери, пожалуйста, итоговую картину по статьям."),
    ChatMessage(
        "assistant",
        "Итог по статьям на месяц: такси 4 поездки × 420 = 1 680 рублей; метро по проездному 2 000 рублей; " +
            "поезд к родителям одна поездка туда-обратно 1 200 рублей; самокат 640 рублей. Сумма — 5 520 рублей, " +
            "то есть на 520 рублей больше бюджета. Если убрать самокат, выходит 4 880 рублей и остаток 120 рублей. " +
            "Если дополнительно отказаться от подписки на каршеринг, остаток вырастает до 1 020 рублей."
    ),
    ChatMessage("user", "Что можно убрать, если я хочу уложиться в бюджет?"),
    ChatMessage(
        "assistant",
        "Чтобы гарантированно уложиться в 5 000 рублей, достаточно двух действий: убрать самокат (−640 рублей) " +
            "и отказаться от подписки на каршеринг (−900 рублей). Тогда расходы становятся 3 980 рублей, " +
            "а остаток бюджета — 1 020 рублей. Если хочется сохранить самокат, уложиться тоже можно, " +
            "но без запаса: 4 880 рублей и остаток 120 рублей, которые съест любая незапланированная поездка. " +
            "Я бы выбрал первый вариант: запас важнее сезонной мелочи."
    ),
    ChatMessage("user", "Запиши итог: сколько я планировал потратить на дорогу и сколько выходит."),
    ChatMessage(
        "assistant",
        "Итог: планировали 5 000 рублей в месяц на дорогу, по факту выходит 3 980 рублей — с отказом " +
            "от самоката и подписки на каршеринг. Свободный остаток 1 020 рублей, это двадцать процентов " +
            "бюджета. Основные статьи: такси 1 680 рублей, метро по проездному 2 000 рублей, поезд " +
            "к родителям 1 200 рублей. Если в каком-то месяце понадобится вторая поездка к родителям, " +
            "она стоит 1 200 рублей и укладывается в остаток."
    )
)

/** Цена вызова вместе со служебным вызовом сводки: сжатие тоже стоит токенов. */
private fun totalCallCost(tokens: TokenReport): Double = callCost(tokens) + (tokens.compressionCostUsd ?: 0.0)

/** Ответ помнит названный бюджет — по числу, а не по формулировке. */
private fun mentionsBudget(answer: String): Boolean = answer.replace(" ", "").contains("5000")

/** В подставленном режиме качество не проверяется: ответ задан заранее. */
private fun qualityNote(): String =
    if (demoOnLiveApi) "" else " (ответ подставлен — о качестве судить нельзя, нужен -Pdemo.live=1)"

/** Строка таблицы расхода: сколько истории ушло и во что обошёлся вызов. */
private fun row(messages: Int, branch: String, tokens: TokenReport): String = String.format(
    Locale.ROOT, "%7d %-11s %9d %10d %7d %7d  \$%.6f",
    messages, branch, tokens.historyRawTokens, tokens.history,
    tokens.promptTokens, tokens.replyTokens, totalCallCost(tokens)
)

/**
 * День 9: сравнение диалога со сжатием истории и без него.
 *
 * Без сжатия в модель уходит вся история; со сжатием последние сообщения идут как есть,
 * а старшие заменяет сводка — она хранится отдельно ([InMemorySummaryStore]) и подставляется
 * в запрос вместо свёрнутых сообщений. Вопрос одинаковый и лежит в начале диалога: видно,
 * сохранило ли сжатие то, что модель должна помнить.
 *
 * Прогон с печатью логов:
 *
 *     ./gradlew :agent:demoLogs
 *     ./gradlew :agent:demoLogs -Pdemo.live=1   // живые вызовы, ключ из server/.env
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class HistoryCompressionDemoTest {

    /** Качество и расход без сжатия: базовый вариант, вся история в запросе. */
    @Test
    fun stage1_qualityWithoutCompression() = runBlocking {
        stage("Без сжатия: в модель уходит вся история (${BUDGET_DIALOG.size} сообщений)")
        val agent = LlmAgent(demoClient(CANNED_REPLY), logger = AgentLogger.Console)
        val result = agent.run(BUDGET_QUESTION, options(BUDGET_DIALOG))
        val tokens = result.tokens

        log("--- качество ответа ---")
        log("вопрос: «$BUDGET_QUESTION»")
        log("ответ: ${result.reply.replace("\n", " ")}")
        log("бюджет назван в ответе: ${if (mentionsBudget(result.reply)) "да" else "нет"}${qualityNote()}")
        log("--- расход ---")
        log("история в запросе: ${tokens.history} ток. (свёрнуто сообщений: ${tokens.foldedMessages})")
        log("вход: ${tokens.promptTokens} ток., ответ: ${tokens.replyTokens} ток.")
        log("цена вызова: \$${money(totalCallCost(tokens))}")
    }

    /** Качество и расход со сжатием: старшие сообщения заменены сводкой. */
    @Test
    fun stage2_qualityWithCompression() = runBlocking {
        stage("Со сжатием: старшие сообщения заменены сводкой")
        val store = InMemorySummaryStore()
        val agent = LlmAgent(
            demoClient(CANNED_REPLY, CANNED_SUMMARY),
            summaryStore = store,
            logger = AgentLogger.Console
        )
        val result = agent.run(
            BUDGET_QUESTION,
            options(BUDGET_DIALOG, sessionId = SESSION, strategy = ContextStrategy.SUMMARY)
        )
        val tokens = result.tokens

        log("--- качество ответа ---")
        log("вопрос: «$BUDGET_QUESTION»")
        log("ответ: ${result.reply.replace("\n", " ")}")
        log("бюджет назван в ответе: ${if (mentionsBudget(result.reply)) "да" else "нет"}${qualityNote()}")
        log("--- сводка вместо свёрнутых сообщений ---")
        store.get(SESSION)?.let { log("сводка (${it.tokens} ток.): ${it.text.replace("\n", " ")}") }
        log("--- расход ---")
        log("свёрнуто сообщений: ${tokens.foldedMessages}")
        log(
            "история к отправке: ${tokens.history} ток. вместо ${tokens.historyRawTokens} ток. " +
                "(экономия ${tokens.historyRawTokens - tokens.history} ток. на этом и каждом следующем ходу)"
        )
        log("вход: ${tokens.promptTokens} ток., ответ: ${tokens.replyTokens} ток.")
        log("служебный вызов сводки: ${tokens.compressionTokens} ток., \$${money(tokens.compressionCostUsd ?: 0.0)}")
        log("цена вызова вместе со сводкой: \$${money(totalCallCost(tokens))}")
        assertTrue(tokens.foldedMessages > 0, "на диалоге из ${BUDGET_DIALOG.size} сообщений сжатие должно включиться")
    }

    /** Расход по растущей истории: один и тот же вопрос, обе ветки, окупаемость служебного вызова. */
    @Test
    fun stage3_tokensBeforeAndAfter() = runBlocking {
        stage("Расход по растущей истории: без сжатия и со сжатием")
        val plain = LlmAgent(demoClient(CANNED_REPLY), logger = AgentLogger.Silent)
        val compressed = LlmAgent(
            demoClient(CANNED_REPLY, CANNED_SUMMARY),
            summaryStore = InMemorySummaryStore(),
            logger = AgentLogger.Silent
        )

        log(
            String.format(
                Locale.ROOT, "%7s %-11s %9s %10s %7s %7s %10s",
                "сообщ.", "ветка", "история", "к отправке", "вход", "ответ", "цена"
            )
        )
        var plainTotal = 0.0
        var compressedTotal = 0.0
        var serviceTotal = 0.0
        var savingPerCall = 0.0
        var serviceCost = 0.0
        var steadyPlain = 0.0
        var steadyCompressed = 0.0
        var steadyMessages = 0

        for (size in HISTORY_SIZES) {
            val history = BUDGET_DIALOG.take(size)
            val without = plain.run(BUDGET_QUESTION, options(history)).tokens
            val with = compressed.run(
                BUDGET_QUESTION,
                options(history, sessionId = SESSION, strategy = ContextStrategy.SUMMARY)
            ).tokens

            plainTotal += totalCallCost(without)
            compressedTotal += totalCallCost(with)
            serviceTotal += with.compressionCostUsd ?: 0.0
            log(row(size, "без сжатия", without))
            log(row(size, "со сжатием", with))

            if (with.foldedMessages > 0) {
                // Свёрнутые сообщения в модель не уходят — иначе сжатия просто нет.
                assertTrue(
                    with.history < with.historyRawTokens,
                    "свёрнутые сообщения не должны уходить в модель"
                )
                val inputPrice = ModelCatalog.spec(DEMO_MODEL).inputPer1MUsd ?: 0.0
                savingPerCall = (without.promptTokens - with.promptTokens) / 1_000_000.0 * inputPrice
                // Окупаемость считаем по последнему вызову, в котором сводка действительно строилась.
                (with.compressionCostUsd ?: 0.0).takeIf { it > 0 }?.let { serviceCost = it }
                // Установившийся режим: сводка уже есть, платим только за урезанную историю.
                if (with.compressionCostUsd == null) {
                    steadyPlain = totalCallCost(without)
                    steadyCompressed = totalCallCost(with)
                    steadyMessages = size
                }
            }
        }

        val share = if (plainTotal > 0) (plainTotal - compressedTotal) / plainTotal * 100 else 0.0
        log(
            "итого за ${HISTORY_SIZES.size} вызова: без сжатия \$${money(plainTotal)}, " +
                "со сжатием \$${money(compressedTotal)} " +
                "(в том числе служебные вызовы сводки \$${money(serviceTotal)})"
        )
        log(
            String.format(
                Locale.ROOT,
                "разница: %.1f%% %s",
                share,
                if (share >= 0) "в пользу сжатия" else "не в пользу сжатия: сводка ещё не окупилась"
            )
        )
        if (steadyPlain > 0) {
            log(
                String.format(
                    Locale.ROOT,
                    "установившийся режим: на тех же %d сообщениях вызов со сжатием $%.6f против $%.6f — на %.1f%% дешевле",
                    steadyMessages, steadyCompressed, steadyPlain,
                    (steadyPlain - steadyCompressed) / steadyPlain * 100
                )
            )
        }
        if (savingPerCall > 0 && serviceCost > 0) {
            val breakEven = ceil(serviceCost / savingPerCall).toInt()
            log(
                "окупаемость: служебный вызов \$${money(serviceCost)} окупается за $breakEven " +
                    "следующих вызовов — экономия на истории \$${money(savingPerCall)} за вызов " +
                    "(диалог из ${HISTORY_SIZES.last()} сообщений)"
            )
        }
    }
}
