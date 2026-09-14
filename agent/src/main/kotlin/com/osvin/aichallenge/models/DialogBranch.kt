package com.osvin.aichallenge.models

import kotlinx.serialization.Serializable

/**
 * Ветка диалога: независимое продолжение от точки ветвления (checkpoint).
 * Клиент помечает сообщения веткой ([ChatMessage.branchId]; null — основная линия)
 * и присылает структуру веток вместе с историей.
 *
 * @param id Идентификатор ветки; он же стоит на её сообщениях.
 * @param parentId Ветка-родитель: null — ветка идёт от основной линии диалога.
 * @param forkedAfter Сколько сообщений родительского пути общих с этой веткой:
 *        они уходят в модель как контекст до точки ветвления.
 */
@Serializable
data class DialogBranch(
    val id: String,
    val parentId: String? = null,
    val forkedAfter: Int = 0
)
