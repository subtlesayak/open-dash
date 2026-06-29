package com.example.opendash.dash

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.opendash.data.VehicleStore
import com.example.opendash.util.DebugLog

data class DashCredentialSnapshot(
    val vehicleId: String,
    val ssid: String,
    val password: String,
)

data class DashConfigSnapshot(
    val ssidPrefix: String,
    val credentials: List<DashCredentialSnapshot>,
)

/**
 * Per-rider dash WiFi configuration, persisted on-device.
 *
 * OpenDash is meant to work on any compatible bike dash, not just the author's.
 * Every dash advertises a different SSID (e.g. `RE_P0RP_260525`, `RE_XXXX_yymmdd`) but
 * they all share the `RE_` prefix and the factory passphrase `12345678`. So out of the
 * box we connect by PREFIX (see [DashWifiManager]) — the rider just picks their dash from
 * the system dialog once — and then we remember that exact SSID here for direct reconnects.
 *
 * Everything is overridable in Settings for dashes that don't fit the defaults.
 */
class DashConfig private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val legacyPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val prefs = encryptedPrefsOrFallback()

    /** Broadest match across compatible dash variants; rider-overridable. */
    var ssidPrefix: String
        get() = prefs.getString(KEY_PREFIX, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
        set(v) = prefs.edit().putString(KEY_PREFIX, v).apply()

    /** The exact SSID once learned/entered. Empty = not yet known → discover by prefix. */
    var ssid: String
        get() = prefs.getString(vehicleKey(KEY_SSID), null)
            ?: legacySsidFallback()
        set(v) = prefs.edit().putString(vehicleKey(KEY_SSID), v).apply()

    var password: String
        get() = prefs.getString(vehicleKey(KEY_PASSWORD), null)
            ?: legacyPasswordFallback()
        set(v) = prefs.edit().putString(vehicleKey(KEY_PASSWORD), v).apply()

    /** True until a specific dash has been identified — connect by prefix discovery. */
    val needsDiscovery: Boolean get() = ssid.isBlank()

    /** Forget the learned dash so the next connect re-runs prefix discovery. */
    fun forgetDash() { ssid = "" }

    fun exportSnapshot(vehicleIds: List<String>): DashConfigSnapshot =
        DashConfigSnapshot(
            ssidPrefix = ssidPrefix,
            credentials = vehicleIds.map { vehicleId ->
                DashCredentialSnapshot(
                    vehicleId = vehicleId,
                    ssid = prefs.getString(vehicleKey(KEY_SSID, vehicleId), null)
                        ?: if (vehicleId == VehicleStore.DEFAULT_VEHICLE_ID) legacySsidFallback() else "",
                    password = prefs.getString(vehicleKey(KEY_PASSWORD, vehicleId), null)
                        ?: legacyPasswordFallback(),
                )
            },
        )

    fun importSnapshot(snapshot: DashConfigSnapshot) {
        prefs.edit().apply {
            putString(KEY_PREFIX, snapshot.ssidPrefix.ifBlank { DEFAULT_PREFIX })
            snapshot.credentials.forEach { credential ->
                putString(vehicleKey(KEY_SSID, credential.vehicleId), credential.ssid)
                putString(
                    vehicleKey(KEY_PASSWORD, credential.vehicleId),
                    credential.password.ifBlank { DEFAULT_PASSWORD },
                )
            }
        }.apply()
    }

    private fun vehicleKey(baseKey: String): String =
        vehicleKey(baseKey, VehicleStore.activeVehicleId.value)

    private fun vehicleKey(baseKey: String, vehicleId: String): String =
        "${baseKey}_vehicle_${vehicleId.sanitizePreferenceKey()}"

    private fun legacySsidFallback(): String =
        if (VehicleStore.activeVehicleId.value == VehicleStore.DEFAULT_VEHICLE_ID) {
            prefs.getString(KEY_SSID, "") ?: ""
        } else {
            ""
        }

    private fun legacyPasswordFallback(): String =
        prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD

    private fun encryptedPrefsOrFallback(): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also {
                migrateLegacyValues(it)
                migrateGlobalCredentialsToActiveVehicle(it)
            }
        }.getOrElse { error ->
            DebugLog.w(TAG) { "Encrypted dash_config unavailable; using fallback prefs (${error.javaClass.simpleName})" }
            showEncryptionWarning()
            legacyPrefs.also { migrateGlobalCredentialsToActiveVehicle(it) }
        }
    }

    private fun migrateLegacyValues(encryptedPrefs: SharedPreferences) {
        val legacyValues = listOf(KEY_PREFIX, KEY_SSID, KEY_PASSWORD)
            .mapNotNull { key -> legacyPrefs.getString(key, null)?.let { key to it } }
        if (legacyValues.isEmpty()) return

        encryptedPrefs.edit().apply {
            legacyValues.forEach { (key, value) -> putString(key, value) }
        }.apply()
        legacyPrefs.edit().apply {
            legacyValues.forEach { (key, _) -> remove(key) }
        }.apply()
    }

    private fun migrateGlobalCredentialsToActiveVehicle(targetPrefs: SharedPreferences) {
        val activeSsidKey = vehicleKey(KEY_SSID)
        val activePasswordKey = vehicleKey(KEY_PASSWORD)
        val globalSsid = targetPrefs.getString(KEY_SSID, null)
        val globalPassword = targetPrefs.getString(KEY_PASSWORD, null)

        if (!globalSsid.isNullOrBlank() && targetPrefs.getString(activeSsidKey, null).isNullOrBlank()) {
            targetPrefs.edit().putString(activeSsidKey, globalSsid).apply()
        }
        if (!globalPassword.isNullOrBlank() && targetPrefs.getString(activePasswordKey, null).isNullOrBlank()) {
            targetPrefs.edit().putString(activePasswordKey, globalPassword).apply()
        }
    }

    private fun showEncryptionWarning() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                appContext,
                "Dash WiFi settings are using fallback storage.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    companion object {
        private const val TAG = "DashConfig"
        private const val PREFS_NAME = "dash_config"
        private const val KEY_PREFIX   = "ssid_prefix"
        private const val KEY_SSID     = "ssid"
        private const val KEY_PASSWORD = "password"
        const val DEFAULT_PREFIX   = "RE_"
        const val DEFAULT_PASSWORD = "12345678"

        @Volatile private var instance: DashConfig? = null
        fun get(context: Context): DashConfig =
            instance ?: synchronized(this) {
                instance ?: DashConfig(context).also { instance = it }
            }
    }
}

private fun String.sanitizePreferenceKey(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_")
