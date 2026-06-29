package com.example.opendash.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.example.opendash.util.DebugLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object CallInfoProvider {
    private const val TAG = "CallInfoProvider"

    private val _incomingCall = MutableStateFlow<IncomingCall?>(null)
    val incomingCall = _incomingCall.asStateFlow()

    fun update(call: IncomingCall?) {
        _incomingCall.value = call
    }

    fun hasCallLogPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    fun recentCalls(context: Context, limit: Int = 3): List<RecentCall> {
        if (!hasCallLogPermission(context)) return emptyList()
        val projection = arrayOf(
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
        )
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        add(
                            RecentCall(
                                name = cursor.getStringOrNull(nameIndex),
                                number = cursor.getStringOrNull(numberIndex).orEmpty(),
                                type = cursor.getIntOrDefault(typeIndex, CallLog.Calls.INCOMING_TYPE),
                                timestampMs = cursor.getLongOrDefault(dateIndex, 0L),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.onFailure {
            DebugLog.w(TAG) { "Call log read failed: ${it.javaClass.simpleName}" }
        }.getOrDefault(emptyList())
    }

    fun latestRecentCallLabel(context: Context): String {
        val latest = recentCalls(context, 1).firstOrNull()
        if (latest != null) return dashLabel(latest)
        return if (hasCallLogPermission(context)) "No recent calls" else "Allow call log"
    }

    fun dashLabel(call: RecentCall): String = call.toDashLabel()

    private fun RecentCall.toDashLabel(): String {
        val time = timestampMs.takeIf { it > 0L }?.let {
            SimpleDateFormat("d MMM h:mm a", Locale.getDefault()).format(Date(it))
        }
        val label = buildString {
            append(typeLabel(type))
            append(": ")
            append(displayName)
            if (time != null) {
                append(" ")
                append(time)
            }
        }
        return label.take(32)
    }

    private fun typeLabel(type: Int): String = when (type) {
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.REJECTED_TYPE -> "Rejected"
        else -> "Incoming"
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    private fun android.database.Cursor.getIntOrDefault(index: Int, fallback: Int): Int =
        if (index >= 0 && !isNull(index)) getInt(index) else fallback

    private fun android.database.Cursor.getLongOrDefault(index: Int, fallback: Long): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else fallback
}
