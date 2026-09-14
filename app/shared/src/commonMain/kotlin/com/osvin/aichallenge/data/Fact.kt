package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Факт диалога из памяти агента (стратегия «память фактов»): короткий ключ
 * и одна фраза значения. Приходит в отчёте ответа ([TokenReport.facts]),
 * поэтому блок памяти на экране чата собирается без отдельного запроса.
 *
 * @param key Ключ: что именно запомнили, например «цель».
 * @param value Значение: одна фраза без вступлений.
 */
@Serializable
data class Fact(val key: String, val value: String)
