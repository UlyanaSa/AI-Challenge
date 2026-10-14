package com.osvin.aichallenge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.osvin.aichallenge.data.AndroidSettingsStorage
import com.osvin.aichallenge.data.RoomChatStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Хранилища приходят из точки входа, а не берутся внутри общего кода:
            // и Room, и SharedPreferences требуют Context, которого в слое репозитория
            // нет намеренно. Чаты и настройки сервиса лежат в разных местах — база
            // и файл настроек, — поэтому передаются отдельно
            App(
                store = RoomChatStore(applicationContext),
                settingsStorage = AndroidSettingsStorage(applicationContext)
            )
        }
    }
}
