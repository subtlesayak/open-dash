package com.example.northstar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.northstar.data.Ride
import com.example.northstar.data.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recorded rides for RidesScreen. Rides are written by [DashViewModel] on disconnect. */
class RidesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SyncRepository.get(app)

    private val _rides = MutableStateFlow<List<Ride>>(emptyList())
    val rides = _rides.asStateFlow()

    init {
        reload()
        // Reflect new rides + cross-device syncs.
        viewModelScope.launch { repo.revision.collect { reload() } }
    }

    private fun reload() = viewModelScope.launch {
        _rides.value = withContext(Dispatchers.IO) { repo.rides() }
    }

    fun deleteRide(r: Ride) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.deleteRide(r) }
    }
}
