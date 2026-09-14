package com.osvin.aichallenge.agent

import com.osvin.aichallenge.models.ChatMessage
import com.osvin.aichallenge.models.DialogBranch

/**
 * Ветки диалога: точка ветвления (checkpoint) и независимые продолжения от неё.
 *
 * Клиент помечает сообщения веткой ([ChatMessage.branchId]; null — основная линия)
 * и присылает структуру веток. В модель уходит только **путь активной ветки**:
 * общая с родителем часть до точки ветвления плюс её собственные сообщения.
 * Соседние ветки в запрос не попадают, поэтому продолжения не смешиваются,
 * а переключение ветки меняет контекст следующего же запроса.
 */
object DialogBranches {

    /** Сообщения основной линии: у них нет метки ветки. */
    private fun trunk(history: List<ChatMessage>): List<ChatMessage> = history.filter { it.branchId == null }

    /**
     * Путь активной ветки: контекст, который уходит в модель.
     *
     * Ветки вкладываются друг в друга ([DialogBranch.parentId]), поэтому путь
     * собирается рекурсивно: путь родителя до точки ветвления + свои сообщения.
     * Неизвестная ветка или петля в структуре — работаем как с основной линией.
     */
    fun activePath(
        history: List<ChatMessage>,
        branches: List<DialogBranch>,
        activeBranchId: String?
    ): List<ChatMessage> = path(history, branches, activeBranchId, mutableSetOf())

    private fun path(
        history: List<ChatMessage>,
        branches: List<DialogBranch>,
        branchId: String?,
        visited: MutableSet<String>
    ): List<ChatMessage> {
        val branch = branches.firstOrNull { it.id == branchId && visited.add(it.id) }
            ?: return trunk(history)
        val parent = path(history, branches, branch.parentId, visited)
        val shared = parent.take(branch.forkedAfter.coerceIn(0, parent.size))
        return shared + history.filter { it.branchId == branch.id }
    }
}
