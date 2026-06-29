package com.example.opendash.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.example.opendash.util.DebugLog

class PhoneCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            ?.takeIf { it.isNotBlank() }

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING ->
                CallInfoProvider.update(IncomingCall(number ?: "Incoming call"))
            TelephonyManager.EXTRA_STATE_OFFHOOK ->
                CallInfoProvider.update(IncomingCall(number ?: "On call", incoming = false))
            TelephonyManager.EXTRA_STATE_IDLE ->
                CallInfoProvider.update(null)
        }
        DebugLog.i(TAG) { "Phone state: $state" }
    }

    companion object {
        private const val TAG = "PhoneCallReceiver"
    }
}
