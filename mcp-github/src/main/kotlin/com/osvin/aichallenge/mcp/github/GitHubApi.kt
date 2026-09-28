package com.osvin.aichallenge.mcp.github

/**
 * Источник репозиториев GitHub — то, что нужно инструменту и что подменяют тесты.
 *
 * Интерфейс, а не конкретный класс: обработчик инструмента не должен знать про HTTP, поэтому
 * он проверяется на подставном источнике — без сети и без GitHub. Настоящая реализация —
 * [GitHubApiImpl], а данные, которые инструмент получает, — это и есть [GitHubApi].
 */
interface GitHubApi {

    /**
     * Все репозитории, доступные владельцу токена, в порядке, который вернул GitHub.
     *
     * @throws GitHubApiException Токен не принят, прав нет, GitHub ответил не-2xx или сеть
     *         недоступна: это ошибка вызова инструмента, а не повод уронить сервер.
     */
    suspend fun repositories(): List<GitHubRepository>
}

/**
 * Обращение к GitHub не удалось.
 *
 * Отдельный тип, а не общий `Exception`: инструмент показывает текст ошибки пользователю,
 * и «сеть недоступна» должно отличаться от опечатки в коде. [status] — код ответа HTTP,
 * null — до GitHub вообще не дошло.
 */
class GitHubApiException(val status: Int?, message: String) : IllegalStateException(message)
