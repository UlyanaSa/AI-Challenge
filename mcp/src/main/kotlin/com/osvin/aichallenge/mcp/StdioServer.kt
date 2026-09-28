package com.osvin.aichallenge.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

/**
 * Флаг консольного режима: показать инструменты сервера и выйти.
 *
 * Отдельный режим нужен потому, что в рабочем режиме стандартный вывод сервера занят
 * протоколом ([stdoutForProtocol]): напечатать туда список инструментов значило бы послать
 * клиенту мусор вместо кадра. Поэтому «что у тебя есть» — это команда
 * (`java -cp mcp-1.0.0.jar … --list-tools`, а из репозитория `./gradlew :mcp:mcpTools`),
 * а подключение — запуск без аргументов.
 */
const val LIST_TOOLS_FLAG = "--list-tools"

/** Логи проекта: их пишем мы, поэтому они видны в потоке ошибок сервера. */
private const val PROJECT_LOGGER = "com.osvin.aichallenge"

/**
 * Точка входа MCP-сервера на stdio: два режима у одной команды.
 *
 * Без аргументов — сервер: он работает, пока клиент не закроет вход, и выходит вместе с ним.
 * С [LIST_TOOLS_FLAG] — ответ о своих инструментах и выход, без данных и без транспорта:
 * список берётся из объявлений ([tools]), поэтому поднимать сервер для него не нужно.
 * Неизвестный аргумент — отказ с кодом 2: опечатка (`--list-tool`) иначе подняла бы сервер,
 * который молча ждёт кадры из stdin, и человек решил бы, что команда зависла.
 *
 * @param args Аргументы процесса.
 * @param name Имя сервера в рукопожатии.
 * @param version Версия сервера в рукопожатии.
 * @param tools Объявления инструментов: их печатает консольный режим.
 * @param buildServer Сервер с инструментами, привязанными к данным процесса. Вызывается
 *        только в рабочем режиме: в консольном данные не нужны.
 */
fun runStdioServer(
    args: Array<String>,
    name: String,
    version: String,
    tools: List<ToolDeclaration>,
    buildServer: () -> Server
): Unit = runBlocking {
    when {
        // `singleOrNull`, а не сравнение с `listOf(флаг)`: массив и список не равны никогда.
        args.singleOrNull() == LIST_TOOLS_FLAG -> {
            println(describeTools(name, version, tools))
            return@runBlocking
        }

        args.isNotEmpty() -> {
            System.err.println(
                "неизвестные аргументы: ${args.joinToString(" ")}; поддерживается только " +
                    "$LIST_TOOLS_FLAG, без аргументов — сервер на stdio"
            )
            exitProcess(2)
        }
    }

    // Вывод забирается под протокол до сборки сервера, а не после: сборка — это регистрация
    // инструментов и первые обращения к логгерам SDK, и они печатают в стандартный вывод
    // («kotlin-logging: initializing…», «Adding Tool: …»). Эти строки уехали бы клиенту
    // вместо кадров: клиент разбирает вывод построчно и на чужой строке теряет её, а если
    // она придёт между вопросом и ответом — потеряет ответ. Проверено: без этого порядка
    // клиент печатал «Failed to deserialize message from line: … Adding Tool …».
    val protocolOutput = stdoutForProtocol()
    logsToStderr()

    serveStdio(protocolOutput, buildServer())
}

/**
 * Сервер на stdio: работает, пока клиент не закроет соединение.
 *
 * Ни порта, ни регистрации нет — сервер поднимает клиент процессом (как и большинство
 * MCP-серверов), поэтому запуск сводится к команде, а конец работы — к концу входного
 * потока: закрывая соединение, клиент закрывает и сервер.
 *
 * Что осталось позади: транспорт при естественной остановке сам закрывает вход и выход,
 * снимает свои корутины и зовёт `onClose` — поэтому здесь достаточно дождаться этой
 * остановки. `Server.close()` после неё не зовётся намеренно: он первым делом ждёт службу
 * уведомлений, а та после остановки транспорта завершается через раз — уведомление о конце
 * (`EndEvent`) шлётся в поток без буфера и висит, если подписчик занят отправкой в уже
 * остановленный транспорт (гонка в SDK 0.15.0). Проверено на пяти прогонах: с закрытием
 * процесс висел в трёх, без него выходит всегда.
 *
 * @param protocolOutput Вывод, забранный под протокол **до** сборки сервера:
 *        забрать его в этой функции было бы поздно — к моменту вызова первые строки
 *        библиотек уже уехали бы в настоящий стандартный вывод.
 */
private suspend fun serveStdio(protocolOutput: java.io.PrintStream, server: Server) {
    val closed = CompletableDeferred<Unit>()
    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = protocolOutput.asSink().buffered()
    )
    transport.onClose { closed.complete(Unit) }
    server.createSession(transport)
    closed.await()
}

/**
 * Забирает стандартный вывод под протокол: печатать в него больше нельзя никому.
 *
 * Кадр JSON-RPC — это строка в общем выводе, поэтому одна чужая строка (`println` из
 * библиотеки, приветствие логгера) ломает кадр: клиент разбирает её как сообщение
 * протокола и теряет ответ. Забрать вывод у процесса целиком надёжнее, чем надеяться,
 * что печатать в него станут только мы: печать уводится в поток ошибок, а транспорт
 * получает прежний вывод — тот самый, который видит клиент.
 */
private fun stdoutForProtocol(): java.io.PrintStream {
    val protocol = System.out
    System.setOut(System.err)
    return protocol
}

/**
 * Логи сервера уводит в поток ошибок: стандартный вывод занят протоколом.
 *
 * Уровень поднят с `trace` до `warn` из `logback.xml`: у клиента есть свой разбор протокола,
 * поэтому в поток ошибок сервер пишет то, что случилось, а не каждый шаг рукопожатия —
 * шаги SDK видны по требованию, правкой уровня. Свои сообщения ([PROJECT_LOGGER]) остаются
 * на `info`: их пишем мы, и их в потоке ошибок ищут.
 */
private fun logsToStderr() {
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    val appender = ConsoleAppender<ILoggingEvent>().apply {
        name = "MCP-STDERR"
        target = "System.err"
        encoder = PatternLayoutEncoder().apply {
            this.context = context
            pattern = "%d{HH:mm:ss.SSS} %-5level %logger{20} - %msg%n"
            start()
        }
        this.context = context
        start()
    }
    root.detachAndStopAllAppenders()
    root.level = Level.WARN
    root.addAppender(appender)
    context.getLogger(PROJECT_LOGGER).level = Level.INFO
}
