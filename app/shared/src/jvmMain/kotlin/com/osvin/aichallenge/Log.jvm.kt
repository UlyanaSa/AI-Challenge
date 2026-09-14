package com.osvin.aichallenge

actual fun platformLog(tag: String, message: String) {
    println("[$tag] $message")
}
