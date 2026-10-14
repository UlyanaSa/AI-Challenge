package com.osvin.aichallenge.models

import com.osvin.aichallenge.agent.TokenReport
import kotlinx.serialization.Serializable

/**
 * Метки сборок сравнения.
 *
 * Метка, а не перечисление: она уходит на провод строкой и по ней клиент раскладывает
 * ответы по окнам, поэтому значение обязано быть устойчивым между версиями сервера
 * и приложения.
 */
object Pane {

    /** Как модель отвечает сейчас: настройки запуска, без собственной инструкции. */
    const val BASELINE = "baseline"

    /** Оптимизированная сборка: окно контекста, инструкция под кейс и бюджет ответа. */
    const val OPTIMIZED = "optimized"
}

/**
 * Описание одной сборки модели для сравнения.
 *
 * Отдельный тип, а не поля ответа: настройки нужны до первого вопроса — по ним видно, что
 * именно сравнивают, — и приходят они отдельным чтением. Клиент по этим полям подписывает окна,
 * а не подставляет свои умолчания, поэтому окна и сервер говорят об одних и тех же сборках.
 *
 * @param pane Метка панели: `baseline` — как сейчас, `optimized` — оптимизированная сборка.
 * @param title Заголовок окна.
 * @param model Имя модели этой сборки.
 * @param provider Провайдер (`local` — генерация не покидала машину).
 * @param temperature Температура генерации.
 * @param think Рассуждает ли модель перед ответом.
 * @param numCtx Окно контекста в токенах.
 * @param maxTokens Бюджет ответа; null — без бюджета.
 * @param promptTemplate Есть ли у сборки своя системная инструкция под кейс.
 */
@Serializable
data class PaneInfo(
    val pane: String,
    val title: String,
    val model: String,
    val provider: String,
    val temperature: Double = 0.0,
    val think: Boolean = true,
    val numCtx: Int = 0,
    val maxTokens: Int? = null,
    val promptTemplate: Boolean = false
)

/** Описание сборок сравнения: читается клиентом при входе в режим сравнения. */
@Serializable
data class DualChatInfo(val panes: List<PaneInfo> = emptyList())

/**
 * Запрос сравнения: один вопрос и история каждого окна.
 *
 * История у окон своя: каждая сборка продолжает собственный разговор, иначе вторая читала бы
 * ответы первой. История приходит в том же виде, что у обычного чата, — роли и тексты ([ChatMessage]),
 * — поэтому второе представление переписки на проводе не заводится.
 *
 * @param message Вопрос человека: он у обеих сборок один.
 * @param baselineHistory История окна «как сейчас».
 * @param optimizedHistory История оптимизированного окна.
 * @param systemPrompt Своя инструкция человека: она важнее шаблона сборки.
 * @param maxTokens Бюджет ответа из настроек чата.
 * @param stop Стоп-слова из настроек чата.
 * @param temperature Температура из настроек чата; в локальном режиме транспорт решает сам.
 */
@Serializable
data class DualChatRequest(
    val message: String,
    val baselineHistory: List<ChatMessage>? = null,
    val optimizedHistory: List<ChatMessage>? = null,
    val systemPrompt: String? = null,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val temperature: Double? = null
)

/**
 * Ответ одной сборки.
 *
 * Отказ сборки лежит здесь, а не в отказе всего запроса: модели отвечают независимо, и одна
 * может не ответить, пока другая отвечает. Причина отказа уходит текстом — тем же, что человек
 * увидел бы в обычном чате.
 *
 * @param pane Метка панели.
 * @param title Заголовок окна: приходит с ответом, чтобы окно было подписано и без описания сборок.
 * @param reply Текст ответа.
 * @param model Модель, которая ответила.
 * @param provider Провайдер ответа.
 * @param elapsedMs Время ответа сборки: от отправки запроса до ответа, в миллисекундах.
 * @param usage Расход токенов в формате API.
 * @param tokens Отчёт агента о расходе токенов.
 * @param error Причина отказа; null — сборка ответила.
 */
@Serializable
data class PaneAnswer(
    val pane: String,
    val title: String = "",
    val reply: String = "",
    val model: String? = null,
    val provider: String? = null,
    val elapsedMs: Long = 0,
    val usage: Map<String, Int>? = null,
    val tokens: TokenReport? = null,
    val error: String? = null
)

/** Ответ сравнения: по одному ответу на каждую сборку. */
@Serializable
data class DualChatResponse(val answers: List<PaneAnswer> = emptyList())
