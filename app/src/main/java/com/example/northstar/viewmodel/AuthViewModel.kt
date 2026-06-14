package com.example.northstar.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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
    private val _state = MutableStateFlow(AuthState())
    val state = _state.asStateFlow()

    // Null when Firebase isn't configured (no google-services.json) — keeps the whole VM
    // crash-safe and lets the UI show "local only".
    private val auth: FirebaseAuth? = runCatching { FirebaseAuth.getInstance() }.getOrNull()

    /** Build state from the live Firebase user (real email + name for Settings). */
    private fun signedInState(): AuthState = auth?.currentUser.let { u ->
        AuthState(isSignedIn = u != null, email = u?.email, displayName = u?.displayName, syncAvailable = auth != null)
    }

    init { runCatching { _state.value = signedInState() } }

    fun signInWithGoogle(idToken: String) {
        val a = auth ?: run { _state.value = _state.value.copy(error = "Sync not configured", syncAvailable = false); return }
        _state.value = AuthState(loading = true)
        viewModelScope.launch {
            runCatching {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                a.signInWithCredential(credential).await()
                _state.value = signedInState()
            }.onFailure { e ->
                _state.value = AuthState(error = e.message ?: "Sign-in failed")
            }
        }
    }

    fun signOut() {
        runCatching { auth?.signOut() }
        _state.value = AuthState(isSignedIn = false, syncAvailable = auth != null)
    }
}
