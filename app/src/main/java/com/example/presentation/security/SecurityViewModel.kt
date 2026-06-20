package com.example.presentation.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SecurityViewModel(
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow<String>("loading") // "loading", "unlocked", "locked", "input"
    val uiState: StateFlow<String> = _uiState.asStateFlow()

    private val _errorEvent = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = _errorEvent.asStateFlow()

    init {
        checkLockState()
    }

    private fun checkLockState() {
        viewModelScope.launch {
            val isUnlocked = appPreferences.isUnlocked.first()
            val failedAttempts = appPreferences.failedAttempts.first()

            if (isUnlocked) {
                _uiState.value = "unlocked"
            } else if (failedAttempts >= 3) {
                _uiState.value = "locked"
            } else {
                _uiState.value = "input"
            }
        }
    }

    fun submitPassword(password: String) {
        viewModelScope.launch {
            val failedAttempts = appPreferences.failedAttempts.first()
            if (failedAttempts >= 3) {
                _uiState.value = "locked"
                return@launch
            }

            _uiState.value = "verifying"

            // Simulate verification delay
            kotlinx.coroutines.delay(1500)

            if (password == "MOHAMED564") {
                _uiState.value = "success_verified"
                
                // Show success state briefly before redirecting
                kotlinx.coroutines.delay(2000)
                
                appPreferences.setUnlocked(true)
                _uiState.value = "unlocked"
            } else {
                appPreferences.incrementFailedAttempts()
                val newFailed = failedAttempts + 1
                if (newFailed >= 3) {
                    _uiState.value = "locked"
                } else {
                    _uiState.value = "input"
                    val remaining = 3 - newFailed
                    _errorEvent.value = remaining.toString()
                }
            }
        }
    }

    fun clearError() {
        _errorEvent.value = null
    }
}
