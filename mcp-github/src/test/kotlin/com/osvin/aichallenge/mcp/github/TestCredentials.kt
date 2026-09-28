package com.osvin.aichallenge.mcp.github

/** Токен подставных источников: настоящее значение в проверках не нужно и быть не должно. */
internal const val TEST_TOKEN = "test-token"

/** Имя подставного источника: по нему видно, что отчёт не подменяет место, откуда взят токен. */
internal const val TEST_SOURCE = "подставной источник"

/**
 * Цепочка доступа из одного источника — то, чем подменяют [GitHubCredentials] в проверках.
 *
 * Подставной источник вместо настоящего, потому что настоящий читал бы окружение и запускал
 * команды: проверка, зависящая от машины, на которой идёт, проходит или падает не по своей
 * вине. Порядок и разбор цепочки проверяются отдельно ([GitHubCredentialsTest]), а здесь нужен
 * только ответ «токен есть» или «токена нет».
 *
 * @param token Токен источника или null — тогда источник отвечает «не задана», и цепочка
 *        доходит до конца, как на машине без готового доступа.
 */
internal fun testCredentials(token: String? = TEST_TOKEN): GitHubCredentials = GitHubCredentials(
    listOf(
        object : GitHubCredentialSource {
            override val label = TEST_SOURCE

            override fun lookup(): GitHubCredentialResult = token
                ?.let { GitHubCredentialResult.Found(it) }
                ?: GitHubCredentialResult.Absent("не задана")
        }
    )
)
