package com.osvin.aichallenge.agent

/**
 * Лог агента: строки о запросе, истории диалога, ответе модели
 * и о том, что вернул провайдер при ошибке.
 *
 * Агент не привязан к конкретному логгеру: сервер печатает строки в консоль
 * ([Console]), тесты подставляют свою реализацию или [Silent].
 */
fun interface AgentLogger {
    /** Печатает одну строку лога. */
    fun log(message: String)

    companion object {
        /** Печать в консоль с префиксом агента. */
        val Console: AgentLogger = AgentLogger { println("[agent] $it") }

        /** Ничего не печатает. */
        val Silent: AgentLogger = AgentLogger { }
    }
}
