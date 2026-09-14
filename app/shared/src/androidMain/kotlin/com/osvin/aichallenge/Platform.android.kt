package com.osvin.aichallenge

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

/** На эмуляторе хост-машина доступна по адресу 10.0.2.2. */
actual fun serverBaseUrl(): String = "http://10.0.2.2:8080"
