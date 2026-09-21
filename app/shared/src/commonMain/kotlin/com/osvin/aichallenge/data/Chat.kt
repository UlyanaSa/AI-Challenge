package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable

/**
 * Чат — отдельный диалог пользователя с агентом.
 *
 * @param id Идентификатор чата; он же — идентификатор сессии агента на сервере,
 * поэтому он хранится в БД устройства и не меняется, пока чат не удалён.
 * @param title Заголовок чата: берётся из первого сообщения пользователя.
 * @param createdAt Время создания в миллисекундах.
 * @param updatedAt Время последнего сообщения: по нему чаты сортируются в списке.
 * @param activeBranchId Ветка диалога, в которой продолжается чат; null — основная
 *        линия. Хранится у чата, чтобы после перезапуска приложения открылась
 *        та же ветка, в которой пользователь остановился.
 * @param strategy Стратегия управления контекстом этого чата (wire-значение
 *        [ContextStrategy.wire]). Тоже хранится у чата, а не в общих настройках:
 *        память на сервере принадлежит сессии, то есть чату, и после перезапуска
 *        приложения или открытия соседнего чата чат должен читать те записи памяти,
 *        что сделаны в нём, — иначе выбранная стратегия терялась бы, и записанное
 *        в шторке памяти не уходило бы в модель.
 */
@Serializable
data class Chat(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val activeBranchId: String? = null,
    val strategy: String = ContextStrategy.MEMORY.wire
)
