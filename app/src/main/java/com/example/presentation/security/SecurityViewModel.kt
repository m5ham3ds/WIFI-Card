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

            if (password.equals("MOHAMED564", ignoreCase = false)) { // Requirement stated "MOHAMED564" explicitly, I will make it case sensitive or not depending on requirement. The user said "MOHAMED564" in prompt. Let's stick to exact match or ignore case? User specified "MOHAMED564".
                appPreferences.setUnlocked(true)
                _uiState.value = "unlocked"
            } else {
                appPreferences.incrementFailedAttempts()
                val newFailed = failedAttempts + 1
                if (newFailed >= 3) {
                    _uiState.value = "locked"
                } else {
                    val remaining = 3 - newFailed
                    _errorEvent.value = "كلمة المرور غير صحيحة، تبقى لك ${remaining} محاولة"
                }
            }
        }
    }

    fun clearError() {
        _errorEvent.value = null
    }
}
