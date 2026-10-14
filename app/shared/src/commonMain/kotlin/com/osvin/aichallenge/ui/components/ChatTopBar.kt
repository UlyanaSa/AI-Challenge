package com.osvin.aichallenge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * Верхняя панель чата.
 *
 * Переключателя веток здесь нет: варианты продолжения живут на самих сообщениях
 * («‹ 2/3 ›» в [ChatBubble]), поэтому шапка остаётся про чат и связь.
 *
 * @param title Заголовок активного чата; null — чат ещё не выбран.
 * @param isOnline Статус подключения сервера.
 * @param model Модель, которой ответил сервер на последний вопрос; null — ответов ещё не было.
 * @param provider Провайдер последнего ответа: `local` — запрос не покидал эту машину.
 * @param onBack Возврат к списку чатов.
 * @param onTitleClick Обработчик нажатия на заголовок (для ручной проверки связи).
 * @param onNewChat Создание нового чата и переход в него.
 * @param onInvariants Открытие шторки инвариантов проекта.
 * @param onProfile Открытие шторки профиля пользователя.
 * @param onServer Открытие шторки настроек приватного сервиса: адрес, ключ, модель,
 *        рамки запроса и переключатель режима чата.
 * @param showBack Показывать ли возврат к списку: на узком экране список — отдельный
 *        экран, и без возврата из чата не выйти; на широком список стоит рядом,
 *        и стрелка возврата не нужна.
 * @param showNewChat Показывать ли создание чата в шапке: на широком экране кнопка
 *        есть в списке чатов, и вторая кнопка на той же странице повторяла бы её.
 * @param compareMode Включено ли сравнение сборок модели: под заголовком тогда стоит
 *        строка о сравнении, а не имя одной ответившей модели.
 * @param showCompare Показывать ли кнопку сравнения сборок: в режиме приватного сервиса
 *        её нет — сравнение относится к пути `/v1`, а не к сервисной сессии.
 * @param onToggleCompare Включение и выключение сравнения сборок.
 * @param serviceMode Открыт ли чат приватного сервиса: его лента обновляется и делится
 *        ссылкой, а не сравнивается по сборкам — чат живёт на сервисе и общий у клиентов.
 * @param canShare Показывать ли «Поделиться»: ссылку на чат собирает только та платформа,
 *        что знает адрес своей страницы ([appAddress]). Там, где адреса нет — приложение
 *        Android, iOS, десктоп, — кнопка была бы без действия: она отдала бы голый
 *        идентификатор, который нечем скопировать.
 * @param onRefresh Перечитывание списка чатов сервиса и истории открытого: так появляется
 *        написанное другим клиентом — сервис о чужих записях сам не сообщает.
 * @param onShare Показ и копирование ссылки на открытый чат сервиса: по ней второй клиент
 *        с ключом открывает тот же разговор.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    title: String?,
    isOnline: Boolean?,
    model: String? = null,
    provider: String? = null,
    compareMode: Boolean = false,
    showCompare: Boolean = true,
    onToggleCompare: () -> Unit = {},
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onNewChat: () -> Unit,
    onInvariants: () -> Unit,
    onProfile: () -> Unit,
    onServer: () -> Unit,
    showBack: Boolean = true,
    showNewChat: Boolean = true,
    serviceMode: Boolean = false,
    canShare: Boolean = true,
    onRefresh: () -> Unit = {},
    onShare: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    BackIcon()
                }
            }
        },
        title = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTitleClick() }
            ) {
                Text(
                    text = title ?: "DeepSeek AI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ConnectionStatus(isOnline)
                // Имя ответившей модели — под статусом связи и до ответа не показывается:
                // до первого ответа называть модель значило бы обещать, а не сообщать.
                // В локальном режиме подпись называет провайдера: имя модели Ollama
                // (`qwen3:1.7b`) само по себе не говорит, что ответ пришёл с этой машины
                model?.let {
                    Text(
                        text = if (provider == LOCAL_PROVIDER) "локальная модель: $it" else "модель: $it",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // В сравнении отвечают две сборки сразу, и строка называет не модель,
                // а то, что их две: имя каждой стоит над своим окном
                if (compareMode) {
                    Text(
                        text = "сравнение двух сборок локальной модели",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        actions = {
            // Сравнение сборок модели: включается из шапки, потому что это способ смотреть
            // на ответы, а не настройка чата — оно действует до выключения и в ленту
            // обычного разговора ничего не пишет. В режиме сервиса кнопки нет
            // ([showCompare]): сервис отвечает одной сессией, а не двумя сборками
            if (showCompare) {
                TextButton(onClick = onToggleCompare) {
                    Text(
                        text = if (compareMode) "Обычный чат" else "Сравнить сборки",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            // Лента сервиса: «Обновить» перечитывает список чатов и историю открытого —
            // так появляется написанное другим клиентом, потому что сервис о чужих
            // записях сам не сообщает. «Поделиться» даёт ссылку на тот же чат: по ней
            // второй клиент с ключом открывает этот разговор — там, где ссылку есть
            // из чего собрать ([canShare]), иначе кнопка была бы без действия
            if (serviceMode) {
                TextButton(onClick = onRefresh) {
                    Text("Обновить", style = MaterialTheme.typography.labelLarge)
                }
                if (canShare) {
                    TextButton(onClick = onShare) {
                        Text("Поделиться", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            // Инварианты проекта: те же правила уходят в модель с каждым запросом,
            // поэтому правятся они там же, где видно остальные настройки агента
            TextButton(onClick = onInvariants) {
                Text("Инварианты", style = MaterialTheme.typography.labelLarge)
            }
            // Профиль пользователя: предпочтения, которые сервер подставляет в каждый
            // запрос к модели; шторка открывается поверх чата, а не занимает ленту
            TextButton(onClick = onProfile) {
                Text("Профиль", style = MaterialTheme.typography.labelLarge)
            }
            // Приватный сервис: его адрес, ключ и режим чата правятся поверх чата —
            // выбранный режим определяет, куда уходит следующее сообщение
            TextButton(onClick = onServer) {
                Text("Сервер", style = MaterialTheme.typography.labelLarge)
            }
            // Новый чат: заводится сразу и открывается — настраивать перед первым
            // сообщением нечего, провайдера и модель выбирает конфигурация сервера
            if (showNewChat) {
                IconButton(onClick = onNewChat) {
                    PlusIcon()
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

/**
 * Значение провайдера в ответе сервера для локального режима.
 *
 * Строка, а не перечисление: провайдера выбирает конфигурация сервера, и клиент
 * о нём знает ровно столько, сколько сервер прислал, — иначе список провайдеров
 * на клиенте разошёлся бы с тем, что сервер умеет.
 */
private const val LOCAL_PROVIDER = "local"
