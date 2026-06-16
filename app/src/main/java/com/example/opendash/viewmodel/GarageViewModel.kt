package com.example.opendash.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opendash.data.Expense
import com.example.opendash.data.FuelFillup
import com.example.opendash.data.MaintenanceItem
import com.example.opendash.data.SyncRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val expenses: List<Expense> = emptyList(),
    val avgKmpl30: Double? = null,
    val spent30: Double = 0.0,
    val litres30: Double = 0.0,
    val fills30: Int = 0,
    val expensesTotal: Double = 0.0,
    val expenses30: Double = 0.0,
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
            com.example.opendash.data.MaintenanceNotifier.check(getApplication(), ui.maint.map { it.item }, ui.odometerKm)
        }
    }

    private fun compute(): GarageUi {
        val odo = repo.odometer()
        val fills = repo.fuelFills()   // highest odometer (newest) first
        val expenses = repo.expenses()
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
            expenses = expenses,
            avgKmpl30 = kmpls.takeIf { it.isNotEmpty() }?.average(),
            spent30 = recent.sumOf { it.fill.cost },
            litres30 = recent.sumOf { it.fill.litres },
            fills30 = recent.size,
            expensesTotal = expenses.sumOf { it.amount },
            expenses30 = expenses.filter { it.dateMs >= cutoff }.sumOf { it.amount },
        )
    }

    fun addFuel(litres: Double, cost: Double, odometerKm: Int, location: String) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addFuel(litres, cost, odometerKm, location) } }

    fun deleteFuel(fill: FuelFillup) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteFuel(fill) } }

    fun addExpense(category: String, amount: Double, note: String) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addExpense(category, amount, note) } }

    fun deleteExpense(expense: Expense) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteExpense(expense) } }

    fun markServiceDone(item: MaintenanceItem, odoKm: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.markServiceDone(item, odoKm) } }

    fun addService(name: String, iconKey: String, intervalKm: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addMaintenance(name, iconKey, intervalKm, repo.odometer()) } }

    fun deleteService(item: MaintenanceItem) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteMaintenance(item) } }

    fun setOdometer(km: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.setOdometer(km) } }

    suspend fun exportExpensesCsv(): File = withContext(Dispatchers.IO) {
        val file = exportFile("opendash-expenses.csv")
        file.writeText(buildString {
            appendLine("Date,Category,Amount,Note")
            repo.expenses().forEach { e ->
                appendLine(
                    listOf(
                        exportDate(e.dateMs),
                        e.category,
                        "%.2f".format(Locale.US, e.amount),
                        e.note,
                    ).joinToString(",") { csvCell(it) }
                )
            }
        })
        file
    }

    suspend fun exportExpensesDoc(): File = withContext(Dispatchers.IO) {
        val file = exportFile("opendash-expenses.doc")
        val expenses = repo.expenses()
        file.writeText(
            buildString {
                appendLine("<html><head><meta charset=\"utf-8\"><title>OpenDash Expenses</title></head><body>")
                appendLine("<h1>OpenDash Expenses</h1>")
                appendLine("<p>Total: ₹${"%,.2f".format(expenses.sumOf { it.amount })}</p>")
                appendLine("<table border=\"1\" cellspacing=\"0\" cellpadding=\"6\">")
                appendLine("<tr><th>Date</th><th>Category</th><th>Amount</th><th>Note</th></tr>")
                expenses.forEach { e ->
                    appendLine("<tr><td>${exportDate(e.dateMs)}</td><td>${html(e.category)}</td><td>₹${"%,.2f".format(e.amount)}</td><td>${html(e.note)}</td></tr>")
                }
                appendLine("</table></body></html>")
            }
        )
        file
    }

    private fun exportFile(name: String): File {
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        return File(dir, name)
    }

    private fun exportDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    private fun csvCell(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""

    private fun html(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
