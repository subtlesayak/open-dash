package com.example.opendash.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opendash.data.FirebaseGate
import com.example.opendash.data.SyncRepository
import com.example.opendash.util.CrashReporter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class AuthState(
    val isSignedIn: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val uid: String? = null,
    /** False when Firebase is not configured for this application id. */
    val syncAvailable: Boolean = true,
    val syncActive: Boolean = false,
    val syncStatus: String = "Local only",
)

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app.applicationContext
    private val sync = SyncRepository.get(appContext)
    private val firebaseConfigured = FirebaseGate.isConfigured(appContext)
    private val googleSignInConfigured = FirebaseGate.canUseGoogleSignIn(appContext)
    private val auth: FirebaseAuth? = if (firebaseConfigured) FirebaseAuth.getInstance() else null

    private val _state = MutableStateFlow(
        AuthState(
            syncAvailable = googleSignInConfigured,
            syncStatus = when {
                !firebaseConfigured -> "Add google-services.json to enable cloud sync"
                !googleSignInConfigured -> "Add GOOGLE_WEB_CLIENT_ID to enable Google sign-in"
                else -> "Ready to sync"
            },
        ),
    )
    val state = _state.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        applyUser(firebaseAuth.currentUser)
    }

    init {
        auth?.addAuthStateListener(authListener)
        applyUser(auth?.currentUser)
    }

    fun signInWithGoogle(idToken: String) {
        val firebaseAuth = auth
        if (!googleSignInConfigured || firebaseAuth == null) {
            _state.update {
                it.copy(
                    loading = false,
                    error = "Google sign-in is not configured for this build",
                    syncAvailable = googleSignInConfigured,
                    syncActive = false,
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, syncStatus = "Signing in...") }
            runCatching {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                firebaseAuth.signInWithCredential(credential).await()
                sync.startSync()
                sync.pushProfileSettings()
            }.onSuccess {
                _state.update { it.copy(loading = false, error = null, syncStatus = "Sync active") }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "Google sign-in failed",
                        syncStatus = "Sign-in failed",
                    )
                }
                CrashReporter.recordNonFatal("auth", "google_sign_in_failed", error)
            }
        }
    }

    fun syncNow() {
        if (!firebaseConfigured || auth?.currentUser == null) {
            _state.update { it.copy(error = "Sign in to sync OpenDash data") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, error = null, syncStatus = "Syncing...") }
            runCatching {
                sync.pushProfileSettings()
                sync.startSync()
            }.onSuccess {
                _state.update { it.copy(loading = false, syncStatus = "Sync active") }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "Sync failed",
                        syncStatus = "Sync failed",
                    )
                }
                CrashReporter.recordNonFatal("sync", "manual_sync_failed", error)
            }
        }
    }

    fun signOut() {
        sync.stopSync()
        auth?.signOut()
        CrashReporter.setUser(null, null)
        _state.update {
            it.copy(
                isSignedIn = false,
                loading = false,
                error = null,
                email = null,
                displayName = null,
                uid = null,
                syncActive = false,
                syncStatus = if (googleSignInConfigured) "Signed out" else it.syncStatus,
            )
        }
    }

    override fun onCleared() {
        auth?.removeAuthStateListener(authListener)
        super.onCleared()
    }

    private fun applyUser(user: FirebaseUser?) {
        if (user == null) {
            CrashReporter.setUser(null, null)
            _state.update {
                it.copy(
                    isSignedIn = false,
                    loading = false,
                    email = null,
                    displayName = null,
                    uid = null,
                    syncActive = false,
                    syncAvailable = googleSignInConfigured,
                    syncStatus = if (googleSignInConfigured) "Ready to sync" else it.syncStatus,
                )
            }
            return
        }
        CrashReporter.setUser(user.uid, user.email)
        sync.startSync()
        _state.update {
            it.copy(
                isSignedIn = true,
                loading = false,
                error = null,
                email = user.email,
                displayName = user.displayName,
                uid = user.uid,
                syncAvailable = googleSignInConfigured,
                syncActive = true,
                syncStatus = "Sync active",
            )
        }
    }
}
