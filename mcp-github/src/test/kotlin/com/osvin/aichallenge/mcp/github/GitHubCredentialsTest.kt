package com.osvin.aichallenge.mcp.github

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Цепочка источников доступа: каждое место по отдельности, приоритет и подсказка.
 *
 * Машина здесь поддельная целиком: дом и путь поиска бинарников — временные каталоги, внешние
 * команды — shell-скрипты, написанные из Kotlin, файлы токенов — с выставленными правами.
 * Настоящее окружение не читается, поэтому проверка не зависит от того, вошёл ли человек
 * в GitHub на этой машине и есть ли у него `gh`.
 *
 * Проверяется не «мы умеем читать переменную», а поведение цепочки: что она доходит до нужного
 * места, что явное побеждает машинное, что чужой файл отвергается с подсказкой и что ни в одном
 * перечне проверенных мест нет значения токена.
 */
class GitHubCredentialsTest {

    @Test
    fun `переменная окружения отвечает первой`() {
        Fixture().use { machine ->
            machine.set(GitHubConfig.TOKEN_ENV, TEST_TOKEN)
            machine.defaultTokenFile("другой-token")

            val access = machine.credentials().lookup().found()

            assertEquals(TEST_TOKEN, access.token)
            assertEquals("переменная окружения ${GitHubConfig.TOKEN_ENV}", access.source)
        }
    }

    @Test
    fun `значение переменной обрезается по краям`() {
        Fixture().use { machine ->
            machine.set(GitHubConfig.TOKEN_ENV, "  $TEST_TOKEN\n")

            assertEquals(TEST_TOKEN, machine.credentials().lookup().found().token)
        }
    }

    @Test
    fun `пустая переменная — это отсутствие источника, а не пустой токен`() {
        Fixture().use { machine ->
            machine.set(GitHubConfig.TOKEN_ENV, "   ")
            machine.defaultTokenFile(TEST_TOKEN)

            assertEquals(TEST_TOKEN, machine.credentials().lookup().found().token)
        }
    }

    @Test
    fun `файл из GITHUB_TOKEN_FILE читается и обрезается`() {
        Fixture().use { machine ->
            val named = machine.tokenFile(machine.root.resolve("token"), "\n$TEST_TOKEN\r\n")

            val access = machine.credentials(GitHubConfig(tokenFile = named)).lookup().found()

            assertEquals(TEST_TOKEN, access.token)
            assertEquals("файл $named", access.source)
        }
    }

    @Test
    fun `файл из переменной побеждает связку ключей и путь по умолчанию`() {
        Fixture().use { machine ->
            val named = machine.tokenFile(machine.root.resolve("named.token"), TEST_TOKEN)
            machine.script("security") { "echo \"другой-token\"" }
            machine.defaultTokenFile("третий-token")

            val access = machine.credentials(GitHubConfig(tokenFile = named)).lookup().found()

            assertEquals("файл $named", access.source)
        }
    }

    @Test
    fun `связка ключей побеждает файл по умолчанию`() {
        Fixture().use { machine ->
            machine.script("security") { "echo \"$TEST_TOKEN\"" }
            machine.defaultTokenFile("другой-token")

            val access = machine.credentials().lookup().found()

            assertEquals(TEST_TOKEN, access.token)
            assertEquals("связка ключей macOS", access.source)
        }
    }

    @Test
    fun `файл по умолчанию побеждает gh`() {
        Fixture().use { machine ->
            machine.script("gh") { "echo \"другой-token\"" }
            machine.defaultTokenFile(TEST_TOKEN)

            val access = machine.credentials().lookup().found()

            assertEquals(TEST_TOKEN, access.token)
            assertTrue(
                access.source.endsWith(".config/ai-challenge/github.token"),
                "ответил не файл по умолчанию: ${access.source}"
            )
        }
    }

    @Test
    fun `gh побеждает git`() {
        Fixture().use { machine ->
            machine.script("gh") { "echo \"$TEST_TOKEN\"" }
            machine.script("git") { "echo \"password=другой-token\"" }

            assertEquals("gh auth token", machine.credentials().lookup().found().source)
        }
    }

    @Test
    fun `файл по умолчанию берётся из HOME`() {
        Fixture().use { machine ->
            machine.defaultTokenFile(TEST_TOKEN)

            assertEquals(TEST_TOKEN, machine.credentials().lookup().found().token)
            assertEquals(
                machine.home.resolve(".config/ai-challenge/github.token"),
                GitHubConfig.defaultTokenFile(GitHubConfig.homeDirectory(machine::env))
            )
        }
    }

    @Test
    fun `файл, открытый группе или остальным, отвергается с подсказкой`() {
        Fixture().use { machine ->
            val path = machine.defaultTokenFile(TEST_TOKEN, mode = "rw-r--r--")

            val tried = placesOf(machine.credentials().lookup())

            assertTrue("права 644: нужен chmod 600" in tried, "права названы не по-человечески: $tried")
            assertTrue(path.toString() in tried, "не сказано, о каком файле речь: $tried")
            assertTrue(TEST_TOKEN !in tried, "значение токена попало в перечень проверенных мест")
        }
    }

    @Test
    fun `файл, закрытый от чужих, читается`() {
        Fixture().use { machine ->
            machine.defaultTokenFile(TEST_TOKEN, mode = "rw-------")

            assertEquals(TEST_TOKEN, machine.credentials().lookup().found().token)
        }
    }

    @Test
    fun `пустой файл — источника нет`() {
        Fixture().use { machine ->
            machine.defaultTokenFile("\n")

            val tried = placesOf(machine.credentials().lookup())

            assertTrue("файл пуст" in tried, "пустой файл не назван: $tried")
        }
    }

    @Test
    fun `файла нет — так и написано`() {
        Fixture().use { machine ->
            val tried = placesOf(machine.credentials().lookup())

            assertTrue("файла нет" in tried, "отсутствие файла не названо: $tried")
            assertTrue("не задана" in tried, "отсутствие переменной не названо: $tried")
        }
    }

    @Test
    fun `незаданный GITHUB_TOKEN_FILE не проверяется`() {
        Fixture().use { machine ->
            val named = machine.tokenFile(machine.root.resolve("мимо.token"), TEST_TOKEN)

            val tried = placesOf(machine.credentials().lookup())

            assertFalse(named.toString() in tried, "файл по незаданному пути всё же проверялся: $tried")
        }
    }

    @Test
    fun `отсутствующий бинарник — пропуск с пометкой, а не ошибка`() {
        Fixture().use { machine ->
            val tried = placesOf(machine.credentials().lookup())

            listOf(
                "связка ключей macOS — программы нет на машине",
                "gh auth token — gh не установлен",
                "git credential fill — git не установлен"
            ).forEach { place ->
                assertTrue(place in tried, "нет честной пометки «$place»: $tried")
            }
        }
    }

    @Test
    fun `закрытая связка ключей — записи нет, а не отказ security`() {
        Fixture().use { machine ->
            machine.script("security") {
                "echo \"The specified item could not be found in the keychain.\" >&2\n exit 44"
            }

            val tried = placesOf(machine.credentials().lookup())

            assertTrue(
                "связка ключей macOS — записи нет (security find-generic-password)" in tried,
                "нет человеческой пометки: $tried"
            )
            assertTrue("SecKeychainSearchCopyNext" !in tried, "в перечень попал чужой текст security: $tried")
        }
    }

    @Test
    fun `gh без входа — сказано, что делать`() {
        Fixture().use { machine ->
            machine.script("gh") {
                "echo \"To get started with GitHub CLI, please run: gh auth login\" >&2\n exit 4"
            }

            val tried = placesOf(machine.credentials().lookup())

            assertTrue(
                "gh auth token — gh не авторизован (нужен gh auth login)" in tried,
                "нет человеческой пометки: $tried"
            )
        }
    }

    @Test
    fun `внешние команды запускаются без диалога и с запросом на github`() {
        Fixture().use { machine ->
            val stdin = machine.root.resolve("git-stdin.txt")
            val guards = machine.root.resolve("git-guards.txt")
            machine.script("git") {
                """
                /bin/cat > "$stdin"
                echo "${'$'}GIT_TERMINAL_PROMPT ${'$'}*" > "$guards"
                echo "protocol=https"
                echo "host=github.com"
                echo "username=octo"
                echo "password=$TEST_TOKEN"
                """.trimIndent()
            }
            machine.script("gh") { "echo \"${'$'}GH_PROMPT_DISABLED\" > \"${machine.root.resolve("gh-guard.txt")}\"; exit 1" }

            val access = machine.credentials().lookup().found()

            assertEquals("git credential fill", access.source)
            assertEquals("protocol=https\nhost=github.com\n\n", Files.readString(stdin))
            val guardLine = Files.readString(guards)
            assertTrue(guardLine.startsWith("0 "), "диалог git не запрещён: $guardLine")
            assertTrue("credential.interactive=false" in guardLine, "нет запрета интерактива: $guardLine")
            assertEquals("1", Files.readString(machine.root.resolve("gh-guard.txt")).trim())
        }
    }

    @Test
    fun `токен из чужого вывода не попадает в перечень проверенных мест`() {
        Fixture().use { machine ->
            machine.script("git") {
                "echo \"password=$TEST_TOKEN\"\nprintf 'первая причина\\nвторая причина\\n' >&2\nexit 1"
            }

            val tried = placesOf(machine.credentials().lookup())

            assertTrue("первая причина вторая причина" in tried, "незнакомый отказ не показан: $tried")
            assertFalse("\n" in tried, "в перечень проверенных мест попал перевод строки: $tried")
            assertTrue(TEST_TOKEN !in tried, "значение токена попало в перечень проверенных мест")
        }
    }

    @Test
    fun `git без сохранённых учётных данных — сказано словами`() {
        Fixture().use { machine ->
            machine.script("git") {
                "echo \"fatal: could not read Username for 'https://github.com': terminal prompts disabled\" >&2\n exit 128"
            }

            val tried = placesOf(machine.credentials().lookup())

            assertTrue(
                "git credential fill — готовых учётных данных для github.com нет" in tried,
                "нет человеческой пометки: $tried"
            )
            assertTrue("askpass" !in tried && "terminal prompts" !in tried, "в перечень попал чужой текст git: $tried")
        }
    }

    @Test
    fun `путь из GITHUB_TOKEN_FILE назван, даже когда файла нет`() {
        Fixture().use { machine ->
            val named = machine.root.resolve("нет-такого.token")

            val tried = placesOf(machine.credentials(GitHubConfig(tokenFile = named)).lookup())

            assertTrue(
                "файл из ${GitHubConfig.TOKEN_FILE_ENV} не найден: $named" in tried,
                "про переменную и путь не сказано: $tried"
            )
        }
    }

    @Test
    fun `команда, не ответившая вовремя, пропускается`() {
        Fixture().use { machine ->
            val source = ExternalCommandSource(
                label = "медленная команда",
                command = listOf(machine.script("slow") { "exec /bin/sleep 5" }.toString()),
                environment = mapOf("PATH" to machine.bin.toString()),
                parse = { it.trim().takeIf { value -> value.isNotEmpty() } },
                timeout = 300.milliseconds
            )

            val tried = placesOf(GitHubCredentials(listOf(source)).lookup())

            assertTrue("не ответила" in tried, "таймаут назван не словами: $tried")
        }
    }

    @Test
    fun `найденный доступ кэшируется`() {
        var calls = 0
        val counting = object : GitHubCredentialSource {
            override val label = "счётный источник"
            override fun lookup(): GitHubCredentialResult {
                calls++
                return GitHubCredentialResult.Found(TEST_TOKEN)
            }
        }
        val credentials = GitHubCredentials(listOf(counting))

        assertEquals(TEST_TOKEN, credentials.lookup().found().token)
        assertEquals(TEST_TOKEN, credentials.lookup().found().token)

        assertEquals(1, calls, "источник спрашивали повторно, хотя доступ уже найден")
    }

    @Test
    fun `отсутствие доступа не кэшируется`() {
        Fixture().use { machine ->
            val path = machine.home.resolve(".config/ai-challenge/github.token")
            val credentials = GitHubCredentials(listOf(TokenFileSource(path)))

            placesOf(credentials.lookup())

            machine.defaultTokenFile(TEST_TOKEN)

            assertEquals(
                TEST_TOKEN,
                credentials.lookup().found().token,
                "появившийся токен не найден: отсутствие закэшировалось"
            )
        }
    }

    @Test
    fun `подсказка собирается из проверенных мест и называет все способы`() {
        Fixture().use { machine ->
            val places = missingOf(machine.credentials().lookup()).tried

            val hint = missingAccessHint(places)

            places.forEach { place -> assertTrue(place in hint, "место «$place» не попало в подсказку") }
            listOf(
                "Проверено",
                "export ${GitHubConfig.TOKEN_ENV}=<токен>",
                GitHubConfig.defaultTokenFile().abbreviated(),
                "chmod 600",
                "security add-generic-password -a \"\$USER\" -s ${GitHubConfig.KEYCHAIN_SERVICE} -w",
                "gh auth login"
            ).forEach { way -> assertTrue(way in hint, "в подсказке нет «$way»: $hint") }
            assertTrue("\n" !in hint, "подсказка показывается дословно и должна быть одной строкой: $hint")
            assertTrue(TEST_TOKEN !in hint, "значение токена попало в подсказку")
        }
    }

    /**
     * Поддельная машина: временный дом, временный путь поиска бинарников и карта окружения.
     *
     * Дом и путь — параметры, а не наследие процесса: настоящая цепочка читает их из окружения
     * ([GitHubConfig.homeDirectory], `PATH` у внешних команд), поэтому подмена окружения
     * подменяет и их. Каталог удаляется за собой ([use]): временные файлы не должны копиться
     * между прогонами.
     */
    private class Fixture : Closeable {

        val root: Path = Files.createTempDirectory("github-credentials")
        val home: Path = root.resolve("home").createDirectories()
        val bin: Path = root.resolve("bin").createDirectories()

        private val values = linkedMapOf(
            GitHubConfig.HOME_ENV to home.toString(),
            "PATH" to bin.toString()
        )

        /** Чтение поддельного окружения: то же, что [System.getenv], но без процесса. */
        fun env(name: String): String? = values[name]

        fun set(name: String, value: String) {
            values[name] = value
        }

        /** Поддельный бинарник: shell-скрипт, созданный из Kotlin, как и всё остальное здесь. */
        fun script(name: String, body: () -> String): Path {
            val file = bin.resolve(name)
            Files.writeString(file, "#!/bin/sh\n${body()}\n")
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
            return file
        }

        /** Файл токена с выставленными правами: `rw-------` — как надо, `rw-r--r--` — как нельзя. */
        fun tokenFile(path: Path, content: String, mode: String = "rw-------"): Path {
            path.parent.createDirectories()
            Files.writeString(path, content)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
            return path
        }

        /** Файл по умолчанию в поддельном доме — тот самый `<дом>/.config/ai-challenge/...`. */
        fun defaultTokenFile(content: String, mode: String = "rw-------"): Path = tokenFile(
            home.resolve(".config/ai-challenge/github.token"),
            content,
            mode
        )

        /** Цепочка по умолчанию на поддельном окружении — та же, что на рабочей машине. */
        fun credentials(config: GitHubConfig = GitHubConfig()): GitHubCredentials =
            GitHubCredentials(defaultSources(config, ::env))

        override fun close() {
            root.toFile().deleteRecursively()
        }
    }
}

/** Доступ найден — или проверка падает: молчаливое «нет» скрыло бы причину. */
private fun GitHubCredentialLookup.found(): GitHubAccess = when (this) {
    is GitHubCredentialLookup.Found -> access
    is GitHubCredentialLookup.Missing -> error("доступа нет, проверено: $tried")
}

/** Итог поиска, в котором доступа нет: найденный доступ здесь — ошибка проверки. */
private fun missingOf(lookup: GitHubCredentialLookup): GitHubCredentialLookup.Missing = when (lookup) {
    is GitHubCredentialLookup.Missing -> lookup
    is GitHubCredentialLookup.Found ->
        error("доступ нашёлся (${lookup.access.source}), а ожидалось «доступа нет»")
}

/** Перечень проверенных мест одной строкой: так его читает человек в подсказке. */
private fun placesOf(lookup: GitHubCredentialLookup): String =
    missingOf(lookup).tried.joinToString("; ")
