package com.osvin.aichallenge.mcp.github

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
import java.nio.file.attribute.PosixFilePermission.GROUP_READ
import java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
import java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OTHERS_READ
import java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Найденный доступ к GitHub: сам токен и откуда он взят.
 *
 * [source] — человекочитаемое имя источника, без секрета: оно уезжает и в отчёт инструмента,
 * и в текст ошибки, поэтому в нём не должно быть ничего, чего человек не увидел бы в подсказке.
 * Сам [token] не показывается нигде: его получает только тот, кто собирает заголовок
 * `Authorization`.
 */
data class GitHubAccess(val token: String, val source: String)

/**
 * Чем закончился поиск доступа.
 *
 * [Missing] несёт не только «нет», но и перечень проверенных мест: из него собирается подсказка
 * человеку — что именно посмотрели и что сделать. Голого «токена нет» тут быть не может: без
 * перечня мест человек не знает, куда смотреть, и подсказка превратилась бы в «что-то не так».
 */
sealed interface GitHubCredentialLookup {

    /** Доступ найден: [access] — токен и источник. */
    data class Found(val access: GitHubAccess) : GitHubCredentialLookup

    /**
     * Доступа нет: [tried] — что проверено и почему пусто, в порядке источников.
     *
     * Пометки в списке — слова, а не коды: «записи нет» человеку понятнее, чем код ответа
     * `security`; они же попадают и в подсказку, а не пересказываются там второй раз.
     */
    data class Missing(val tried: List<String>) : GitHubCredentialLookup
}

/**
 * Один источник доступа: как его назвать и что он отвечает.
 *
 * Источник — маленький объект, а не ветка в общем поиске: цепочка собирается из них списком,
 * и порядок списка — это и есть приоритет. Добавить или убрать место поиска тогда значит
 * поправить одну строку сборки ([defaultSources]), а не тронуть разбор цепочки.
 */
interface GitHubCredentialSource {

    /** Имя источника для человека: без токена, но с тем, что человеку нужно узнать. */
    val label: String

    /** Что нашлось: токен или строка для перечня проверенных мест. */
    fun lookup(): GitHubCredentialResult
}

/** Ответ одного источника: [Found] — токен, [Absent] — что записать в проверенные места. */
sealed interface GitHubCredentialResult {

    /** Источник дал токен. */
    data class Found(val token: String) : GitHubCredentialResult

    /**
     * Источника нет или он пуст: [place] — готовая строка перечня проверенных мест.
     *
     * Строка целиком, а не пометка к [GitHubCredentialSource.label], и это не мелочь: перечень
     * видит человек, а формулировки у источников разные. Склейка «имя: пометка» дала бы
     * «файл из GITHUB_TOKEN_FILE: не найден: /путь», а отказ команды — «команда ответила
     * кодом 44: security: SecKeychainSearchCopyNext: …»; и то и другое читается как выгрузка
     * из лога, а не как ответ на вопрос «где смотрели и что нашли».
     */
    data class Absent(val place: String) : GitHubCredentialResult
}

/**
 * Поиск доступа к GitHub по цепочке мест, где он может лежать на машине.
 *
 * Токен не спрашивают у человека заново, если он уже есть на машине: сервер инструментов берёт
 * готовый доступ — из переменной окружения, файла, связки ключей macOS или у `gh`/`git`. Порядок
 * источников — приоритет: раньше идёт то, что человек задал явно (переменная, файл по названному
 * им пути), позже — машинные соглашения (`gh auth token`, `git credential fill`), потому что
 * явно заданное сейчас должно побеждать настроенное когда-то для других целей.
 *
 * Успех кэшируется, отсутствие — нет, и это разные вещи. Найденный токен от удачного обращения
 * к GitHub не меняется, поэтому перезапускать ради него `gh` на каждом вызове инструмента
 * незачем. А отсутствие — состояние, которое человек тут же и исправит: сохранит токен и нажмёт
 * «подключить снова». Кэш отсутствия превратил бы это исправление в «перезапустите сервер».
 *
 * Поиск синхронный (`fun lookup`, а не `suspend`): он и есть цепочка блокирующих шагов — чтение
 * файлов и запуск команд. Асинхронность здесь ничего не дала бы, а вызывающий сам решает, на
 * каком диспетчере её крутить ([GitHubApiImpl] уводит поиск на диспетчер ввода-вывода).
 *
 * @param sources Места поиска в порядке приоритета.
 */
class GitHubCredentials(
    private val sources: List<GitHubCredentialSource> = defaultSources()
) {

    /** Найденное однажды: см. KDoc класса — отсутствие здесь намеренно не лежит. */
    private var found: GitHubAccess? = null

    /**
     * Ищет доступ: первое найденное значение и есть ответ.
     *
     * Источники перебираются до первого удачного: следующий по порядку — менее приоритетный
     * по определению, и спрашивать его, когда уже ответил предыдущий, значило бы переворачивать
     * порядок. Все неудачные попадают в [GitHubCredentialLookup.Missing.tried] — иначе человек
     * не узнал бы, где доступ искали.
     */
    fun lookup(): GitHubCredentialLookup {
        found?.let { return GitHubCredentialLookup.Found(it) }
        val tried = mutableListOf<String>()
        for (source in sources) {
            when (val result = source.lookup()) {
                is GitHubCredentialResult.Found -> {
                    val access = GitHubAccess(token = result.token, source = source.label)
                    found = access
                    return GitHubCredentialLookup.Found(access)
                }

                is GitHubCredentialResult.Absent -> tried += result.place
            }
        }
        return GitHubCredentialLookup.Missing(tried)
    }
}

/**
 * Подсказка человеку, когда доступа нет: что проверено и что сделать.
 *
 * Строится из [tried] — перечня проверенных мест, — а не из второго списка: подсказка, собранная
 * отдельно, разошлась бы с тем, что сервер проверял, и человек читал бы не про своё. Токена
 * в подсказке нет и взяться ему неоткуда: в [tried] только имена мест и пометки.
 *
 * Способов четыре, и перечислены все: подсказка показывается в интерфейсе дословно, и человеку
 * нужен тот, который подходит его машине, — переменная для запуска командой, файл для машины
 * без хранилищ, связка ключей для macOS (самый закрытый способ из готовых) и `gh` для тех,
 * у кого он уже есть. Один совет на все случаи был бы либо невыполним, либо не про этот случай.
 * Команды приведены целиком: по одной подсказке «сохраните в связке ключей» человек пошёл бы
 * искать, как это делается.
 */
fun missingAccessHint(tried: List<String>): String = buildString {
    append("доступ к GitHub не найден: среди готовых доступов машины токена нет. ")
    append("Проверено — ")
    append(tried.joinToString("; "))
    append(". Что сделать (любой из способов): export ${GitHubConfig.TOKEN_ENV}=<токен>; ")
    append("сохранить токен в файле ${GitHubConfig.defaultTokenFile().abbreviated()} ")
    append("с правами 600 (chmod 600); положить его в связку ключей macOS командой ")
    append("security add-generic-password -a \"${'$'}USER\" -s ${GitHubConfig.KEYCHAIN_SERVICE} -w; ")
    append("или войти один раз командой `gh auth login`.")
}

/**
 * Путь глазами человека: домашний каталог сокращается до `~`.
 *
 * Сокращение не косметика: полный путь в ответе инструмента — это имя пользователя в выводе,
 * который читают и агент, и экран, а `~` показывает то же самое, не называя дом целиком.
 */
fun Path.abbreviated(): String {
    val home = GitHubConfig.homeDirectory().toString()
    val full = toString()
    return when {
        full == home -> "~"
        full.startsWith("$home/") -> "~" + full.removePrefix(home)
        else -> full
    }
}

/**
 * Источник из переменной окружения: то, что человек задаёт запуском.
 *
 * Значение обрезается по краям: переменные задают и через `export`, и через конфиг клиента,
 * а перевод строки в конце — не часть токена. Пустое значение — «источника нет», а не пустой
 * токен: заголовок `Authorization: Bearer ` GitHub отверг бы, и человек читал бы про 401
 * вместо того, что переменная пуста.
 *
 * @param env Чтение окружения; по умолчанию — окружение процесса. Параметр, а не прямой
 *        `System.getenv`, чтобы проверки задавали окружение поддельным, не трогая процесс.
 */
class EnvironmentTokenSource(
    private val env: (String) -> String? = System::getenv
) : GitHubCredentialSource {

    override val label = "переменная окружения ${GitHubConfig.TOKEN_ENV}"

    override fun lookup(): GitHubCredentialResult =
        env(GitHubConfig.TOKEN_ENV)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { GitHubCredentialResult.Found(it) }
            ?: GitHubCredentialResult.Absent("$label — не задана")
}

/**
 * Источник из файла: значение — всё содержимое, обрезанное по краям.
 *
 * В файл смотрят два места цепочки — путь из переменной окружения и путь по умолчанию, — поэтому
 * источник один, а различаются они только путём и тем, назван ли этот путь человеком. Права
 * проверяются до чтения: файл, открытый группе или остальным, — это токен, который видит кто
 * угодно в этой системе, и внятный отказ с `chmod 600` честнее молчаливого согласия с чужими
 * правами.
 *
 * @param path Путь к файлу с токеном.
 * @param namedByEnvironment Путь назван переменной [GitHubConfig.TOKEN_FILE_ENV]. Различие видно
 *        ровно в одном случае — когда файла нет: человек, назвавший путь сам, должен прочитать
 *        про свою переменную и свой путь, а не про «файла нет» вообще.
 */
class TokenFileSource(
    private val path: Path,
    private val namedByEnvironment: Boolean = false
) : GitHubCredentialSource {

    override val label = "файл ${path.abbreviated()}"

    override fun lookup(): GitHubCredentialResult {
        if (!Files.isRegularFile(path)) {
            return GitHubCredentialResult.Absent(missingPlace())
        }
        exposure(path)?.let { return GitHubCredentialResult.Absent("$label — $it") }
        val value = try {
            Files.readString(path).trim()
        } catch (error: IOException) {
            // Причину файловой системы человеку не показываем: она повторяет то, что и так
            // написано рядом — файл есть, а прочитать не вышло.
            return GitHubCredentialResult.Absent("$label — не читается")
        }
        return value.takeIf { it.isNotEmpty() }
            ?.let { GitHubCredentialResult.Found(it) }
            ?: GitHubCredentialResult.Absent("$label — файл пуст")
    }

    /** Строка перечня про отсутствующий файл: см. [namedByEnvironment]. */
    private fun missingPlace(): String = if (namedByEnvironment) {
        "файл из ${GitHubConfig.TOKEN_FILE_ENV} не найден: ${path.abbreviated()}"
    } else {
        "$label — файла нет"
    }
}

/**
 * Отказ по правам или null, если файл закрыт от чужих.
 *
 * Проверка есть только там, где биты POSIX вообще есть: на Windows их нет, и падать из-за этого
 * нельзя — там доступа группы и остальных не бывает. Сбой самой проверки (файловая система без
 * прав, недоступные атрибуты) тоже не повод отказывать: читать файл всё равно будем мы, и отказ
 * по несуществующей причине хуже, чем чтение.
 */
private fun exposure(path: Path): String? {
    val permissions = try {
        Files.getPosixFilePermissions(path)
    } catch (unsupported: UnsupportedOperationException) {
        return null
    } catch (error: IOException) {
        return null
    }
    val allowed = setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)
    val exposed = permissions - allowed
    return exposed.takeIf { it.isNotEmpty() }
        ?.let { "права ${permissions.octal()}: нужен chmod 600" }
}

/** Права в привычном виде (`600`, `644`): три триады — владелец, группа, остальные. */
private fun Set<PosixFilePermission>.octal(): String = listOf(
    listOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE),
    listOf(GROUP_READ, GROUP_WRITE, GROUP_EXECUTE),
    listOf(OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE)
).joinToString("") { (read, write, execute) ->
    val bits = (if (contains(read)) 4 else 0) +
        (if (contains(write)) 2 else 0) +
        (if (contains(execute)) 1 else 0)
    bits.toString()
}

/**
 * Источник из внешней команды: `security`, `gh` или `git`.
 *
 * Все три устроены одинаково — запустить программу, прочитать её вывод, вынуть из вывода
 * значение, — поэтому источник один, а различаются они командой и словами о неудаче. Запуск
 * ограничен нарочно: [timeout] не даёт команде висеть (человек ждёт ответа на нажатие кнопки,
 * а не зависший `git`), а отсутствие программы — не ошибка, а пропуск с пометкой: `gh` есть
 * далеко не на каждой машине, и это не значит, что доступ сломан.
 *
 * Чужой вывод в перечень проверенных мест не попадает, и это главное здесь. Отказ `security`
 * «SecKeychainSearchCopyNext: The specified item could not be found in the keychain» и отказ
 * `git` «could not read Username for 'https://github.com'» — не диагностика для человека,
 * а внутренние слова инструментов: их владелец прочитает как «что-то сломалось» там, где всё
 * в порядке. Поэтому у каждой команды есть [explain] — свои слова для её известных отказов,
 * — а чужая строка остаётся только тем отказам, которых мы не знали, и тогда она обрезается
 * и идёт одной строкой: вдруг в ней и правда написана причина.
 *
 * @param label Имя источника для человека.
 * @param command Команда: первым идёт программа, остальное — её аргументы. Программа ищется
 *        по заданному PATH ([resolveExecutable]), а не отдаётся на поиск `ProcessBuilder` —
 *        почему именно так, сказано там же.
 * @param environment Добавки к окружению команды: запрет интерактивных запросов и путь поиска
 *        программ. Именно добавки: команда наследует окружение сервера, иначе пришлось бы
 *        перечислять заново всё, что ей оттуда нужно.
 * @param input Что написать команде на вход; пусто — вход закрывается сразу (команда, читающая
 *        запрос до конца ввода, иначе ждала бы его вечно).
 * @param parse Токен из вывода команды или null, если значения в выводе нет.
 * @param missingProgram Что сказать, когда программы нет на машине: имя программы знает только
 *        вызывающий, и «gh не установлен» понятнее, чем «бинарник не найден».
 * @param explain Человеческие слова для известного отказа команды или null — тогда в перечень
 *        пойдёт её собственный ответ одной строкой.
 * @param timeout Сколько ждать команду.
 */
class ExternalCommandSource(
    override val label: String,
    private val command: List<String>,
    private val environment: Map<String, String> = emptyMap(),
    private val input: String = "",
    private val parse: (String) -> String?,
    private val missingProgram: String = "программы нет на машине",
    private val explain: (code: Int, errors: String) -> String? = { _, _ -> null },
    private val timeout: Duration = COMMAND_TIMEOUT
) : GitHubCredentialSource {

    override fun lookup(): GitHubCredentialResult = when (val outcome = runCommand()) {
        is CommandOutcome.Output -> parse(outcome.output)
            ?.let { GitHubCredentialResult.Found(it) }
            ?: GitHubCredentialResult.Absent("$label — команда не дала значения")

        is CommandOutcome.Failed -> GitHubCredentialResult.Absent(
            "$label — ${explain(outcome.code, outcome.errors) ?: outcome.oneLine()}"
        )

        CommandOutcome.NotInstalled -> GitHubCredentialResult.Absent("$label — $missingProgram")
        CommandOutcome.TimedOut ->
            GitHubCredentialResult.Absent("$label — команда не ответила за ${timeout.inWholeSeconds} с")
    }

    /**
     * Запуск команды с ограничениями.
     *
     * Вывод читается после выхода команды и целиком: команды цепочки отвечают парой строк, такая
     * труба не переполнится сама и не заблокирует команду, — а читать её в отдельном потоке
     * значило бы заводить поток и синхронизацию там, где ответ в одну строку. Отсюда же и запрет
     * читать вывод у не ответившей команды: её гасят, а не ждут её байтов.
     */
    private fun runCommand(): CommandOutcome {
        val executable = resolveExecutable(command.first(), environment[PATH_ENV])
            ?: return CommandOutcome.NotInstalled
        val process = try {
            ProcessBuilder(listOf(executable) + command.drop(1))
                .apply { environment().putAll(environment) }
                .start()
        } catch (error: IOException) {
            // Программа есть, но не запускается: для цепочки это то же самое — источника нет.
            return CommandOutcome.NotInstalled
        }
        try {
            process.outputStream.use { it.write(input.toByteArray()) }
        } catch (error: IOException) {
            // Команда закрыла вход, не прочитав его: ответ всё равно читаем дальше.
        }
        return try {
            // Не ответившую команду гасит finally: читать её вывод некому и незачем.
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                return CommandOutcome.TimedOut
            }
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            if (process.exitValue() == 0) {
                CommandOutcome.Output(output)
            } else {
                CommandOutcome.Failed(
                    code = process.exitValue(),
                    errors = process.errorStream.readBytes().toString(Charsets.UTF_8)
                )
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** Чем закончился запуск внешней команды. */
private sealed interface CommandOutcome {

    /** Команда ответила кодом 0: [output] — её стандартный вывод. */
    data class Output(val output: String) : CommandOutcome

    /** Команда ответила не нулём: [code] и её поток ошибок. */
    data class Failed(val code: Int, val errors: String) : CommandOutcome {
        /** Ответ команды в одну строку — на случай, когда своих слов для него не нашлось. */
        fun oneLine(): String = "команда ответила кодом $code: ${errors.oneLine()}"
    }

    /** Программы нет на машине или она не запускается. */
    data object NotInstalled : CommandOutcome

    /** Команда не ответила за отведённое время и была погашена. */
    data object TimedOut : CommandOutcome
}

/**
 * Чужой отказ в одну строку: он остаётся только там, где своих слов не нашлось.
 *
 * Переводы строк здесь не украшение: `git` печатает отказ двумя строками, и в подсказке он
 * разъехался бы на два абзаца посреди предложения. Длина тоже ограничена — [ERROR_LIMIT]:
 * человеку нужна причина, а не весь вывод команды.
 */
private fun String.oneLine(): String = replace(WHITESPACE, " ").trim().take(ERROR_LIMIT)

/** Пробельные последовательности: их и схлопываем в [oneLine]. */
private val WHITESPACE = Regex("\\s+")

/**
 * Цепочка источников по умолчанию — в порядке приоритета.
 *
 * Порядок не случаен: сначала то, что человек задал явно (переменная окружения, файл по
 * названному им пути), потом системное хранилище (связка ключей macOS) и путь по умолчанию,
 * и лишь затем машинные соглашения (`gh`, `git`). Явное раньше машинного — иначе токен,
 * оставленный когда-то для `gh`, побеждал бы тот, который человек задал сейчас.
 *
 * Про `git credential fill` и `gh auth token` важная оговорка: оба умеют спрашивать пароль
 * или подтверждение, а сервер инструментов работает без терминала, и такой вопрос стал бы
 * молчаливым зависанием на 10 с. Поэтому запуск идёт с `GIT_TERMINAL_PROMPT=0`,
 * `credential.interactive=false` и `GH_PROMPT_DISABLED=1`: у команды должен быть только
 * «готовый» путь ответа, без диалога.
 *
 * @param config Настройки: адрес API тут не нужен, а путь из [GitHubConfig.TOKEN_FILE_ENV] —
 *        нужен, и он же решает, смотрит ли цепочка в этот файл вообще.
 * @param env Чтение окружения; по умолчанию — окружение процесса. Через него задаётся всё
 *        машинное: значение токена, дом для файла по умолчанию и путь поиска программ. Отсюда
 *        и подмена машины в проверках — и она же способ закрыть машинные источники целиком
 *        (пустой `PATH`, пустой дом), когда нужен детерминированный «доступа нет» на машине,
 *        где доступ есть.
 */
fun defaultSources(
    config: GitHubConfig = GitHubConfig.fromEnvironment(),
    env: (String) -> String? = System::getenv
): List<GitHubCredentialSource> = buildList {
    add(EnvironmentTokenSource(env))
    // Файл по названному пути — только если путь назван: отсутствие переменной означает
    // «у меня не там», а не «возьми путь по умолчанию» — за это отвечает источник ниже.
    config.tokenFile?.let { add(TokenFileSource(it, namedByEnvironment = true)) }
    add(keychainSource(env))
    add(TokenFileSource(GitHubConfig.defaultTokenFile(GitHubConfig.homeDirectory(env))))
    add(ghTokenSource(env))
    add(gitCredentialSource(env))
}

/**
 * Связка ключей macOS: `security find-generic-password -s <служба> -w` печатает пароль.
 *
 * Код 44 у `security` — не поломка, а ответ «такой записи в связке нет»: у человека, который
 * ей не пользовался, это обычное дело, и пересказывать ему `SecKeychainSearchCopyNext` значит
 * пугать его там, где ничего не сломалось.
 */
private fun keychainSource(env: (String) -> String?): GitHubCredentialSource = ExternalCommandSource(
    label = "связка ключей macOS",
    command = listOf(
        "security", "find-generic-password", "-s", GitHubConfig.KEYCHAIN_SERVICE, "-w"
    ),
    environment = commandEnvironment(env),
    parse = { it.trim().takeIf { value -> value.isNotEmpty() } },
    explain = { code, errors ->
        if (code == KEYCHAIN_ITEM_NOT_FOUND || "could not be found" in errors) {
            "записи нет (security find-generic-password)"
        } else {
            null
        }
    }
)

/**
 * `gh auth token` — токен, которым уже вошёл сам `gh`.
 *
 * `GH_PROMPT_DISABLED=1` в окружении: `gh` умеет спрашивать подтверждения, а источник ищет
 * готовый доступ и вопросов задавать не должен — иначе нажатие кнопки подключения зависало бы
 * на невидимом диалоге.
 *
 * Отказ `gh` — это почти всегда «не вошёл»: так он отвечает и без сохранённого входа, и с
 * отозванным токеном. Своими словами это и сказано, а причина в другом месте осталась бы
 * загадкой для того, у кого `gh` просто не настроен.
 */
private fun ghTokenSource(env: (String) -> String?): GitHubCredentialSource = ExternalCommandSource(
    label = "gh auth token",
    command = listOf("gh", "auth", "token"),
    environment = commandEnvironment(env, "GH_PROMPT_DISABLED" to "1"),
    parse = { it.trim().takeIf { value -> value.isNotEmpty() } },
    missingProgram = "gh не установлен",
    explain = { _, errors ->
        if (GH_UNAUTHORIZED_MARKERS.any { it in errors }) "gh не авторизован (нужен gh auth login)" else null
    }
)

/**
 * `git credential fill` — доступ, который `git` уже хранит для github.com.
 *
 * На вход уходит запрос `protocol=https`, `host=github.com`, а пустая строка его завершает:
 * `git` читает запрос до конца ввода, и без завершения команда ждала бы ещё. Поля запроса
 * повторяют разбор адреса: нас интересует вход в github.com по HTTPS, а не чужой хост, для
 * которого у человека тоже может лежать пароль.
 *
 * Ответ разбирается по строке `password=`: `git credential fill` печатает протокол, хост,
 * логин и пароль отдельными строками, и значение там одно — пароль.
 *
 * Отказ `git` означает, что готовых учётных данных для github.com у него нет, — и говорит это
 * он же словами про askpass и терминал, из которых человеку не нужно ничего.
 */
private fun gitCredentialSource(env: (String) -> String?): GitHubCredentialSource = ExternalCommandSource(
    label = "git credential fill",
    command = listOf("git", "-c", "credential.interactive=false", "credential", "fill"),
    environment = commandEnvironment(env, "GIT_TERMINAL_PROMPT" to "0"),
    input = "protocol=https\nhost=github.com\n\n",
    parse = { output ->
        output.lineSequence()
            .firstOrNull { it.startsWith(PASSWORD_PREFIX) }
            ?.removePrefix(PASSWORD_PREFIX)
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
    },
    missingProgram = "git не установлен",
    explain = { code, errors ->
        if (code == GIT_NO_CREDENTIALS || "could not read Username" in errors) {
            "готовых учётных данных для github.com нет"
        } else {
            null
        }
    }
)

/**
 * Окружение внешней команды: заданные добавки плюс путь поиска программ.
 *
 * Путь берётся из того же окружения, что и всё остальное, и кладётся в команду явно: программа
 * и так наследовала бы его от сервера, но тогда проверке нечем было бы подменить её скриптами.
 * На рабочем запуске значение то же самое, поэтому поведение не меняется.
 */
private fun commandEnvironment(
    env: (String) -> String?,
    vararg additions: Pair<String, String>
): Map<String, String> = buildMap {
    env(PATH_ENV)?.takeIf { it.isNotBlank() }?.let { put(PATH_ENV, it) }
    additions.forEach { (name, value) -> put(name, value) }
}

/**
 * Путь к программе по заданному PATH — абсолютный, если она там есть, или null, если её там нет.
 *
 * Ищем сами, а не отдаём поиск `ProcessBuilder`, и это не придирка: `ProcessBuilder` ищет
 * программу по PATH того процесса, который её запускает, а не по тому, который мы задали
 * команде. Из-за этого подменить PATH цепочке было бы нельзя — ни проверке, которой нужен
 * детерминированный «доступа нет» на машине, где доступ есть, ни человеку, который хочет
 * закрыть машинные источники. Поиск по PATH — это несколько строк, зато PATH становится
 * настоящим: что в нём лежит, то команда и запускает.
 *
 * Программа с косой чертой берётся как есть: путь уже назван, искать нечего. Если PATH неизвестен
 * вовсе, программа тоже берётся как есть — пусть её ищет тот, кто запускает: молча объявить
 * «программы нет» из-за отсутствующей переменной значило бы соврать о причине.
 */
private fun resolveExecutable(program: String, path: String?): String? {
    if (program.contains('/')) return program
    if (path.isNullOrBlank()) return program
    return path.split(File.pathSeparator)
        .filter { it.isNotEmpty() }
        .map { File(it, program) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
}

/** Переменная окружения с путём поиска программ: команда запускается именно им. */
private const val PATH_ENV = "PATH"

/** Префикс строки с паролем в ответе `git credential fill`. */
private const val PASSWORD_PREFIX = "password="

/** Код `security`, которым связка отвечает «такой записи нет». */
private const val KEYCHAIN_ITEM_NOT_FOUND = 44

/** Код `git`, которым он отвечает «спросить учётные данные негде». */
private const val GIT_NO_CREDENTIALS = 128

/**
 * Слова `gh`, по которым видно, что он не вошёл, а не что-то сломал.
 *
 * Их несколько, потому что своих формулировок у `gh` тоже несколько: и «no oauth token»,
 * и приглашение выполнить `gh auth login` — это один и тот же случай «готового входа нет».
 * Список — догадка о чужом тексте, но она безопасна: не узнав отказа, цепочка покажет его
 * как есть, одной обрезанной строкой.
 */
private val GH_UNAUTHORIZED_MARKERS =
    listOf("no oauth token", "not logged in", "authentication required", "gh auth login", "GH_TOKEN")

/** Сколько ждать внешнюю команду: дольше человек у кнопки подключения не ждёт. */
private val COMMAND_TIMEOUT = 10.seconds

/** Сколько символов чужого отказа показать: в подсказке это строка, а не простыня. */
private const val ERROR_LIMIT = 120
