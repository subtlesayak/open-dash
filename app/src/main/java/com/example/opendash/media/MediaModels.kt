package com.example.opendash.media

import android.app.PendingIntent
import android.graphics.Bitmap

data class NowPlaying(
    val title: String,
    val album: String,
    val artist: String,
    val art: Bitmap? = null,
)

data class IncomingCall(
    val caller: String,
    val incoming: Boolean = true,
    val answerIntent: PendingIntent? = null,
    val declineIntent: PendingIntent? = null,
)

data class RecentCall(
    val name: String?,
    val number: String,
    val type: Int,
    val timestampMs: Long,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: number.ifBlank { "Unknown" }
}
