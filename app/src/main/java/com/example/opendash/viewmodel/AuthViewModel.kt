package com.example.opendash.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuthState(
    val isSignedIn: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    /** False when Firebase isn't configured (bring-your-own-project) — sync is unavailable. */
    val syncAvailable: Boolean = true,
)

class AuthViewModel : ViewModel() {
    private val _state = MutableStateFlow(AuthState(syncAvailable = false))
    val state = _state.asStateFlow()

    fun signInWithGoogle(idToken: String) {
        _state.value = AuthState(error = "Sync is disabled; OpenDash data stays local", syncAvailable = false)
    }

    fun signOut() {
        _state.value = AuthState(isSignedIn = false, syncAvailable = false)
    }
}
