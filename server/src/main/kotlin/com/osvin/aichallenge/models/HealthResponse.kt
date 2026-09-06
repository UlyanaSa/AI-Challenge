package com.osvin.aichallenge.models

import kotlinx.serialization.Serializable

/**
 * Статус здоровья сервера и внешних API.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val deepseek: String,
    val message: String? = null,
    val code: Int? = null
)
