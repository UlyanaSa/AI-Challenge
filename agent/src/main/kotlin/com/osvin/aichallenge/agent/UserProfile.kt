package com.osvin.aichallenge.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Заголовок блока профиля: по нему профиль видно и в промпте, и в логе. */
private const val PROFILE_HEADER = "Профиль пользователя (учитывай в каждом ответе):"

/** Подписи полей блока: те же слова, что в профиле по умолчанию, — иначе блок не прочитать. */
private const val ROLE_LABEL = "кто"
private const val STACK_LABEL = "стек"
private const val STYLE_LABEL = "стиль"
private const val FORMAT_LABEL = "формат"
private const val CONSTRAINTS_LABEL = "ограничения"
private const val SIGN_OFF_LABEL = "в конце каждого ответа"

/**
 * Профиль пользователя: объявленные предпочтения — кто он, на чём пишет, каким хочет
 * видеть ответ и чем его подписывать.
 *
 * Профиль задаёт сам пользователь и целиком, поэтому он не извлекается из диалога и не
 * проходит то, что проходит память: слияние по тексту, пределы слоя и отклонение записей
 * ([MemoryRules]). Ошибаться негде — предпочтение заявлено, а не выведено моделью из
 * переписки, поэтому чинить и переписывать здесь нечего. Частью памяти профиль при этом
 * не становится: [MemoryLayer] описывает то, что модель наполняет сама и что стратегия
 * контекста то ведёт, то нет, а профиль обязан дойти до модели в каждом запросе
 * ([AgentOptions.profile] уходит системным сообщением при любой стратегии).
 *
 * Хранится профиль по тому же идентификатору области, что и память ([DEFAULT_PROFILE]):
 * пользователь в приложении один. Сущность другая — [MemoryStore] про профиль ничего не
 * знает, у него своё хранилище ([ProfileStore]).
 *
 * @param role Кто пользователь: роль и контекст работы.
 * @param stack На чём он пишет: языки, библиотеки, инструменты.
 * @param style Каким должен быть ответ по манере изложения.
 * @param format Каким должен быть ответ по форме: код, списки, таблицы, язык.
 * @param constraints Чего делать нельзя и о чём честно сказать, что не проверял.
 * @param signOff Чем заканчивать каждый ответ.
 */
@Serializable
data class UserProfile(
    @SerialName("role") val role: String = "",
    @SerialName("stack") val stack: String = "",
    @SerialName("style") val style: String = "",
    @SerialName("format") val format: String = "",
    @SerialName("constraints") val constraints: String = "",
    @SerialName("sign_off") val signOff: String = ""
) {

    /** Профиль пуст: все поля пусты — сообщения в запросе он не оставляет. */
    val isEmpty: Boolean
        get() = role.isBlank() && stack.isBlank() && style.isBlank() &&
            format.isBlank() && constraints.isBlank() && signOff.isBlank()

    /**
     * Блок профиля для системного сообщения: заголовок и заполненные поля.
     *
     * Пустые поля не печатаются: строка «стиль: » без значения читается моделью как
     * требование, о котором забыли, и ответ подстраивается под пустоту. Заполнено
     * ничего — блок пуст, и в запрос его не добавляют ([AgentOptions.profile]).
     */
    fun render(): String {
        val lines = listOfNotNull(
            field(ROLE_LABEL, role),
            field(STACK_LABEL, stack),
            field(STYLE_LABEL, style),
            field(FORMAT_LABEL, format),
            field(CONSTRAINTS_LABEL, constraints),
            field(SIGN_OFF_LABEL, signOff)
        )
        return if (lines.isEmpty()) "" else (listOf(PROFILE_HEADER) + lines).joinToString("\n")
    }

    /** Строка блока; поле без текста в блок не попадает. */
    private fun field(label: String, value: String): String? =
        value.trim().takeIf { it.isNotEmpty() }?.let { "- $label: $it" }

    companion object {

        /**
         * Профиль владельца приложения: он же профиль по умолчанию для того, кто своего
         * ещё не задал.
         *
         * Профиль объявленный, а не извлечённый: так заявлено человеком, а не выведено
         * моделью из переписки, — поэтому он и лежит вне памяти. Личная настройка здесь
         * одна — подпись «Ты молодец» в конце каждого ответа: по ней видно, что профиль
         * дошёл до модели, а не остался настройкой интерфейса.
         */
        val DEFAULT = UserProfile(
            role = "Kotlin Multiplatform разработчик: общий код на Android, iOS, desktop и wasm, " +
                "сервер на JVM",
            stack = "Kotlin, kotlinx.coroutines и Flow; Compose Multiplatform и Material 3; " +
                "Ktor (клиент и сервер); kotlinx.serialization; Room и SQLDelight; Koin; " +
                "Gradle Kotlin DSL с version catalog; kotlin.test и Turbine; Detekt и Ktlint",
            style = "сначала вывод, потом причина; без вступлений, без пересказа вопроса, " +
                "без извинений; риск и сомнение называть прямо; если данных не хватает — " +
                "сказать, каких именно",
            format = "код на Kotlin полным куском: путь файла, сигнатуры, без пропусков «…»; " +
                "шаги — нумерованным списком; сравнения — таблицей; по-русски, " +
                "идентификаторы как в проекте",
            constraints = "только Kotlin, без Java и RxJava; общий код в commonMain, " +
                "платформенное — expect/actual; версии — из version catalog; новых зависимостей " +
                "не добавлять без явной просьбы; не выдумывать незнакомые API — сказать «не проверял»",
            signOff = "в конце каждого ответа отдельной строкой — «Ты молодец»"
        )
    }
}
