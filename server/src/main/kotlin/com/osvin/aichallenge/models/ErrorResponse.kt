package com.osvin.aichallenge.models

import kotlinx.serialization.Serializable

/**
 * Модель ошибки для передачи клиенту.
 */
@Serializable
data class ErrorResponse(
    val success: Boolean,
    val error: String
)
