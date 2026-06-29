package com.example.opendash.util

import android.content.Context
import com.example.opendash.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

object CrashReporter {
    private const val MAX_VALUE_LENGTH = 80
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (!BuildConfig.CRASHLYTICS_ENABLED) return
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) return
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
            initialized = true
        }.onFailure {
            initialized = false
            DebugLog.w("CrashReporter") { "Crashlytics unavailable: ${it.javaClass.simpleName}" }
        }
    }

    fun setKey(key: String, value: String) {
        if (!initialized) return
        runCatching {
            FirebaseCrashlytics.getInstance().setCustomKey(sanitizeKey(key), sanitizeValue(value))
        }
    }

    fun setUser(uid: String?, email: String?) {
        if (!initialized) return
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setUserId(uid.orEmpty())
            if (!email.isNullOrBlank()) {
                crashlytics.setCustomKey("user_email", sanitizeValue(email))
            }
        }
    }

    fun recordNonFatal(source: String, reason: String, throwable: Throwable? = null) {
        if (!initialized) return
        runCatching {
            val safeSource = sanitizeKey(source)
            val safeReason = sanitizeValue(reason)
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("source", safeSource)
            crashlytics.setCustomKey("reason", safeReason)
            crashlytics.recordException(
                NonFatalOpenDashException(
                    source = safeSource,
                    reason = safeReason,
                    causeType = throwable?.javaClass?.simpleName,
                ),
            )
        }
    }

    fun throwTestCrash(context: Context): Nothing {
        runCatching {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
                FirebaseCrashlytics.getInstance().setCustomKey("source", "manual_test")
                FirebaseCrashlytics.getInstance().setCustomKey("build_type", BuildConfig.BUILD_TYPE)
            }
        }
        throw RuntimeException("Test Crash")
    }

    private fun sanitizeKey(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9_\\-]"), "_")
            .trim('_')
            .take(MAX_VALUE_LENGTH)
            .ifBlank { "event" }

    private fun sanitizeValue(raw: String): String {
        val compact = raw
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("(?i)SSID matches '[^']+'"), "SSID matches [redacted]")
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
            .replace(Regex("\\+?\\d[\\d .()\\-]{7,}\\d"), "[number]")
            .replace(Regex("AIza[0-9A-Za-z_\\-]{20,}"), "AIza****")
            .replace(Regex("(?i)(password|token|secret|key|ssid)=\\S+"), "\$1=[redacted]")
            .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "[ip]")
            .trim()
        return compact.take(MAX_VALUE_LENGTH).ifBlank { "unspecified" }
    }

    private class NonFatalOpenDashException(
        source: String,
        reason: String,
        causeType: String?,
    ) : RuntimeException(
        buildString {
            append(source)
            append(": ")
            append(reason)
            causeType?.let {
                append(" (")
                append(it)
                append(")")
            }
        },
    )
}
