package com.osvin.aichallenge.service

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Хранилище сессий в файле SQLite.
 *
 * Файл, а не память процесса: история чата — то, ради чего сервис вообще заведён, и после
 * перезапуска (а контейнер перезапускают при каждом обновлении образа) диалог должен
 * открываться тем же. В памяти пришлось бы либо мириться с потерей истории, либо держать
 * рядом второй механизм сохранения — два места на одни данные. Файл уже есть у соседнего
 * модуля курсов ([com.osvin.aichallenge.currency.storage.CurrencyDatabase]), и тот же
 * драйвер подключается в `:server` явно; Exposed или Room здесь не годятся: первый в проекте
 * не используется вовсе, второй — Android-библиотека, которой в JVM-модуле нет.
 *
 * Соединение одно на весь процесс и открывается в конструкторе. Одно — потому что SQLite
 * всё равно пускает на запись по одному писателю, и пул дал бы несколько дескрипторов к
 * одному файлу без выигрыша в параллелизме, зато с задачей «а согласованы ли соединения».
 * В конструкторе — потому что сервис без хранилища не должен подниматься: открытие
 * проверяет каталог, прагмы и схему, и отказ виден на старте, а не на первом вопросе
 * человека, когда чинить уже поздно.
 *
 * JDBC-соединение SQLite не потокобезопасно, поэтому каждое обращение (и чтение тоже) идёт
 * под одним [ReentrantLock]. Чтения под тем же замком, что и записи, — не перестраховка:
 * писатель использует незафиксированную транзакцию, и чтение через то же соединение внутри
 * неё показало бы чужие недописанные строки. Замок именно `ReentrantLock`, а не монитор
 * `synchronized`: он именованный и его область видна в тексте, а рекурсии здесь нет —
 * вложенных захватов не бывает.
 *
 * Прагмы задаются при открытии. `journal_mode=WAL` — чтобы читатель не ждал писателя внутри
 * процесса и чтобы файл переживал одновременное открытие вторым процессом (ручной осмотр
 * базы рядом с работающим сервисом). `busy_timeout=5000` — потому что WAL не отменяет
 * блокировку писателя писателем: второй процесс подождёт пять секунд, а не откажет сразу.
 * `foreign_keys=ON` — чтобы схема не осталась без ссылочной целостности молча, если её
 * когда-нибудь дополнят внешним ключом.
 *
 * @param file Файл базы; каталог создаётся при открытии.
 */
class SqliteChatStore(private val file: Path) : ChatSessionStore {

    /** Замок на соединение: без него две корутины писали бы в него одновременно. */
    private val lock = ReentrantLock()

    /** Источник идентификаторов: предсказуемый идентификатор позволил бы угадать чужой чат. */
    private val random = SecureRandom()

    private val connection: Connection = open()

    /** Закрыто ли хранилище: после закрытия обращения отвергаются, а не идут в закрытое соединение. */
    private var closed = false

    override fun create(title: String?, nowMillis: Long): ChatSession {
        val session = ChatSession(
            id = newId(),
            // Пустое имя — не отказ в создании: клиент может не знать, как назвать диалог,
            // и подставлять нечего, кроме умолчания.
            title = title?.takeIf { it.isNotBlank() } ?: ChatSession.DEFAULT_TITLE,
            createdAt = nowMillis,
            updatedAt = nowMillis
        )
        guarded("создание сессии") { connection ->
            connection.prepareStatement(INSERT_SESSION).use { statement ->
                statement.setString(1, session.id)
                statement.setString(2, session.title)
                statement.setLong(3, session.createdAt)
                statement.setLong(4, session.updatedAt)
                statement.executeUpdate()
            }
        }
        return session
    }

    override fun find(id: String): ChatSession? = guarded("поиск сессии") { connection ->
        connection.prepareStatement(SELECT_SESSION).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toSession() else null
            }
        }
    }

    override fun append(chatId: String, role: String, content: String, nowMillis: Long): StoredMessage =
        transaction("запись сообщения") { connection ->
            // Строка сообщения и время сессии пишутся одной транзакцией: половина этой пары —
            // сообщение, которого нет в списке сессий, или сессия, не помнящая последнего
            // сообщения, — состояние, из которого не видно, что произошло. Существование
            // сессии здесь не перепроверяется: вызывающий уже нашёл её в [find], а гонку двух
            // запросов к одной сессии разводит её мьютекс в маршруте.
            connection.prepareStatement(INSERT_MESSAGE).use { statement ->
                statement.setString(1, chatId)
                statement.setString(2, role)
                statement.setString(3, content)
                statement.setLong(4, nowMillis)
                statement.executeUpdate()
            }
            connection.prepareStatement(TOUCH_SESSION).use { statement ->
                statement.setLong(1, nowMillis)
                statement.setString(2, chatId)
                statement.executeUpdate()
            }
            StoredMessage(role = role, content = content, createdAt = nowMillis)
        }

    override fun messages(chatId: String): List<StoredMessage> = guarded("чтение сообщений") { connection ->
        // Порядок — по ключу строки, а не по её времени: два сообщения одного миллисекунда
        // (а пара «вопрос — ответ» в локальной модели и правда может уложиться в неё)
        // по времени неразличимы, и порядок диалога зависел бы от удачи сортировки.
        connection.prepareStatement(SELECT_MESSAGES).use { statement ->
            statement.setString(1, chatId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            StoredMessage(
                                role = rows.getString("role"),
                                content = rows.getString("content"),
                                createdAt = rows.getLong("created_at")
                            )
                        )
                    }
                }
            }
        }
    }

    override fun list(limit: Int): List<ChatSession> = guarded("список сессий") { connection ->
        // Число сообщений считается подзапросом в том же запросе, а не отдельным обращением
        // на чат: список показывает их у каждой строки сразу, и N+1 запросов к базе
        // на одну страницу панели — это ровно тот случай, когда SQLite держит замок
        // дольше, чем нужно.
        connection.prepareStatement(SELECT_SESSIONS).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(rows.toSession().copy(messageCount = rows.getInt(MESSAGE_COUNT)))
                    }
                }
            }
        }
    }

    override fun latestWithMessages(excluding: String): ChatSession? =
        guarded("поиск прошлого чата") { connection ->
            // Наличие сообщений проверяется существованием строки, а не `COUNT`: вопрос
            // «есть ли хоть одно» дешевле пересчёта всей истории чата. Сортировка — та же,
            // что у списка, плюс ключ строки: два чата одной миллисекунды обязаны
            // упорядочиваться одинаково в обоих чтениях.
            connection.prepareStatement(SELECT_LATEST_WITH_MESSAGES).use { statement ->
                statement.setString(1, excluding)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.toSession() else null
                }
            }
        }

    override fun setSummary(chatId: String, previousChatId: String?, summary: String?, messages: Int) {
        guarded("запись сводки") { connection ->
            // Время сессии здесь не трогается: сводка — не сообщение, и чат не должен
            // всплывать в списке только потому, что для него что-то посчитали.
            connection.prepareStatement(UPDATE_SESSION_SUMMARY).use { statement ->
                statement.setString(1, previousChatId)
                statement.setString(2, summary)
                statement.setInt(3, messages)
                statement.setString(4, chatId)
                statement.executeUpdate()
            }
        }
    }

    override fun delete(chatId: String): Int = transaction("удаление сессии") { connection ->
        // Сообщения, затем сессия — и обе операции в одной транзакции: если бы сессия
        // исчезла, а история осталась, её строки стали бы мусором, который никто не найдёт
        // и не удалит.
        val messages = connection.prepareStatement(DELETE_MESSAGES).use { statement ->
            statement.setString(1, chatId)
            statement.executeUpdate()
        }
        val sessions = connection.prepareStatement(DELETE_SESSION).use { statement ->
            statement.setString(1, chatId)
            statement.executeUpdate()
        }
        // Сессии не было — не «удалено ноль», а «удалять было нечего»: по этому различию
        // маршрут отвечает 404, а не успехом с нулём сообщений.
        if (sessions == 0) NOT_FOUND else messages
    }

    override fun counts(): StoreCounts = guarded("подсчёт хранилища") { connection ->
        StoreCounts(
            sessions = connection.count(SELECT_SESSION_COUNT),
            messages = connection.count(SELECT_MESSAGE_COUNT)
        )
    }

    /**
     * Закрывает соединение; повторный вызов ничего не делает.
     *
     * Идемпотентность нужна на остановке процесса: закрытие стоит в `finally`, и второй
     * проход по нему не должен превращаться в отказ.
     */
    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            try {
                connection.close()
            } catch (error: SQLException) {
                throw IllegalStateException("закрытие базы сессий: $file", error)
            }
        }
    }

    /**
     * Обращение к драйверу под замком.
     *
     * Отказы драйвера приводятся к одному типу с текстом «что делали и где»: SQLException
     * с номером состояния ничего не говорит ни логу, ни человеку, а причина сохраняется
     * в `cause`, так что оригинал не теряется. Собственного типа исключения не заводим —
     * поведение у отказа одно (ответ 500 через обработчик ошибок сервиса), и тип-обёртка
     * только маскировал бы драйвер там, где его и так не ловят.
     */
    private fun <T> guarded(what: String, block: (Connection) -> T): T = lock.withLock {
        if (closed) throw IllegalStateException("$what: база сессий закрыта")
        try {
            block(connection)
        } catch (error: SQLException) {
            throw IllegalStateException("$what: база сессий отказала ($file)", error)
        }
    }

    /**
     * То же под замком, но одной транзакцией с откатом при отказе.
     *
     * Автофиксация включается обратно в любом случае: после неудачной записи следующее
     * обращение не должно идти внутри незакрытой транзакции, а после удачной коммитить уже
     * нечего.
     */
    private fun <T> transaction(what: String, block: (Connection) -> T): T = guarded(what) { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (error: Throwable) {
            // Откат обязателен: половина удаления выглядела бы как «у чата не было истории».
            // Если не удался и он, исходная причина важнее — её и бросаем, а отказ отката
            // остаётся при ней подавленным, чтобы не потеряться совсем.
            try {
                connection.rollback()
            } catch (rollbackError: SQLException) {
                error.addSuppressed(rollbackError)
            }
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    /**
     * Открывает файл и приводит его в рабочее состояние.
     *
     * Каталог создаётся до открытия: путь по умолчанию (`data/service-chats.db` в контейнере)
     * задаёт человек, и каталога под файлом может не быть — тогда открытие упало бы с
     * «файл не найден», хотя причина в другом. Схема объявляется здесь же и через
     * `IF NOT EXISTS`: файл обычный, его открывают повторно после перезапуска, и повторное
     * открытие не должно быть ошибкой.
     */
    private fun open(): Connection {
        val absolute = file.toAbsolutePath()
        try {
            absolute.parent?.let { Files.createDirectories(it) }
        } catch (error: IOException) {
            throw IllegalStateException("каталог базы сессий не создан: $absolute", error)
        }

        val opened = try {
            DriverManager.getConnection("jdbc:sqlite:$absolute")
        } catch (error: SQLException) {
            throw IllegalStateException("база сессий не открыта: $absolute", error)
        }
        try {
            opened.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA busy_timeout=5000")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute(CREATE_SESSIONS_TABLE)
                statement.execute(CREATE_MESSAGES_TABLE)
                statement.execute(CREATE_MESSAGES_INDEX)
            }
            migrateSessions(opened)
        } catch (error: SQLException) {
            // Соединение наружу не отдаётся, поэтому убирается здесь: иначе неудачное
            // открытие оставило бы за собой ещё один дескриптор к тому же файлу.
            runCatching { opened.close() }
            throw IllegalStateException("схема базы сессий не объявлена: $absolute", error)
        }
        return opened
    }

    /**
     * Досоздаёт колонки сводки в существующей базе.
     *
     * Файл базы живёт дольше кода: его открывает и пользователь, и каждый прогон, поэтому
     * таблица `chat_sessions` в нём создана прежней схемой — без `previous_chat_id`,
     * `summary` и `summary_messages`. `ALTER TABLE ... ADD COLUMN` в SQLite не умеет
     * `IF NOT EXISTS`, а глушить «duplicate column name» нельзя: так исчезал бы любой
     * настоящий отказ схемы. Поэтому колонки сначала читаются у самой базы
     * (`PRAGMA table_info`), и добавляется только то, чего в ней нет, — повторное
     * открытие базы проходит через это же место и не падает.
     *
     * @param connection Открытое соединение: миграция до того, как хранилище станет рабочим.
     */
    private fun migrateSessions(connection: Connection) {
        val present = connection.prepareStatement(SELECT_SESSION_COLUMNS).use { statement ->
            statement.executeQuery().use { rows ->
                buildSet { while (rows.next()) add(rows.getString(COLUMN_NAME)) }
            }
        }
        SESSION_MIGRATIONS.forEach { (column, statement) ->
            if (column !in present) {
                connection.createStatement().use { it.execute(statement) }
            }
        }
    }

    /** Идентификатор сессии: 16 случайных байт в шестнадцатеричном виде — 32 символа. */
    private fun newId(): String {
        val bytes = ByteArray(ID_BYTES)
        random.nextBytes(bytes)
        val hex = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            hex[index * 2] = HEX[value ushr 4]
            hex[index * 2 + 1] = HEX[value and 0x0F]
        }
        return String(hex)
    }

    /** Сколько строк в таблице: ключ не нужен, считается вся таблица. */
    private fun Connection.count(sql: String): Int =
        prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getInt(1) else 0
            }
        }

    private fun ResultSet.toSession(): ChatSession = ChatSession(
        id = getString("id"),
        title = getString("title"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        previousChatId = getString("previous_chat_id"),
        summary = getString("summary"),
        summaryMessages = getInt("summary_messages")
    )

    private companion object {

        /** Сколько случайных байт в идентификаторе: 128 бит — перебор не имеет смысла. */
        const val ID_BYTES = 16

        /** Значение, которым [delete] отвечает на отсутствующую сессию. */
        const val NOT_FOUND = -1

        /** Таблица цифр для шестнадцатеричного вида: без `String.format` на каждый байт. */
        val HEX = "0123456789abcdef".toCharArray()

        /** Строка — сессия; время в миллисекундах эпохи, как его считает вызывающий. */
        val CREATE_SESSIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS chat_sessions (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent()

        /** Строка — одно сообщение; порядок внутри сессии задаёт автоинкрементный ключ. */
        val CREATE_MESSAGES_TABLE = """
            CREATE TABLE IF NOT EXISTS chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent()

        /**
         * Индекс под чтение истории: выборка сообщений одной сессии идёт по её ключу и
         * упорядочивается автоинкрементным ключом, поэтому оба столбца — в одном индексе.
         */
        const val CREATE_MESSAGES_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_chat_messages_chat ON chat_messages(chat_id, id)"

        /** Имя колонки с числом сообщений: одно на запрос списка и на его разбор. */
        const val MESSAGE_COUNT = "message_count"

        /** Имя колонки в ответе `PRAGMA table_info`: так SQLite называет столбец с именем. */
        const val COLUMN_NAME = "name"

        /** Колонки таблицы сессий: по ним миграция отличает уже добавленное от недостающего. */
        const val SELECT_SESSION_COLUMNS = "PRAGMA table_info(chat_sessions)"

        /**
         * Колонки сводки, которых может не быть в существующей базе, и как их добавить.
         *
         * Таблица, а не три `if`: состав колонок и порядок, в котором они называются, должны
         * читаться в одном месте — здесь же их и добавляют.
         */
        val SESSION_MIGRATIONS = listOf(
            "previous_chat_id" to "ALTER TABLE chat_sessions ADD COLUMN previous_chat_id TEXT",
            "summary" to "ALTER TABLE chat_sessions ADD COLUMN summary TEXT",
            "summary_messages" to
                "ALTER TABLE chat_sessions ADD COLUMN summary_messages INTEGER NOT NULL DEFAULT 0"
        )

        const val INSERT_SESSION =
            "INSERT INTO chat_sessions(id, title, created_at, updated_at) VALUES (?, ?, ?, ?)"

        const val SELECT_SESSION =
            "SELECT id, title, created_at, updated_at, previous_chat_id, summary, summary_messages " +
                "FROM chat_sessions WHERE id = ?"

        /**
         * Список чатов: свежие сверху, у каждой строки — число сообщений подзапросом.
         *
         * Вторичная сортировка по ключу строки нужна затем, что `updated_at` — миллисекунды,
         * и два чата, тронутых подряд, могут совпасть по времени: без неё порядок страницы
         * зависел бы от того, как SQLite вздумается выдать строки при равных ключах.
         */
        val SELECT_SESSIONS = """
            SELECT s.id, s.title, s.created_at, s.updated_at,
                   s.previous_chat_id, s.summary, s.summary_messages,
                   (SELECT COUNT(*) FROM chat_messages m WHERE m.chat_id = s.id) AS $MESSAGE_COUNT
            FROM chat_sessions s
            ORDER BY s.updated_at DESC, s.rowid DESC
            LIMIT ?
        """.trimIndent()

        /** Прошлый чат: свежайший среди тех, у кого есть хоть одно сообщение. */
        val SELECT_LATEST_WITH_MESSAGES = """
            SELECT s.id, s.title, s.created_at, s.updated_at,
                   s.previous_chat_id, s.summary, s.summary_messages
            FROM chat_sessions s
            WHERE s.id <> ?
              AND EXISTS (SELECT 1 FROM chat_messages m WHERE m.chat_id = s.id)
            ORDER BY s.updated_at DESC, s.rowid DESC
            LIMIT 1
        """.trimIndent()

        const val UPDATE_SESSION_SUMMARY =
            "UPDATE chat_sessions SET previous_chat_id = ?, summary = ?, summary_messages = ? WHERE id = ?"

        const val INSERT_MESSAGE =
            "INSERT INTO chat_messages(chat_id, role, content, created_at) VALUES (?, ?, ?, ?)"

        const val TOUCH_SESSION = "UPDATE chat_sessions SET updated_at = ? WHERE id = ?"

        const val SELECT_MESSAGES =
            "SELECT role, content, created_at FROM chat_messages WHERE chat_id = ? ORDER BY id"

        const val DELETE_MESSAGES = "DELETE FROM chat_messages WHERE chat_id = ?"

        const val DELETE_SESSION = "DELETE FROM chat_sessions WHERE id = ?"

        const val SELECT_SESSION_COUNT = "SELECT COUNT(*) FROM chat_sessions"

        const val SELECT_MESSAGE_COUNT = "SELECT COUNT(*) FROM chat_messages"
    }
}
