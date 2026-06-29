package com.example.opendash.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class VehicleProfile(
    val id: String,
    val title: String,
    val nickname: String,
    val puc: String,
    val insurance: String,
    val service: String,
)

data class VehicleStoreSnapshot(
    val vehicles: List<VehicleProfile>,
    val activeVehicleId: String,
)

object VehicleStore {
    const val DEFAULT_VEHICLE_ID = "default"
    private const val PREFS = "vehicle_profiles"
    private const val KEY_VEHICLES = "vehicles"
    private const val KEY_ACTIVE = "active_vehicle"

    private val defaultVehicle = VehicleProfile(
        id = DEFAULT_VEHICLE_ID,
        title = "Himalayan 450",
        nickname = "Default vehicle",
        puc = "Not set",
        insurance = "Not set",
        service = "Not set",
    )

    private val _vehicles = MutableStateFlow(listOf(defaultVehicle))
    val vehicles = _vehicles.asStateFlow()
    private val _activeVehicleId = MutableStateFlow(DEFAULT_VEHICLE_ID)
    val activeVehicleId = _activeVehicleId.asStateFlow()
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val loaded = runCatching {
            val array = JSONArray(prefs.getString(KEY_VEHICLES, "[]"))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        VehicleProfile(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            title = item.optString("title").ifBlank { "Motorcycle" },
                            nickname = item.optString("nickname"),
                            puc = item.optString("puc", "Not set"),
                            insurance = item.optString("insurance", "Not set"),
                            service = item.optString("service", "Not set"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList()).ifEmpty { listOf(defaultVehicle) }
        _vehicles.value = loaded
        _activeVehicleId.value = prefs.getString(KEY_ACTIVE, loaded.first().id)
            ?.takeIf { id -> loaded.any { it.id == id } }
            ?: loaded.first().id
        initialized = true
        persist(context, sync = false)
    }

    fun activeVehicle(): VehicleProfile =
        _vehicles.value.firstOrNull { it.id == _activeVehicleId.value } ?: _vehicles.value.first()

    fun add(context: Context, profile: VehicleProfile) {
        val created = profile.copy(id = profile.id.ifBlank { UUID.randomUUID().toString() })
        _vehicles.value = _vehicles.value + created
        _activeVehicleId.value = created.id
        persist(context, sync = true)
    }

    fun update(context: Context, profile: VehicleProfile) {
        _vehicles.value = _vehicles.value.map { if (it.id == profile.id) profile else it }
        persist(context, sync = true)
    }

    fun select(context: Context, vehicleId: String) {
        if (_vehicles.value.none { it.id == vehicleId }) return
        _activeVehicleId.value = vehicleId
        persist(context, sync = true)
    }

    fun snapshot(): VehicleStoreSnapshot =
        VehicleStoreSnapshot(
            vehicles = _vehicles.value,
            activeVehicleId = _activeVehicleId.value,
        )

    @Synchronized
    fun applySnapshot(context: Context, snapshot: VehicleStoreSnapshot) {
        val loaded = snapshot.vehicles.ifEmpty { listOf(defaultVehicle) }
        _vehicles.value = loaded
        _activeVehicleId.value = snapshot.activeVehicleId
            .takeIf { id -> loaded.any { it.id == id } }
            ?: loaded.first().id
        persist(context, sync = false)
    }

    private fun persist(context: Context, sync: Boolean) {
        val array = JSONArray()
        _vehicles.value.forEach { vehicle ->
            array.put(
                JSONObject()
                    .put("id", vehicle.id)
                    .put("title", vehicle.title)
                    .put("nickname", vehicle.nickname)
                    .put("puc", vehicle.puc)
                    .put("insurance", vehicle.insurance)
                    .put("service", vehicle.service)
            )
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VEHICLES, array.toString())
            .putString(KEY_ACTIVE, _activeVehicleId.value)
            .apply()
        if (sync) {
            Thread {
                runCatching { SyncRepository.get(context.applicationContext).pushProfileSettings() }
            }.start()
        }
    }
}
