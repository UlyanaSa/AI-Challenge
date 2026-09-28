package com.osvin.aichallenge.orchestration

import com.osvin.aichallenge.agent.AgentTool

/**
 * Инструмент одного сервера: как он называется у клиента и на каком сервере живёт.
 *
 * Имя сервера хранится рядом с инструментом, а не ищется по имени инструмента: два сервера
 * вправе объявить один и тот же инструмент (у пайплайна и у сервера отчётов есть запись файла),
 * и вопрос «чей это вызов» решается сервером, а не догадкой по имени.
 *
 * [qualifiedName] — «сервер.инструмент». Именно его видит модель: без признака сервера две
 * записи файла в одном списке были бы одним именем с двумя разными смыслами, и выбор между
 * ними пришлось бы делать коду, а не модели.
 */
data class RegisteredTool(val serverId: String, val name: String, val tool: AgentTool) {

    /** Имя вызова: сервер и инструмент через точку. */
    val qualifiedName: String get() = "$serverId.$name"
}

/**
 * Единый реестр инструментов всех подключённых серверов: одна точка, где по имени вызова
 * находится сервер.
 *
 * Реестр — только справочник: он ничего не вызывает и не решает, какой инструмент нужен.
 * Решение принимает модель, а работа реестра — назвать по её вызову сервер (см. [McpOrchestrator]).
 * Складывать поиск сервера в сам вызов инструмента не стали: тогда признак сервера пришлось бы
 * повторять в каждой обёртке, и коллизия имён решалась бы тем, кто положил инструмент в список.
 *
 * Повторное имя (тот же сервер и тот же инструмент во второй раз — например, сессию подняли
 * заново, а старые инструменты остались) не заменяет прежнее и не удваивает список: первое
 * объявление и есть объявление сервера, а [register] отвечает, сколько инструментов принято
 * и сколько пропущено, чтобы вызывающий сказал об этом в лог.
 */
class McpToolRegistry {

    private val byQualifiedName = LinkedHashMap<String, RegisteredTool>()

    /**
     * Добавляет инструменты сервера.
     *
     * @return Сколько инструментов принято и сколько пропущено как повторные.
     */
    fun register(serverId: String, tools: List<AgentTool>): Registration {
        var added = 0
        var skipped = 0
        tools.forEach { tool ->
            val registered = RegisteredTool(serverId, tool.name, tool)
            if (byQualifiedName.putIfAbsent(registered.qualifiedName, registered) == null) {
                added++
            } else {
                skipped++
            }
        }
        return Registration(added = added, skipped = skipped)
    }

    /** Все инструменты в порядке появления: по этому списку строится список для модели. */
    fun all(): List<RegisteredTool> = byQualifiedName.values.toList()

    /**
     * Инструмент по имени вызова; `null` — такого имени нет.
     *
     * Имя ищется целиком, а не по части после точки: инструмент без признака сервера в реестр
     * не попадает, и разбирать чужое имя на «сервер» и «инструмент» значило бы принимать вызовы,
     * которых сервер не объявлял.
     */
    fun find(qualifiedName: String): RegisteredTool? = byQualifiedName[qualifiedName]

    /** Есть ли среди подключённых серверов такой: по нему различаются «нет сервера» и «нет инструмента». */
    fun knowsServer(serverId: String): Boolean = byQualifiedName.values.any { it.serverId == serverId }
}

/** Что дал [McpToolRegistry.register]: принято и пропущено. */
data class Registration(val added: Int, val skipped: Int)
