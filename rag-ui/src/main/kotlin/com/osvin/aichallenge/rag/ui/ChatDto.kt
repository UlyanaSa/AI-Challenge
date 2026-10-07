package com.osvin.aichallenge.rag.ui

import com.osvin.aichallenge.rag.SourceRef
import com.osvin.aichallenge.rag.chat.ChatScenario
import com.osvin.aichallenge.rag.chat.ChatScenarioCheck
import com.osvin.aichallenge.rag.chat.ChatScenarioRun
import com.osvin.aichallenge.rag.chat.ChatSummary
import com.osvin.aichallenge.rag.chat.ChatTurn
import com.osvin.aichallenge.rag.chat.TaskMemory
import com.osvin.aichallenge.rag.chat.TaskTerm
import kotlinx.serialization.Serializable

/**
 * Состояние мини-чата дня 25: лента ходов, память задачи, сводка и итог сценария одним ответом.
 *
 * Как и состояние прогона, оно приходит целиком и рисуется целиком: собрать разговор из нескольких
 * запросов значило бы показать реплики, память и сводку из разных моментов времени, а в разговоре
 * они меняются вместе — ход обновляет и память, и числа. Поля, которых ещё нет, пусты осознанно:
 * [summary] пуст, пока разговор не начат, [scenario] — пока не прогнан сценарий, [note] называет
 * причину, по которой чата нет вовсе. Пустая лента и неработающий чат выглядели бы одинаково, если
 * бы причину нельзя было прочитать отдельным полем.
 *
 * Сценарии уходят в состоянии, а не в `setup`: кнопки запуска — часть панели чата, и браузер
 * рисует их из того же ответа, что и ленту. Имена и заголовки берутся из [ChatScenarios] на сервере,
 * а не пишутся в разметке: второй список сценариев на странице разошёлся бы с движком при первой
 * правке. Две короткие строки на опрос — цена, которой не жалко: разговор показывает себя лентой,
 * а не списком сценариев.
 */
@Serializable
data class ChatStateDto(
    /** Можно ли вести разговор: есть и ключ, и построенный индекс. */
    val available: Boolean,
    /** Почему чат недоступен; `null` — доступен. Причина уходит человеку строкой, а не пустой лентой. */
    val note: String?,
    /** Сбой последнего хода или сценария; не то же, что недоступность чата. */
    val error: String?,
    /** Идёт ход или сценарий: страница на это время не даёт начать второй. */
    val busy: Boolean,
    /** Что именно идёт («разговор» или сценарий со своим именем): по этому видно, чего ждать. */
    val activity: String?,
    /** Настройки поиска разговора ([ChatSettings.description]): без них числа ответов не объяснить. */
    val settings: String,
    /** Сценарии движка — имена и заголовки для кнопок запуска. */
    val scenarios: List<ChatScenarioDto>,
    val turns: List<ChatTurnDto>,
    /** Память задачи в конце разговора: цель, уточнения, ограничения и термины. */
    val memory: ChatMemoryDto,
    /** Сводка ([ChatReport.summarize]); `null` — разговора ещё нет. */
    val summary: ChatSummaryDto?,
    /** Итог последнего прогона сценария; `null` — сценарий не прогоняли. */
    val scenario: ChatScenarioResultDto?
)

/**
 * Сценарий для кнопки запуска: имя, заголовок и цель.
 *
 * Цель и число реплик идут рядом с заголовком не для полноты: человек выбирает сценарий по тому,
 * о чём он, а не по имени `scene-1`, и числа реплик ему достаточно, чтобы понять размер разговора.
 * Всё это — из [ChatScenarios], а не из разметки страницы.
 */
@Serializable
data class ChatScenarioDto(
    val name: String,
    val title: String,
    val goal: String,
    val steps: Int
)

/**
 * Один ход разговора в том виде, в каком его показывает страница.
 *
 * Ход несёт не только ответ, но и путь к нему — вопрос, запрос поиска и пометку о нём, источники
 * и память: разбирать разговор по одному тексту ответа значит повторять прогон, а повторять его
 * нельзя. [kind] и [kindTitle] — одно и то же решение в двух видах, как у режимов дня 23: код
 * (`BASE`, `DIALOGUE`) выбирает разметку, слово объясняет исход. У отказа и сбоя вид пуст по смыслу:
 * отказ — это отсутствие ответа, а не ответ «по базе».
 *
 * [answer] пуст у отказа и сбоя осознанно, а не по забывчивости: подставить сюда текст отказа
 * значило бы выдать его за ответ модели, а показать человеку нужно именно причину — её и несёт
 * [refusalReason]. [elapsedMillis] — полное время хода: ответ, извлечение памяти и переписывание.
 */
@Serializable
data class ChatTurnDto(
    /** Номер хода в разговоре, с единицы. */
    val index: Int,
    /** Вопрос человека так, как он его задал. */
    val question: String,
    /** Запрос, которым искали: у неполного вопроса он раскрыт по истории и отличается от вопроса. */
    val query: String,
    /** Что сделал переписыватель; `null` — переписывать было нечего. */
    val queryNote: String?,
    /** Текст ответа; `null` у отказа и сбоя. */
    val answer: String?,
    /** Код вида ответа (`BASE` или `DIALOGUE`) для разметки; `null` у отказа и сбоя. */
    val kind: String?,
    /** Тот же вид ответа словом — то, что читает человек. */
    val kindTitle: String?,
    /** Источники подтверждённых утверждений — из метаданных чанков, как в дне 24. */
    val sources: List<ChatSourceDto>,
    /** Готовая строка источников: у отказа и ответа по памяти она объясняет, чем ответ подтверждён. */
    val sourcesLine: String,
    /** Отказ словом; `null` — отказа не было. */
    val refusal: String?,
    /** Причина отказа или сбоя: то, что страница показывает вместо блока источников. */
    val refusalReason: String?,
    /** Сбой обращения к модели; не то же, что отказ. */
    val error: String?,
    /** Что сделала память на этом ходу: обновлена, не изменилась или почему не разобрана. */
    val memoryNote: String?,
    /** Ответ опирается на память задачи, а не на фрагменты книги. */
    val fromMemory: Boolean,
    /** Ответ состоялся: отказ и сбой ответом не считаются. */
    val answered: Boolean,
    /** Полное время хода (ответ, память, переписывание). */
    val elapsedMillis: Long
)

/**
 * Источник ответа: запись, собранная системой из метаданных чанка (§4 дня 24).
 *
 * Здесь нет ни одного поля из текста модели — как и у [GroundedSourceDto]: страница показывает
 * страницы, раздел и `chunk_id`, которых модель не называла и не могла назвать. [label] — готовая
 * подпись движка (файл, раздел, `chunk_id`, номер фрагмента): собирать её на странице значило бы
 * завести второй способ называть источник, и он разошёлся бы с отчётом.
 */
@Serializable
data class ChatSourceDto(
    /** Номер фрагмента в контексте: им цитата связывается с источником. */
    val fragment: Int,
    /** Место в финальном Top-K: с ним источник пришёл в контекст. */
    val rank: Int,
    val source: String,
    val title: String?,
    val section: String?,
    /** Идентификатор чанка в индексе — то, что задание называет `chunk_id`. */
    val chunkId: String,
    val chunkIndex: Int,
    val pages: List<Int>,
    val similarity: Double,
    /** Оценка второго этапа; `null` — этапа не было или кандидат оценён не был. */
    val rerankScore: Double?,
    /** Готовая подпись источника для человека. */
    val label: String
)

/**
 * Память задачи на странице: цель, уточнения, ограничения и термины.
 *
 * Те же поля, что у [TaskMemory], и та же причина: память показывает человеку, что система держит
 * в разговоре, и второй способ её называть разошёлся бы с тем, что уходит в запрос к модели.
 * [empty] — не «полей нет», а «память пуста»: панель по нему пишет «память пока пуста», а не
 * рисует заголовки без строк, которые читались бы как память, в которой ничего нет.
 */
@Serializable
data class ChatMemoryDto(
    val goal: String? = null,
    val clarifications: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val terms: List<ChatTermDto> = emptyList(),
    val empty: Boolean = true
)

/** Термин разговора и его значение: то, что человек закрепил словом. */
@Serializable
data class ChatTermDto(val term: String, val meaning: String)

/**
 * Сводка разговора числами — то же, что считает [ChatReport.summarize], в сериализуемом виде.
 *
 * Числа не пересчитываются на странице: «сколько ответов без источника» и «держалась ли цель» —
 * это меры дня 25, и второй счётчик в браузере разошёлся бы с отчётом. Словесные признаки
 * ([sourcesEverywhere], [goalKept]) и строка [line] приходят посчитанными из `:rag` по той же
 * причине, что и метрики дней 23–24.
 */
@Serializable
data class ChatSummaryDto(
    val turns: Int,
    val answered: Int,
    val fromBase: Int,
    val fromMemory: Int,
    val refusals: Int,
    val errors: Int,
    /** Ответы без источника: должно быть ноль. */
    val answersWithoutSource: Int,
    val sourcesTotal: Int,
    val claims: Int,
    val quotesDropped: Int,
    val goal: String?,
    val goalFixedAt: Int?,
    val goalChanges: List<String>,
    val clarifications: Int,
    val constraints: Int,
    val terms: Int,
    val memoryUpdated: Int,
    val memoryUnchanged: Int,
    val memoryFailed: Int,
    /** У каждого ответа назван источник. */
    val sourcesEverywhere: Boolean,
    /** Цель зафиксирована и не менялась. */
    val goalKept: Boolean,
    /** Строка итога для страницы: те же числа, что в полях. */
    val line: String
)

/**
 * Итог прогона сценария: приговор, проверки по шагам и признаки дня.
 *
 * [accepted] решает день, [verdict] объясняет словами, [checks] показывают, где именно разошлось
 * поведение. Всё это приходит из [ChatScenarioRun] посчитанным: сценарий проверяется движком,
 * и на странице не должно быть второго набора правил проверки. [factsMatched] и [factsTotal] —
 * мера качества ответов, а не приговор: она идёт отдельной строкой, как в отчёте.
 */
@Serializable
data class ChatScenarioResultDto(
    val name: String,
    val title: String,
    val accepted: Boolean,
    /** Строки приговора: каждая — проверка и её результат. */
    val verdict: List<String>,
    /** Проверки по шагам: что ожидалось и что вышло. */
    val checks: List<ChatScenarioCheckDto>,
    /** Память задачи удержала ограничения и термины сценария. */
    val memoryKept: Boolean,
    val factsMatched: Int,
    val factsTotal: Int
)

/**
 * Проверка одного шага сценария: вид ответа, отказ, цель и сведения.
 *
 * `null` у [kindOk], [refusalOk] и [goalOk] означает «не проверялось» — так же, как в движке:
 * у шага, где отказ не ожидался, проверять его нечего, и ноль на этом месте был бы суждением,
 * которого никто не выносил. [failures] называет нарушения словами — по ним и чинят память,
 * поиск или промпт.
 */
@Serializable
data class ChatScenarioCheckDto(
    val index: Int,
    val question: String,
    val kindOk: Boolean?,
    val refusalOk: Boolean?,
    val goalOk: Boolean?,
    val factsMatched: Int,
    val factsTotal: Int,
    /** Обязательная часть пройдена: поведение шага, а не содержание ответа. */
    val passed: Boolean,
    val failures: List<String>
)

/** Тело запроса реплики: вопрос человека. */
@Serializable
data class ChatSendDto(val text: String)

/** Тело запроса запуска сценария: имя сценария движка. */
@Serializable
data class ChatScenarioRequestDto(val name: String)

/** Сценарий движка как пункт запуска на странице. */
internal fun ChatScenario.toDto(): ChatScenarioDto = ChatScenarioDto(
    name = name,
    title = title,
    goal = goal,
    steps = length
)

/** Ход разговора переводится поле в поле; время берётся полное — так его читает человек. */
internal fun ChatTurn.toDto(): ChatTurnDto = ChatTurnDto(
    index = index,
    question = question,
    query = query,
    queryNote = queryNote,
    answer = answer,
    kind = kind?.name,
    kindTitle = kind?.title,
    sources = sources.map { it.toChatDto() },
    sourcesLine = sourcesLine,
    refusal = refusal?.title,
    refusalReason = refusalReason,
    error = error,
    memoryNote = memoryNote,
    fromMemory = fromMemory,
    answered = answered,
    elapsedMillis = totalMillis
)

/**
 * Источник переводится как есть, включая готовую подпись: страница не собирает её второй раз,
 * потому что подпись источника — это то, что видит человек, и расхождение здесь видно сразу.
 */
internal fun SourceRef.toChatDto(): ChatSourceDto = ChatSourceDto(
    fragment = fragment,
    rank = rank,
    source = source,
    title = title,
    section = section,
    chunkId = chunkId,
    chunkIndex = chunkIndex,
    pages = pages,
    similarity = similarity,
    rerankScore = rerankScore,
    label = label
)

/** Память задачи уходит странице целиком: её и показывает панель памяти. */
internal fun TaskMemory.toDto(): ChatMemoryDto = ChatMemoryDto(
    goal = goal,
    clarifications = clarifications,
    constraints = constraints,
    terms = terms.map { it.toDto() },
    empty = isEmpty
)

/** Термин переводится парой «слово — значение», как он и хранится. */
internal fun TaskTerm.toDto(): ChatTermDto = ChatTermDto(term = term, meaning = meaning)

/**
 * Сводка переводится поле в поле, без пересчёта: страница показывает те же числа, что и отчёт,
 * — второй набор формул разошёлся бы с первым, и сверять разговор стало бы нечем.
 */
internal fun ChatSummary.toDto(): ChatSummaryDto = ChatSummaryDto(
    turns = turns,
    answered = answered,
    fromBase = fromBase,
    fromMemory = fromMemory,
    refusals = refusals,
    errors = errors,
    answersWithoutSource = answersWithoutSource,
    sourcesTotal = sourcesTotal,
    claims = claims,
    quotesDropped = quotesDropped,
    goal = goal,
    goalFixedAt = goalFixedAt,
    goalChanges = goalChanges,
    clarifications = clarifications,
    constraints = constraints,
    terms = terms,
    memoryUpdated = memoryUpdated,
    memoryUnchanged = memoryUnchanged,
    memoryFailed = memoryFailed,
    sourcesEverywhere = sourcesEverywhere,
    goalKept = goalKept,
    line = line
)

/** Приговор сценария уходит странице целиком: принято/нет, строки приговора и проверки по шагам. */
internal fun ChatScenarioRun.toDto(): ChatScenarioResultDto = ChatScenarioResultDto(
    name = scenario.name,
    title = scenario.title,
    accepted = accepted,
    verdict = verdict,
    checks = checks.map { it.toDto() },
    memoryKept = memoryKept,
    factsMatched = factsMatched,
    factsTotal = factsTotal
)

/** Проверка шага переводится как есть: нарушения — строками, чтобы их можно было прочитать. */
internal fun ChatScenarioCheck.toDto(): ChatScenarioCheckDto = ChatScenarioCheckDto(
    index = index,
    question = question,
    kindOk = kindOk,
    refusalOk = refusalOk,
    goalOk = goalOk,
    factsMatched = factsMatched,
    factsTotal = factsTotal,
    passed = passed,
    failures = failures
)
