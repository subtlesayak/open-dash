package com.example.northstar.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.northstar.data.SharedLocation
import com.example.northstar.dash.nav.GeoPoint
import com.example.northstar.dash.nav.Route
import com.example.northstar.dash.nav.Router
import com.example.northstar.util.LocationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RouteState(
    val destination: SharedLocation? = null,
    val isResolving: Boolean = false,
    val pendingNavigate: Boolean = false,
    // Real routing results
    val route: Route? = null,
    val routing: Boolean = false,
    val distanceText: String? = null,   // "218 km"
    val durationText: String? = null,   // "4h 50m"
    val etaText: String? = null,        // "13:32"
)

class RouteViewModel(app: Application) : AndroidViewModel(app) {
    private val TAG = "RouteViewModel"
    private val _state = MutableStateFlow(RouteState())
    val state = _state.asStateFlow()

    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val repo = com.example.northstar.data.SyncRepository.get(app)

    private val _saved = MutableStateFlow<List<com.example.northstar.data.SavedLocation>>(emptyList())
    /** Saved destinations the rider can tap to load + navigate again. */
    val saved = _saved.asStateFlow()

    init {
        reloadSaved()
        // Reflect saves made locally OR synced in from another device.
        viewModelScope.launch { repo.revision.collect { reloadSaved() } }
    }

    private fun reloadSaved() = viewModelScope.launch {
        _saved.value = withContext(Dispatchers.IO) { repo.savedLocations() }
    }

    /** Save the current resolved destination so it can be reused. No-op without coords. */
    fun saveCurrentDestination(name: String, note: String = "") {
        val d = _state.value.destination ?: return
        val lat = d.lat ?: return
        val lng = d.lng ?: return
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addSaved(name.ifBlank { d.name }, lat, lng, note) } }
    }

    fun renameSaved(loc: com.example.northstar.data.SavedLocation, name: String, note: String) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.renameSaved(loc, name, note) } }

    fun deleteSaved(loc: com.example.northstar.data.SavedLocation) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteSaved(loc) } }

    /** Load a saved destination into the route preview (compute route; stay on the page). */
    fun selectSaved(loc: com.example.northstar.data.SavedLocation) {
        _state.value = RouteState(
            destination = SharedLocation(name = loc.name, lat = loc.lat, lng = loc.lng),
            isResolving = false,
            pendingNavigate = false,
        )
        computeRoute(loc.lat, loc.lng)
    }

    fun handleSharedText(text: String) {
        val loc = LocationParser.parse(text)
        _state.value = RouteState(
            destination = loc,
            isResolving = loc.needsExpansion,
            pendingNavigate = true,
        )
        if (loc.lat != null && loc.lng != null) {
            computeRoute(loc.lat, loc.lng)
        } else if (loc.url != null) {
            viewModelScope.launch {
                val (urlCoords, resolvedName) = LocationParser.resolve(loc.url)
                val name = when {
                    loc.name.isNotBlank() && loc.name != "Loading…" -> loc.name
                    !resolvedName.isNullOrBlank() -> resolvedName
                    urlCoords != null -> "Dropped pin"
                    else -> "Shared location"
                }
                // Named places (e.g. "Third Wave Coffee, … Hyderabad") have no coords in
                // the URL — geocode the resolved address to get lat/lng.
                val coords = urlCoords ?: resolvedName?.let { geocode(it) }
                val resolved = loc.copy(
                    name = name, lat = coords?.first, lng = coords?.second, needsExpansion = false,
                )
                _state.value = _state.value.copy(destination = resolved, isResolving = false)
                if (coords != null) computeRoute(coords.first, coords.second)
                else Log.w(TAG, "No coords for '$name' (url+geocode both empty)")
            }
        }
    }

    /** Geocode an address/place name to coordinates via the device's geocoder backend. */
    @Suppress("DEPRECATION")
    private suspend fun geocode(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) {
                Log.w(TAG, "No geocoder backend present")
                return@withContext null
            }
            val results = Geocoder(getApplication(), Locale.getDefault()).getFromLocationName(query, 1)
            val a = results?.firstOrNull() ?: run {
                Log.w(TAG, "Geocoder: no result for '$query'")
                return@withContext null
            }
            Log.i(TAG, "Geocoded '$query' → ${a.latitude},${a.longitude}")
            a.latitude to a.longitude
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder failed: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun computeRoute(destLat: Double, destLng: Double) {
        val origin = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull() ?: return

        _state.value = _state.value.copy(routing = true)
        viewModelScope.launch {
            val r = Router.route(
                GeoPoint(origin.latitude, origin.longitude),
                GeoPoint(destLat, destLng),
            )
            _state.value = if (r != null) _state.value.copy(
                route = r,
                routing = false,
                distanceText = fmtKm(r.totalMeters),
                durationText = fmtDuration(r.totalSeconds),
                etaText = fmtEta(r.totalSeconds),
            ) else _state.value.copy(routing = false)
        }
    }

    fun onNavigated() { _state.value = _state.value.copy(pendingNavigate = false) }
    fun clear() { _state.value = RouteState() }

    private fun fmtKm(m: Double) = "%.0f km".format(m / 1000.0)
    private fun fmtDuration(sec: Double): String {
        val total = (sec / 60.0).toInt()
        return if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
    }
    private fun fmtEta(sec: Double): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (sec * 1000).toLong()))
}
