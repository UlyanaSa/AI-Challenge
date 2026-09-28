package com.osvin.aichallenge.mcp.github

/**
 * Настройки доступа к GitHub: куда ходить и с каким токеном.
 *
 * Токен не спрашивается при старте сервера: сервер поднимают и для `--list-tools`, где GitHub
 * не нужен вовсе, и падать из-за отсутствия переменной окружения на старте значило бы запрещать
 * знакомство с инструментом до настройки. Поэтому токен читается в момент вызова и его
 * отсутствие — ошибка вызова ([requireToken]), а не запуска.
 *
 * Адрес API — параметр, а не константа: тесты поднимают подставной GitHub на localhost и
 * подменяют адрес переменной окружения, не трогая код сервера.
 *
 * @param apiBase Корень GitHub REST API; по умолчанию — публичный GitHub.
 * @param token Токен доступа или null, если переменной окружения нет.
 */
data class GitHubConfig(val apiBase: String = DEFAULT_API_BASE, val token: String? = null) {

    /**
     * Токен для заголовка `Authorization` или внятная ошибка.
     *
     * Исключение, а не null: вызывающему с отсутствием токена нечего делать, кроме как сообщить
     * о нём, а исключение несёт готовый текст и превращается в `isError` одним местом
     * ([getRepositoriesTool]). Возврат null означал бы проверку на каждом шаге обращения к API
     * — и рано или поздно забытую.
     */
    fun requireToken(): String = token ?: throw GitHubApiException(
        status = null,
        message = "переменная окружения $TOKEN_ENV не задана: сходить в GitHub нельзя, " +
            "положите токен доступа в $TOKEN_ENV перед запуском сервера"
    )

    companion object {

        /** Переменная окружения с токеном: в коде и на диске токена нет — только здесь. */
        const val TOKEN_ENV = "GITHUB_TOKEN"

        /** Переменная окружения с адресом API: тесты подменяют её подставным сервером. */
        const val API_BASE_ENV = "GITHUB_API_BASE"

        /** Публичный GitHub REST API — значение по умолчанию. */
        const val DEFAULT_API_BASE = "https://api.github.com"

        /**
         * Настройки из окружения процесса: сервер запускают командой, конфиг ему не пишут.
         *
         * Пустая переменная — то же, что отсутствующая: в конфиге клиента легко оказаться
         * `"GITHUB_TOKEN": ""`, и «пустой токен» ушёл бы в GitHub заголовком `Bearer `.
         */
        fun fromEnvironment(): GitHubConfig = GitHubConfig(
            apiBase = System.getenv(API_BASE_ENV)?.takeIf { it.isNotBlank() } ?: DEFAULT_API_BASE,
            token = System.getenv(TOKEN_ENV)?.takeIf { it.isNotBlank() }
        )
    }
}
