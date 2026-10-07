package com.osvin.aichallenge.rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Разбор ответа модели и проверка цитат: главное правило дня — `цитата ⊆ текст чанка`.
 *
 * Модель просят вернуть ответ машинно-читаемым видом ([Prompt.GROUNDED_SYSTEM]): текст ответа,
 * признак «ответа нет» и утверждения с цитатами и номерами фрагментов. Но просьба — не гарантия:
 * живой прогон дня 23 показал, что модель отвечает и не тем форматом, который у неё просили
 * (вместо пар «номер=оценка» приходит список чисел). Поэтому разбор построен так, что **любой
 * непонятный ответ считается отказом**, а не принимается как есть: у нас нет способа подтвердить
 * утверждение, у которого нет проверяемой цитаты, а показать его как подтверждённое — это и есть
 * галлюцинация, только пропущенная кодом.
 *
 * Проверка цитаты ([validate]) идёт по тексту чанка, а не по доверию модели (§11 задания): цитата
 * нормализуется тем же способом, что и текст в дне 22 ([Check.normalize]), и ищется в чанке
 * подстрокой. Сравнение по нормализованному виду — не поблажка, а условие работы: модель может
 * снять кавычки, поменять регистр или поставить вместо «ё» «е», и это не меняет того, что цитата
 * взята из текста. Сокращение цитаты допускается (§5): многоточие делит её на части, и каждая часть
 * проверяется отдельно.
 */
object Citations {

    /**
     * Разобранный ответ модели: то, что дальше проверяется и показывается.
     *
     * [reason] заполнен, когда разбор не удался или модель отказалась: лог обязан объяснять, почему
     * цитат нет, — иначе «модель сказала, что не знает» и «мы не поняли её ответ» выглядят
     * одинаково.
     */
    data class Parsed(
        val answer: String?,
        val claims: List<Claim>,
        val insufficient: Boolean,
        val reason: String?
    )

    /**
     * Разбор сырого ответа модели.
     *
     * Порядок разбора: сначала ответ ищется как JSON ([RawAnswer]); если JSON нет или он битый,
     * ответ проверяется на признаки отказа словами. Обратный порядок был бы хуже: текст «не знаю,
     * но вот цитата…» содержит признак отказа, и разбор по словам выбросил бы цитаты, которые
     * в JSON были бы видны.
     *
     * Утверждения с номером фрагмента вне контекста здесь **не отбрасываются**: отброшенное
     * утверждение неотличимо от несуществующего, и выдуманная моделью ссылка исчезла бы из отчёта
     * вместе с уликой. Их отсеивает проверка ([validate]) — с причиной, которую читает отчёт,
     * — и в счётчик выдуманных источников (§4) они попадают именно оттуда.
     */
    fun parse(raw: String, sources: List<Source>): Parsed {
        val json = extractJson(raw)
        val parsed = json?.let { text ->
            runCatching { JSON.decodeFromString<RawAnswer>(text) }.getOrNull()
        }

        if (parsed != null) {
            val claims = parsed.claims.map { claim ->
                Claim(
                    text = claim.claim.trim(),
                    fragment = claim.fragment,
                    quote = claim.quote.trim()
                )
            }
            val refused = parsed.insufficient || parsed.answer.isBlank() && claims.isEmpty()
            return Parsed(
                answer = parsed.answer.trim().ifEmpty { null },
                claims = claims,
                insufficient = refused,
                reason = if (refused) "модель сообщила, что ответа в найденных фрагментах нет" else null
            )
        }

        val text = raw.trim()
        return when {
            text.isEmpty() -> Parsed(null, emptyList(), insufficient = true, reason = "модель вернула пустой ответ")
            looksLikeRefusal(text) -> Parsed(text, emptyList(), insufficient = true, reason = "модель ответила словами, что ответа нет")
            else -> Parsed(
                answer = text,
                claims = emptyList(),
                insufficient = true,
                reason = "ответ модели не разобран: цитат в нём нет, подтвердить утверждения нечем"
            )
        }
    }

    /**
     * Проверка одной цитаты по тексту процитированного чанка (§5, §11).
     *
     * Возвращается не булево, а [QuoteCheck] с причиной: отчёт дня 24 обязан различать «цитаты нет
     * в чанке» (модель пересказала) и «цитата слишком короткая» (модель сослалась на пару слов) —
     * это разные дефекты, и лечатся они разными правками промпта.
     */
    fun validate(claim: Claim, sources: List<Source>): QuoteCheck {
        val source = sources.firstOrNull { it.rank == claim.fragment }
            ?: return QuoteCheck(claim, null, found = false, reason = "фрагмента [${claim.fragment}] не было в контексте")

        val quote = Check.normalize(claim.quote)
        if (quote.count { it == ' ' } + 1 < MIN_QUOTE_WORDS || quote.length < MIN_QUOTE_CHARS) {
            return QuoteCheck(
                claim,
                source.id,
                found = false,
                reason = "цитата короче $MIN_QUOTE_WORDS слов или $MIN_QUOTE_CHARS символов: подтверждением не считается"
            )
        }

        val chunk = Check.normalize(source.text)
        // Многоточие делит цитату **до** нормализации: нормализация заменяет знаки препинания
        // пробелами, и многоточие в ней пропадает — сокращённая цитата проверялась бы как сплошная
        // строка, которой в чанке нет, и любая цитата с многоточием считалась бы невалидной.
        val parts = claim.quote.split(ELLIPSIS)
            .map { Check.normalize(it) }
            .filter { it.length >= MIN_PART_CHARS }
        if (parts.isEmpty()) {
            return QuoteCheck(claim, source.id, found = false, reason = "в цитате нет частей длиннее $MIN_PART_CHARS символов")
        }

        val missing = parts.firstOrNull { part -> !chunk.contains(part) }
        return if (missing == null) {
            QuoteCheck(claim, source.id, found = true, reason = null)
        } else {
            QuoteCheck(
                claim,
                source.id,
                found = false,
                reason = "цитаты нет в тексте фрагмента: «${missing.take(RAW_LIMIT)}»"
            )
        }
    }

    /**
     * JSON из ответа модели: от первой `{` до последней `}`.
     *
     * Модель нередко окружает JSON пояснением или заворачивает его в блок кода, и требовать от неё
     * ровно одну строку значило бы терять правильные ответы из-за оформления. Границы берутся
     * по крайним скобкам, а не по первой паре: вложенные объекты утверждений закрываются раньше
     * внешнего, и разбор по первой паре отрезал бы хвост ответа.
     */
    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
    }

    /** Признаки отказа словами: модель ответила не по формату, но содержание отказа читается. */
    private fun looksLikeRefusal(text: String): Boolean {
        val normalized = Check.normalize(text)
        return REFUSAL_MARKERS.any { normalized.contains(it) }
    }

    /** Ответ модели в том виде, в каком он запрошен у неё в промпте. */
    @Serializable
    private data class RawAnswer(
        val answer: String = "",
        val insufficient: Boolean = false,
        val claims: List<RawClaim> = emptyList()
    )

    /** Утверждение в ответе модели: текст, номер фрагмента и цитата. */
    @Serializable
    private data class RawClaim(
        val claim: String = "",
        val fragment: Int = 0,
        val quote: String = ""
    )

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Многоточие в любом написании: модель ставит и «…», и три точки. */
    private val ELLIPSIS = Regex("…|\\.{3,}")

    private val REFUSAL_MARKERS = listOf("не знаю", "недостаточно", "нет ответа", "не содержит ответа", "не могу ответить")

    /** Сколько символов пропавшей части цитаты попадает в причину: хватает, чтобы узнать место. */
    private const val RAW_LIMIT = 60

    /** Короткая цитата подтверждением не считается: пара слов совпадёт почти с любым чанком. */
    private const val MIN_QUOTE_WORDS = 3
    private const val MIN_QUOTE_CHARS = 15

    /** Часть сокращённой цитаты короче этого — не доказательство, а совпадение. */
    private const val MIN_PART_CHARS = 8
}
