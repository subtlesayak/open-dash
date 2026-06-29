package com.example.opendash.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.example.opendash.util.DebugLog

class CallController(private val context: Context) {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasDirectCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    fun placeCall(number: String): Boolean {
        val cleanNumber = number.trim()
        if (cleanNumber.isBlank()) return false
        val telUri = Uri.fromParts("tel", cleanNumber, null)
        val action = if (hasDirectCallPermission()) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return runCatching {
            val intent = Intent(action, telUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            @Suppress("MissingPermission")
            context.startActivity(intent)
            true
        }.recoverCatching {
            val fallback = Intent(Intent.ACTION_DIAL, telUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
            true
        }.onFailure {
            DebugLog.w(TAG) { "Place call failed: ${it.javaClass.simpleName}" }
        }.getOrDefault(false)
    }

    fun placeDirectCall(number: String): Boolean {
        val cleanNumber = number.trim()
        if (cleanNumber.isBlank() || !hasDirectCallPermission()) return false
        val telUri = Uri.fromParts("tel", cleanNumber, null)
        return runCatching {
            val intent = Intent(Intent.ACTION_CALL, telUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            @Suppress("MissingPermission")
            context.startActivity(intent)
            true
        }.onFailure {
            DebugLog.w(TAG) { "Direct call failed: ${it.javaClass.simpleName}" }
        }.getOrDefault(false)
    }

    fun answer(): Boolean {
        if (!hasPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            @Suppress("MissingPermission")
            telecom?.acceptRingingCall()
            true
        }.onFailure { DebugLog.w(TAG) { "Call answer failed: ${it.javaClass.simpleName}" } }
            .getOrDefault(false)
    }

    fun hangup(): Boolean {
        if (!hasPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            @Suppress("MissingPermission")
            telecom?.endCall() ?: false
        }.onFailure { DebugLog.w(TAG) { "Call end failed: ${it.javaClass.simpleName}" } }
            .getOrDefault(false)
    }

    fun volumeUp(): Boolean = adjustCallVolume(AudioManager.ADJUST_RAISE)

    fun volumeDown(): Boolean = adjustCallVolume(AudioManager.ADJUST_LOWER)

    private fun adjustCallVolume(direction: Int): Boolean = runCatching {
        audio.adjustStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            direction,
            AudioManager.FLAG_SHOW_UI,
        )
        true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "CallController"
    }
}
