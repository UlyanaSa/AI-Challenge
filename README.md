## Задачи (ветки)

Ветки репозитория называются по номеру дня задачи: `task-1`, `task-2` и так далее.

### task-1

День 1

Напишите минимальный код, который:

- отправляет запрос в LLM через API
- получает ответ
- выводит его в консоль или простой интерфейс (CLI / Web)

**Результат:** код, который отправляет запрос в LLM через API и получает ответ.

**Формат:** видео + код.

### task-2

День 2

Отправьте один и тот же запрос, но:

- добавьте явное описание формата ответа
- добавьте ограничение на длину ответа
- добавьте условие завершения ответа (stop sequence или явную инструкцию)

Сравните ответы:

- без ограничений
- с ограничениями

**Результат:** один и тот же запрос с разным уровнем контроля ответа через API.



### task-3

День 3. Разные способы рассуждения

Возьмите одну задачу
(логическую, алгоритмическую или аналитическую)

Решите её через API четырьмя способами:

- получите прямой ответ без дополнительных инструкций
- добавьте в промпт инструкцию: «решай пошагово»
- попросите модель сначала составить промпт для решения задачи,
  а затем используйте его
- создайте в промпте группу экспертов
  (например: аналитик, инженер, критик)
  и получите решение от каждого

Сравните:

- отличаются ли ответы
- какой способ дал наиболее точный результат

**Результат:** несколько решений одной задачи и их сравнение.

**Заключение (проверено на загадке «С какой скоростью должна двигаться собака, чтобы не слышать звона сковородки, привязанной к её хвосту?»):**

Все четыре способа решают задачу, но по-разному раскрывают её. Ключевой момент — в условии спрятана ловушка: сковородка привязана к самой собаке, поэтому звук распространяется к её ушам и по воздуху, и через кости черепа (костная проводимость), и убежать от собственного звона невозможно — задача шуточная. Лучше всех это вскрыл вариант с составленным промптом.

| Вариант | Суть ответа | Токены (вх/вых/всего) | Время, с | Скорость, ток/с | Длина, симв | Стоимость | Точность | Глубина |
|---|---|---|---|---|---|---|---|---|
| Прямой ответ | Физически невыполнимый ответ «быстрее звука», краткий разбор парадокса | 45/587/632 | 6,7 | 88 | 1803 | $0,000658 | 8/10 | 9/10 |
| «Решай пошагово» | Те же выводы по шагам: звон идёт по воздуху и костям, в пределах возможного собаки — невыполнимо | 74/627/701 | 6,4 | 98 | 2009 | $0,000710 | 9/10 | 9/10 |
| Составленный промпт | Самый полный разбор: ошибка условия, костная проводимость, все трактовки и итог | 962/2430/3392 | 23,2 | 105 | 7653 | $0,002933 | 8/10 | 9/10 |
| Группа экспертов | Аналитик (физика волн), инженер (пути передачи звука), критик (ошибка постановки) | 237/2051/2288 | 22,5 | 91 | 6496 | $0,002320 | 7/10 | 6/10 |

Ответы отличаются по полноте и глубине. Вердикт модели-судьи (параметры: правильность, полнота, обоснованность, ясность): наиболее точное — решение «составленный промпт»: оно единственное полностью вскрывает скрытую ошибку условия (костная проводимость) и систематизирует все трактовки; решение группы экспертов сильно, но менее структурировано и содержит избыточные рассуждения.

Вывод по способам: дорогие многошаговые способы (составленный промпт, эксперты) дают заметно более полные ответы ценой ~4–5× больше токенов и времени, чем прямой ответ или пошаговое решение.

### task-4

День 4: Температура

Выполните один и тот же запрос с параметрами:

- temperature = 0
- temperature = 0.7
- temperature = 1.2

Сравните ответы по:

- точности
- креативности
- разнообразию

Сформулируйте:

- для каких задач лучше подходит каждая настройка

**Результат:** примеры ответов с разной температурой и выводы по их использованию.

**Заключение (проверено на задаче «Предложи 5 разных идей необычного музея, каждая идея — одним предложением и обязательно про звук; музей тишины подходит, а музей картин — нет»):**

Один и тот же запрос прогнан при temperature 0 / 0.7 / 1.2 (по два прогона на каждое значение) через собственный сервер. Задача-зонд покрывает все три критерия сразу: условие «5 идей, все про звук» измеримо (точность), выбор тем свободен (креативность), пересечение прогонов считается (разнообразие). Закрытая задача-ловушка («17 овец», правильный ответ 9) для этой проверки не годится: у неё нет простора для креативности — все температуры дали верный ответ 7/7, и ответы неразличимы по структуре.

| temperature | Идей из 5 (2 прогона) | Про звук | Пересечение прогонов | Примеры идей |
|---|---|---|---|---|
| 0 | 5/5 и 5/5 | 10/10 | 2 из 5 (Jaccard 0,25): «Эхо исчезнувших профессий», «Тишина в движении» | «Эхо исчезнувших профессий», «Голоса вещей», «Шум времени» |
| 0.7 | 5/5 и 5/5 | 10/10 | 0 (Jaccard 0) — темы переформулированы | «Симфония вещей», «Слушая землю», «Шёпот стен» |
| 1.2 | 5/5 и 5/5 | 9/10 | 0 (Jaccard 0) | «Предсмертный треск пластинок», «Шёпоты птичьих перелётов», «Слуховая амнезия» |

- **Точность** (следование условию) стабильна: 5/5 идей и 10/10 «про звук» при 0 и 0.7. При 1.2 — 9/10: единственное во всём эксперименте отклонение («Музей дыхания архитектуры» — звук через старинные органы) случилось именно на максимальной температуре.
- **Креативность** растёт с температурой: 0 даёт «надёжное» ядро идей, 1.2 — смелые и образные («предсмертный треск пластинок», «слуховая амнезия»).
- **Разнообразие** между прогонами растёт с температурой: при 0 два прогона делят 2 идеи из 5, при 0.7 и 1.2 пересечение нулевое — каждый прогон даёт полностью новый набор.

Вывод по настройкам: temperature 0 — когда нужен один воспроизводимый результат (шаблоны, A/B-варианты); 0.7 — «похожее, но свежее» для регулярной генерации: идеи не выпадают из ожидаемого пула, а формулировки новые; 1.2 — мозговой штурм: прогнать 3–5 раз и объединить списки, приняв риск выхода за рамки условия. На задачах с единственно верным ответом температура до 1.2 точность не роняет — её влияние проявляется на открытых задачах.


This is a Kotlin Multiplatform project targeting Android, iOS, Web, Server.

* [/app/iosApp](./app/iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/app/shared](./app/shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./app/shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./app/shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./app/shared/src/jvmMain/kotlin)
    folder is the appropriate location.

* [/core](./core/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Server: `./gradlew :server:run`
- Web app:
  - Wasm target (faster, modern browsers): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
  - JS target (slower, supports older browsers): `./gradlew :app:webApp:jsBrowserDevelopmentRun`
- iOS app: open the [/app/iosApp](./app/iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :app:shared:testAndroidHostTest`
- Server tests: `./gradlew :server:test`
- Web tests:
  - Wasm target: `./gradlew :app:shared:wasmJsTest`
  - JS target: `./gradlew :app:shared:jsTest`
- iOS tests: `./gradlew :app:shared:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).