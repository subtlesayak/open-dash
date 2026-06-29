package com.example.opendash.data

import android.content.Context
import com.example.opendash.BuildConfig
import com.google.firebase.FirebaseApp

/**
 * Single source of truth for "is Firebase sync available?".
 *
 * Firebase is bring-your-own-project. If the Google Services plugin did not initialize
 * Firebase for this application id, every auth/sync path stays disabled and OpenDash
 * remains local-only.
 */
object FirebaseGate {
    fun isConfigured(context: Context): Boolean =
        runCatching { FirebaseApp.getApps(context.applicationContext).isNotEmpty() }
            .getOrDefault(false)

    fun canUseGoogleSignIn(context: Context): Boolean =
        isConfigured(context) && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()
}
