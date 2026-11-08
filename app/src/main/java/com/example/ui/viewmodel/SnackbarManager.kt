package com.example.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared coordinator for transient user notifications across feature-scoped ViewModels.
 */
class SnackbarManager {
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    fun showMessage(message: String) {
        _messages.value = message
    }

    fun clearMessage() {
        _messages.value = null
    }
}
