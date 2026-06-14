package com.example.northstar.data

import android.content.Context
import com.google.firebase.FirebaseApp

/**
 * Single source of truth for "is Firebase even available?". Firebase is optional
 * (bring-your-own-project): without a google-services.json the default [FirebaseApp] is
 * never initialized, so every Firebase call would throw. Gate all Firebase access behind
 * [isConfigured] and the app runs fully local — sync simply doesn't exist.
 */
object FirebaseGate {
    /** True only when a google-services.json was present at build time and Firebase initialized. */
    fun isConfigured(context: Context): Boolean =
        FirebaseApp.getApps(context.applicationContext).isNotEmpty()
}
