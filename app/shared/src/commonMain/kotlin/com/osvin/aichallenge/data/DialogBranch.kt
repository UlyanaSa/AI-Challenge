package com.osvin.aichallenge.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Ветка диалога: независимое продолжение от точки ветвления (checkpoint).
 * Клиент помечает сообщения веткой ([ChatMessage.branchId]; null — основная линия)
 * и присылает структуру веток вместе с историей (стратегия [ContextStrategy.BRANCHES]).
 *
 * @param id Идентификатор ветки; он же стоит на её сообщениях.
 * @param parentId Ветка-родитель: null — ветка идёт от основной линии диалога.
 * @param forkedAfter Сколько сообщений родительского пути общих с этой веткой:
 *        они уходят в модель как контекст до точки ветвления.
 * @param createdAt Когда ветку создали, в миллисекундах: нужно только списку веток
 *        на экране, в запрос к серверу это поле не уезжает.
 */
@Serializable
data class DialogBranch(
    val id: String,
    val parentId: String? = null,
    val forkedAfter: Int = 0,
    @Transient val createdAt: Long = System.currentTimeMillis()
)

/**
 * Ветки диалога: путь активной ветки — то, что из истории уходит в модель.
 * Правило то же, что у агента (`agent/DialogBranches.kt`): путь ветки — общая
 * с родителем часть до точки ветвления плюс её собственные сообщения, поэтому
 * соседние продолжения не смешиваются.
 */
object DialogBranches {

    /**
     * Путь активной ветки: контекст, который уходит в модель и показывается в чате.
     * Ветки вкладываются друг в друга ([DialogBranch.parentId]), поэтому путь
     * собирается рекурсивно. Неизвестная ветка или петля в структуре — основная линия.
     */
    fun activePath(
        history: List<ChatMessage>,
        branches: List<DialogBranch>,
        activeBranchId: String?
    ): List<ChatMessage> = path(history, branches, activeBranchId, mutableSetOf())

    /** Сообщения основной линии: у них нет метки ветки. */
    private fun trunk(history: List<ChatMessage>): List<ChatMessage> = history.filter { it.branchId == null }

    /**
     * Варианты продолжения после сообщения активного пути.
     *
     * Одна и та же точка диалога продолжается по-разному: своя линия сообщения
     * и ветки, отведённые от неё. Переключаться между вариантами можно на самом
     * сообщении — отдельного шага «перейти в ветку» не нужно.
     *
     * @param options Варианты по порядку: своя линия ([ChatMessage.branchId], null —
     *        основная линия) и ветки от этой точки, старые — первыми.
     * @param current Индекс варианта, в котором сейчас идёт диалог.
     */
    data class BranchChoice(val options: List<String?>, val current: Int) {

        /** Сколько вариантов у этой точки: один — переключать нечего. */
        val size: Int get() = options.size

        /** Соседний вариант: шаг по кругу, null — основная линия или родительская ветка. */
        fun neighbour(step: Int): String? = options[(current + step + options.size) % options.size]
    }

    /**
     * Выбор после сообщения активного пути: null — от этой точки диалог не ветвился.
     *
     * @param path Сообщения активного пути, то есть то, что видно в чате.
     * @param index Позиция сообщения в [path].
     * @param activeBranchId Активная ветка: по ней определяется текущий вариант,
     *        когда путь на этом сообщении заканчивается (например, ветка только создана).
     */
    fun choiceAfter(
        path: List<ChatMessage>,
        index: Int,
        branches: List<DialogBranch>,
        activeBranchId: String?
    ): BranchChoice? {
        val message = path.getOrNull(index) ?: return null
        val forks = branches
            .filter { it.parentId == message.branchId && it.forkedAfter == index + 1 }
            .sortedBy { it.createdAt }
        if (forks.isEmpty()) return null

        val options = listOf(message.branchId) + forks.map { it.id }
        // Текущий вариант — продолжение пути; если путь здесь кончился, смотрим,
        // не стоит ли пользователь в одной из только что созданных веток.
        val next = path.getOrNull(index + 1)?.branchId
        val current = when {
            next != null -> options.indexOf(next)
            activeBranchId != null && activeBranchId in options -> options.indexOf(activeBranchId)
            else -> 0
        }
        return BranchChoice(options, current.coerceAtLeast(0))
    }

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
