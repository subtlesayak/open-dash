package com.example.opendash.data

import android.content.Context

/**
 * Single source of truth for "is Firebase sync available?".
 *
 * OpenDash stores rider data local-only. Keep this disabled even if a developer has
 * Firebase dependencies or a google-services.json in a local experiment.
 */
object FirebaseGate {
    fun isConfigured(context: Context): Boolean = false
}
