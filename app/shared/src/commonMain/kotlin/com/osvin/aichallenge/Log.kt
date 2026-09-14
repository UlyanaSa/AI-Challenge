package com.osvin.aichallenge

/**
 * Пишет строку в лог платформы: на Android — в logcat, на остальных таргетах — в консоль.
 *
 * Агент работает на сервере, поэтому его собственные `println` в logcat устройства не
 * попадают. Отчёт о токенах приходит в ответе (`tokens`), и приложение печатает его
 * тем же текстом через эту функцию — так строки видны в Android Studio в logcat
 * (фильтр `package:com.osvin.aichallenge` + `[agent]`).
 *
 * @param tag Тег записи: в logcat по нему фильтруют (`tag:agent`).
 * @param message Текст записи; начинается с `[agent]`, как строки агента на сервере.
 */
expect fun platformLog(tag: String, message: String)
