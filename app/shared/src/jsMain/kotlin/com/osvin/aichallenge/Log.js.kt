package com.osvin.aichallenge

actual fun platformLog(tag: String, message: String) {
    console.log("[$tag] $message")
}
