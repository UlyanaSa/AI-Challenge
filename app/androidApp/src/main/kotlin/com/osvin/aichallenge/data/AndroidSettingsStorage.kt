package com.osvin.aichallenge.data

import android.content.Context

/**
 * Настройки сервиса на Android: файл `SharedPreferences` приложения.
 *
 * Своё хранилище рядом с хранилищем чатов (Room), а не колонка в его базе: настройки
 * сервиса — это то, что человек ввёл про соединение (адрес, ключ, модель), и к самим
 * чатам они не относятся. Положить их в базу чатов значило бы связать две сущности,
 * которые меняются порознь: настройку правят и до первого чата, а база под неё
 * потребовала бы миграции схемы ради семи строк.
 *
 * Ключ доступа лежит здесь вместе с остальными настройками — по требованию дня 30:
 * клиент обязан возвращаться в свой разговор после перезапуска, а без ключа чаты
 * сервиса он прочитать не может. Цена названа честно: `SharedPreferences` файла входит
 * в резервные копии приложения, и в них ключ тоже попадает.
 *
 * @param context Контекст приложения; хранится только файл настроек, а не сам контекст.
 */
class AndroidSettingsStorage(context: Context) : SettingsStorage {

    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun read(key: String): String? =
        preferences.getString(key, null)?.takeIf { it.isNotEmpty() }

    override fun write(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    private companion object {
        /**
         * Имя файла настроек. Отдельно от `chat-history.db`: у настроек и чатов разные
         * сроки жизни, и снос базы чатов не должен уносить адрес и ключ сервиса.
         */
        const val FILE_NAME = "ai-challenge-settings"
    }
}
