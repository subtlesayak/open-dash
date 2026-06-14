package com.example.northstar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.northstar.data.FuelFillup
import com.example.northstar.data.MaintenanceItem
import com.example.northstar.data.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FuelRow(val fill: FuelFillup, val kmpl: Double?)
data class MaintRow(val item: MaintenanceItem, val remainingKm: Int, val tone: String)

data class GarageUi(
    val odometerKm: Int = 0,
    val fuel: List<FuelRow> = emptyList(),     // newest first; kmpl vs the prior fill
    val maint: List<MaintRow> = emptyList(),
    val avgKmpl30: Double? = null,
    val spent30: Double = 0.0,
    val litres30: Double = 0.0,
    val fills30: Int = 0,
)

class GarageViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SyncRepository.get(app)
    private val _ui = MutableStateFlow(GarageUi())
    val ui = _ui.asStateFlow()

    init {
        reload()
        // Reload whenever local OR synced-from-cloud data changes.
        viewModelScope.launch { repo.revision.collect { reload() } }
    }

    private fun reload() = viewModelScope.launch {
        val ui = withContext(Dispatchers.IO) { compute() }
        _ui.value = ui
        // Buzz if a service just crossed into "due" (de-duped inside the notifier).
        withContext(Dispatchers.IO) {
            com.example.northstar.data.MaintenanceNotifier.check(getApplication(), ui.maint.map { it.item }, ui.odometerKm)
        }
    }

    private fun compute(): GarageUi {
        val odo = repo.odometer()
        val fills = repo.fuelFills()   // highest odometer (newest) first
        val fuelRows = fills.mapIndexed { i, f ->
            val prev = fills.getOrNull(i + 1)   // next-lower odometer fill
            val kmpl = if (prev != null && f.litres > 0 && f.odometerKm > prev.odometerKm)
                (f.odometerKm - prev.odometerKm) / f.litres else null
            FuelRow(f, kmpl)
        }
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val recent = fuelRows.filter { it.fill.dateMs >= cutoff }
        val kmpls = recent.mapNotNull { it.kmpl }
        val maint = repo.maintenanceItems().map { m ->
            val remaining = m.lastDoneOdoKm + m.intervalKm - odo
            val tone = when {
                remaining < 0 -> "alert"
                remaining < m.intervalKm * 0.25 -> "warn"
                else -> "ok"
            }
            MaintRow(m, remaining, tone)
        }
        return GarageUi(
            odometerKm = odo,
            fuel = fuelRows,
            maint = maint,
            avgKmpl30 = kmpls.takeIf { it.isNotEmpty() }?.average(),
            spent30 = recent.sumOf { it.fill.cost },
            litres30 = recent.sumOf { it.fill.litres },
            fills30 = recent.size,
        )
    }

    fun addFuel(litres: Double, cost: Double, odometerKm: Int, location: String) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addFuel(litres, cost, odometerKm, location) } }

    fun deleteFuel(fill: FuelFillup) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteFuel(fill) } }

    fun markServiceDone(item: MaintenanceItem, odoKm: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.markServiceDone(item, odoKm) } }

    fun addService(name: String, iconKey: String, intervalKm: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addMaintenance(name, iconKey, intervalKm, repo.odometer()) } }

    fun deleteService(item: MaintenanceItem) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteMaintenance(item) } }

    fun setOdometer(km: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.setOdometer(km) } }
}
