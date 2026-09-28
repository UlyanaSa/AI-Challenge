package com.osvin.aichallenge.mcp.github

import com.osvin.aichallenge.mcp.ServerTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Состояние доступа сервера к GitHub — то, что отдаёт инструмент `github_access`.
 *
 * Это обещанная форма ответа, а не внутренняя структура доступа: её читает клиент (и человек),
 * поэтому поля названы словами отчёта, а не словами хранилища, и её нельзя менять, не тронув
 * клиента. Форма одна на оба состояния — «доступ есть» и «доступа нет»: клиенту нужно разобрать
 * ответ одним типом, а `authorized` и говорит, какое из двух состояний пришло.
 *
 * Токена здесь нет ни в каком виде — [source] называет только место, откуда он взят. Иначе
 * отчёт о доступе сам стал бы доступом: он уезжает клиенту, ложится в его кэш и в его логи.
 *
 * Умолчаний у полей намеренно нет: они печатаются всегда, и клиент видит всю форму целиком,
 * а не догадывается, что `"hint"` отсутствует именно потому, что её нет.
 *
 * @param authorized Доступ есть: токен найден и GitHub его принял.
 * @param login Кто вошёл; null — доступа нет или GitHub не назвал логин.
 * @param scopes Права токена; пусто — прав нет или GitHub их не сообщил (см. [scopesReported]).
 * @param scopesReported GitHub сообщил права в заголовке: у fine-grained токенов его нет вовсе,
 *        и «права не сообщены» — не то же самое, что «прав нет».
 * @param source Откуда взят токен, словами; null — доступа нет и брать было неоткуда.
 * @param hint Почему доступа нет и что сделать; null — доступ есть.
 */
@Serializable
data class GitHubAccessReport(
    val authorized: Boolean,
    val login: String?,
    val scopes: List<String>,
    val scopesReported: Boolean,
    val source: String?,
    val hint: String?
) {
    companion object {

        /**
         * Отчёт «доступа нет» с причиной в [hint] — единственная форма отчёта без доступа.
         *
         * Отдельной сборкой этого состояния не обойтись: его собирают и разбор доступа, и сам
         * инструмент на неожиданном сбое, а две сборки одного смысла разошлись бы — например,
         * одна забыла бы обнулить логин, и отчёт назвал бы того, кто в эту минуту не входил.
         *
         * @param source Место, откуда токен всё же был взят, если оно известно: при недоступной
         *        сети токен есть, и человеку видно, какой именно токен проверяли.
         */
        fun unavailable(hint: String?, source: String? = null): GitHubAccessReport = GitHubAccessReport(
            authorized = false,
            login = null,
            scopes = emptyList(),
            scopesReported = false,
            source = source,
            hint = hint
        )
    }
}

/** Формат ответа: с отступами, как у сервера проекта, — его читают и человек, и модель. */
private val JSON = Json {
    prettyPrint = true
    encodeDefaults = true
}

/**
 * Инструмент `github_access`: состояние доступа сервера к GitHub — без аргументов.
 *
 * Аргумента нет намеренно: вопрос один — «есть ли у сервера доступ и какой», и разбирать его
 * на части («спроси логин», «спроси права») значило бы заставлять модель угадывать порядок
 * вопросов. Ответ — один JSON со всем сразу.
 *
 * Ошибкой инструмент не отвечает никогда (`isError = false`): и «доступа нет», и «сеть до GitHub
 * недоступна» — это состояние доступа с причиной в `hint`, а не отказ вызова. Для клиента
 * разница принципиальна: по этому ответу он решает, показывать ли кнопку «Проверить снова»,
 * а инструмент с `isError` выглядел бы сломанным ровно тогда, когда он работает и говорит
 * правду.
 */
fun githubAccessTool(): ServerTool<GitHubApi> = ServerTool(
    name = "github_access",
    description = "Состояние доступа сервера к GitHub: есть ли доступ, кто вошёл, какие у токена " +
        "права и откуда токен взят. Ошибкой не отвечает: недоступность — это состояние с причиной.",
    arguments = emptyList()
) { api, _ ->
    val report = try {
        api.access()
    } catch (cancelled: CancellationException) {
        // Отмена — не состояние доступа: её должен увидеть вызывающий, иначе закрытие сессии
        // вернуло бы «доступа нет» вместо того, чтобы остановиться.
        throw cancelled
    } catch (error: Exception) {
        // Неожиданный сбой (не наш разобранный случай, а, например, чужой ответ, который
        // не разобрался) остаётся состоянием доступа: обещание «isError всегда false» важнее
        // того, чтобы причина пришла клиенту отдельным кодом.
        GitHubAccessReport.unavailable("доступ к GitHub не удалось проверить: ${error.message}")
    }
    CallToolResult(content = listOf(TextContent(JSON.encodeToString(report))), isError = false)
}
