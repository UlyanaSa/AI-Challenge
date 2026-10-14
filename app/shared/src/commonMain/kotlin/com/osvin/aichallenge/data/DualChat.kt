package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Метки панелей сравнения.
 *
 * Строками, а не перечислением: панели называет сервер, и клиент о них знает ровно
 * столько, сколько пришло в ответе, — иначе список панелей на клиенте разошёлся бы
 * с тем, что сервер умеет отвечать. Метка при этом устойчива: по ней клиент раскладывает
 * ответы по окнам ([CompareTurn]) и собирает историю каждого окна отдельно, поэтому
 * вторая сборка модели не видит ответов первой.
 */
object Pane {

    /** Как отвечает модель сейчас — та сборка, которая работала до оптимизации. */
    const val BASELINE = "baseline"

    /** Как отвечает оптимизированная сборка: свои настройки и свой шаблон промпта. */
    const val OPTIMIZED = "optimized"
}

/**
 * Панель сравнения: чем одна сборка модели отличается от другой.
 *
 * Настройки панели показываются рядом с её заголовком, а приходят с сервера: окно контекста
 * и бюджет ответа — часть того, что сравнивают, и клиент, назвавший их сам, показывал бы
 * свои умолчания вместо серверных.
 *
 * @param pane Метка панели ([Pane]).
 * @param title Заголовок окна: «неоптимизированная» или «оптимизированная».
 * @param model Имя модели этой панели: у оси «квантование» сборки отвечают разными моделями.
 * @param provider Провайдер панели (`local` — запрос не покидал машину).
 * @param temperature Температура генерации панели.
 * @param think Рассуждает ли модель панели перед ответом.
 * @param numCtx Окно контекста панели в токенах.
 * @param maxTokens Бюджет ответа панели; null — без бюджета.
 * @param promptTemplate Есть ли у панели своя системная инструкция под кейс.
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

/** Описание панелей: читается при включении сравнения, чтобы окна были подписаны до первого вопроса. */
@Serializable
data class DualChatInfo(val panes: List<PaneInfo> = emptyList())

/**
 * Реплика диалога на проводе: роль и текст.
 *
 * Отдельный тип, а не [ChatMessage]: у окна сравнения своя история, и метки времени, ветки
 * и вызовы инструментов в неё не входят — сравнивают ответы модели, а не состояние чата.
 */
@Serializable
data class PaneMessage(val role: String, val content: String)

/**
 * Запрос сравнения: один вопрос и история каждого окна.
 *
 * История у окон своя ([baselineHistory], [optimizedHistory]): если бы обеим сборкам
 * уходила одна история, вторая читала бы ответы первой и вопрос «какая из них лучше»
 * превратился бы в «какая из них лучше подражает соседке».
 *
 * @param message Текущий вопрос человека: он у обеих сборок один.
 * @param baselineHistory История окна «как сейчас».
 * @param optimizedHistory История оптимизированного окна.
 * @param systemPrompt Своя инструкция человека; null — у панели своя ([PaneInfo.promptTemplate])
 *        или её нет вовсе.
 * @param maxTokens Бюджет ответа из настроек чата; null — умолчание сервера.
 * @param stop Стоп-слова из настроек чата.
 * @param temperature Температура из настроек чата: в локальном режиме транспорт решает сам.
 */
@Serializable
data class DualChatRequest(
    val message: String,
    val baselineHistory: List<PaneMessage>? = null,
    val optimizedHistory: List<PaneMessage>? = null,
    val systemPrompt: String? = null,
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val temperature: Double? = null
)

/**
 * Ответ одной панели.
 *
 * Отказ сборки назван здесь ([error]), а не общим отказом запроса: одна модель может ответить,
 * а другая — нет, и сравнение от этого не отменяется. Окно с отказом показывает причину,
 * а соседнее — свой ответ.
 *
 * @param pane Метка панели ([Pane]).
 * @param title Заголовок окна: он приходит с ответом, чтобы окно было подписано и тогда,
 *        когда описание панелей не прочиталось.
 * @param reply Текст ответа.
 * @param model Модель, которая ответила, — её называет сервер, а не настройки клиента.
 * @param provider Провайдер ответа.
 * @param elapsedMs Время ответа сборки в миллисекундах: измерено сервером у вызова модели.
 * @param usage Расход токенов в формате API.
 * @param tokens Отчёт агента о расходе токенов.
 * @param error Причина отказа панели; null — сборка ответила.
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
) {

    /** Ответа нет, и названа причина — окно покажет причину вместо текста. */
    val failed: Boolean get() = error != null
}

/** Ответ сравнения: по одному ответу на каждую панель. */
@Serializable
data class DualChatResponse(val answers: List<PaneAnswer> = emptyList())
