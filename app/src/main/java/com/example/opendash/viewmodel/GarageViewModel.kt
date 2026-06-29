package com.example.opendash.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opendash.data.Expense
import com.example.opendash.data.FuelFillup
import com.example.opendash.data.Himalayan450MaintenanceSchedule
import com.example.opendash.data.MaintenanceItem
import com.example.opendash.data.OfficialMaintenanceSchedule
import com.example.opendash.data.OpenDashCurrency
import com.example.opendash.data.SyncRepository
import com.example.opendash.data.VehicleStore
import com.example.opendash.data.formatCurrencyAmount
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FuelRow(val fill: FuelFillup, val kmpl: Double?)
data class MaintRow(
    val item: MaintenanceItem,
    val remainingKm: Int,
    val remainingDays: Long?,
    val tone: String,
    val officialSchedule: OfficialMaintenanceSchedule?,
)

data class GarageUi(
    val activeVehicleId: String = VehicleStore.DEFAULT_VEHICLE_ID,
    val activeVehicleName: String = "Himalayan 450",
    val odometerKm: Int = 0,
    val fuel: List<FuelRow> = emptyList(),     // newest first; kmpl vs the prior fill
    val maint: List<MaintRow> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val avgKmpl30: Double? = null,
    val avgKmplLast5: Double? = null,
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
        VehicleStore.init(app)
        reload()
        // Reload whenever local OR synced-from-cloud data changes.
        viewModelScope.launch { repo.revision.collect { reload() } }
        viewModelScope.launch {
            VehicleStore.activeVehicleId.collect { vehicleId ->
                withContext(Dispatchers.IO) { repo.ensureMaintenance(vehicleId) }
                reload()
            }
        }
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
        val vehicle = VehicleStore.activeVehicle()
        val fills = repo.fuelFills(vehicle.id)   // highest odometer (newest) first
        val odo = maxOf(repo.odometer(vehicle.id), fills.maxOfOrNull { it.odometerKm } ?: 0)
        val expenses = repo.expenses(vehicle.id)
        val fuelRows = fills.mapIndexed { i, f ->
            val prev = fills.getOrNull(i + 1)   // next-lower odometer fill
            val kmpl = if (prev != null && f.litres > 0 && f.odometerKm > prev.odometerKm)
                (f.odometerKm - prev.odometerKm) / f.litres else null
            FuelRow(f, kmpl)
        }
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val recent = fuelRows.filter { it.fill.dateMs >= cutoff }
        val kmpls = recent.mapNotNull { it.kmpl }
        val maint = repo.maintenanceItems(vehicle.id).map { m ->
            val official = Himalayan450MaintenanceSchedule.forItem(m)
            val remaining = m.lastDoneOdoKm + m.intervalKm - odo
            val intervalMonths = official?.intervalMonths
            val remainingDays = intervalMonths?.let { months ->
                val dueAt = Calendar.getInstance().apply {
                    timeInMillis = m.lastDoneDateMs
                    add(Calendar.MONTH, months)
                }.timeInMillis
                kotlin.math.ceil((dueAt - System.currentTimeMillis()) / 86_400_000.0).toLong()
            }
            val timeWarning = intervalMonths?.let { months ->
                remainingDays?.let { days -> days < months * 30 * 0.25 }
            } == true
            val tone = when {
                remaining < 0 || (remainingDays != null && remainingDays < 0) -> "alert"
                remaining < m.intervalKm * 0.25 || timeWarning -> "warn"
                else -> "ok"
            }
            MaintRow(m, remaining, remainingDays, tone, official)
        }
        return GarageUi(
            activeVehicleId = vehicle.id,
            activeVehicleName = vehicle.title,
            odometerKm = odo,
            fuel = fuelRows,
            maint = maint,
            expenses = expenses,
            avgKmpl30 = kmpls.takeIf { it.isNotEmpty() }?.average(),
            avgKmplLast5 = fuelRows.mapNotNull { it.kmpl }.take(5).takeIf { it.isNotEmpty() }?.average(),
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

    fun addExpense(category: String, amount: Double, note: String, dateMs: Long = System.currentTimeMillis()) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addExpense(category, amount, note, dateMs) } }

    fun updateExpense(expense: Expense, category: String, amount: Double, note: String, dateMs: Long) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.upsertExpense(
                    expense.copy(
                        category = category,
                        amount = amount,
                        note = note,
                        dateMs = dateMs,
                    )
                )
            }
        }

    fun duplicateExpense(expense: Expense) =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.addExpense(expense.category, expense.amount, expense.note, expense.dateMs, expense.vehicleId)
            }
        }

    fun deleteExpense(expense: Expense) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteExpense(expense) } }

    fun importExpensesCsv(uri: Uri, onResult: (Int, String?) -> Unit) =
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { importCsvInternal(uri) }
            onResult(result.first, result.second)
        }

    fun markServiceDone(item: MaintenanceItem, odoKm: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.markServiceDone(item, odoKm) } }

    fun logService(item: MaintenanceItem, odoKm: Int, intervalKm: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.logService(item, odoKm, intervalKm) } }

    fun addService(name: String, iconKey: String, intervalKm: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addMaintenance(name, iconKey, intervalKm, repo.odometer()) } }

    fun deleteService(item: MaintenanceItem) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteMaintenance(item) } }

    fun setOdometer(km: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.setOdometer(km) } }

    suspend fun exportExpensesCsv(
        expenses: List<Expense>? = null,
        periodLabel: String = "all-time",
        currency: OpenDashCurrency = OpenDashCurrency.INR,
    ): File = withContext(Dispatchers.IO) {
        val selected = expenses ?: repo.expenses()
        val file = exportFile("opendash-expenses-$periodLabel.csv")
        file.writeText(buildString {
            appendLine("Date,Odometer_km,Distance_Covered_km,Fuel_L,Cost_${currency.code},Fuel_Price_per_L_${currency.code},Category,Note")
            selected.forEach { e ->
                val fuel = fuelExpenseFields(e.note)
                appendLine(
                    listOf(
                        exportDate(e.dateMs),
                        fuel["odometer"].orEmpty(),
                        fuel["distance"].orEmpty(),
                        fuel["fuel"].orEmpty(),
                        "%.2f".format(Locale.US, e.amount),
                        fuel["price"].orEmpty(),
                        e.category,
                        e.note,
                    ).joinToString(",") { csvCell(it) }
                )
            }
        })
        file
    }

    suspend fun exportExpensesDoc(
        expenses: List<Expense>? = null,
        periodLabel: String = "All time",
        currency: OpenDashCurrency = OpenDashCurrency.INR,
    ): File = withContext(Dispatchers.IO) {
        val selected = expenses ?: repo.expenses()
        val file = exportFile("opendash-expenses-${periodLabel.lowercase(Locale.US).replace(' ', '-')}.doc")
        file.writeText(
            buildString {
                appendLine("<html><head><meta charset=\"utf-8\"><title>OpenDash Expenses</title></head><body>")
                appendLine("<h1>OpenDash Expenses - ${html(periodLabel)}</h1>")
                appendLine("<p>Total: ${html(formatCurrencyAmount(selected.sumOf { it.amount }, currency, 2))}</p>")
                appendLine("<table border=\"1\" cellspacing=\"0\" cellpadding=\"6\">")
                appendLine("<tr><th>Date</th><th>Category</th><th>Amount</th><th>Note</th></tr>")
                selected.forEach { e ->
                    appendLine("<tr><td>${exportDate(e.dateMs)}</td><td>${html(e.category)}</td><td>${html(formatCurrencyAmount(e.amount, currency, 2))}</td><td>${html(e.note)}</td></tr>")
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

    private fun importCsvInternal(uri: Uri): Pair<Int, String?> {
        val resolver = getApplication<Application>().contentResolver
        val rows = resolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).readLines()
        }.orEmpty().filter { it.isNotBlank() }
        if (rows.isEmpty()) return 0 to "CSV file is empty"

        val header = parseCsvLine(rows.first()).map { it.trim().lowercase(Locale.US) }
        val hasHeader = header.any { it in setOf("date", "category", "amount", "note", "odometer_km", "cost_rs") }
        val dataRows = if (hasHeader) rows.drop(1) else rows
        val activeVehicleId = VehicleStore.activeVehicleId.value
        var imported = 0

        dataRows.forEach { line ->
            val cells = parseCsvLine(line)
            if (cells.isEmpty()) return@forEach
            val date = valueFor(cells, header, hasHeader, "date", 0)
            val odometer = valueFor(cells, header, hasHeader, "odometer_km", 1)
            val distance = valueFor(cells, header, hasHeader, "distance_covered_km", 2)
            val fuel = valueFor(cells, header, hasHeader, "fuel_l", 3)
            val cost = valueFor(cells, header, hasHeader, "cost_rs", 4)
            val fuelPrice = valueFor(cells, header, hasHeader, "fuel_price_per_l_rs", 5)
            val categoryRaw = valueFor(cells, header, hasHeader, "category", 6)
            val noteRaw = valueFor(cells, header, hasHeader, "note", 7)
            val fuelShaped = hasHeader && header.any { it in setOf("odometer_km", "fuel_l", "cost_rs", "fuel_price_per_l_rs") }
            val category = normalizeExpenseCategory(if (fuelShaped && categoryRaw.isBlank()) "Fuel" else categoryRaw)
            val amount = (if (fuelShaped) cost else valueFor(cells, header, hasHeader, "amount", 2))
                .replace("₹", "")
                .replace(currencySymbolsRegex, "")
                .replace(",", "")
                .trim()
                .toDoubleOrNull()
            val note = if (fuelShaped) {
                buildFuelImportNote(
                    date = date,
                    odometer = odometer,
                    distance = distance,
                    fuel = fuel,
                    fuelPrice = fuelPrice,
                    note = noteRaw,
                )
            } else {
                noteRaw.ifBlank { valueFor(cells, header, hasHeader, "note", 3) }
            }
            val dateMs = parseImportDate(date) ?: System.currentTimeMillis()
            if (amount != null && amount > 0.0) {
                repo.addExpense(category, amount, note, dateMs, activeVehicleId)
                imported++
            }
        }
        return if (imported > 0) imported to null else 0 to "No valid expenses found"
    }

    private fun valueFor(cells: List<String>, header: List<String>, hasHeader: Boolean, key: String, fallbackIndex: Int): String {
        val index = if (hasHeader) header.indexOf(key).takeIf { it >= 0 } ?: fallbackIndex else fallbackIndex
        return cells.getOrNull(index).orEmpty().trim()
    }

    private fun buildFuelImportNote(
        date: String,
        odometer: String,
        distance: String,
        fuel: String,
        fuelPrice: String,
        note: String,
    ): String = buildList {
        if (date.isNotBlank()) add(date)
        if (odometer.isNotBlank()) add("Odometer: ${odometer.trim()} km")
        if (distance.isNotBlank()) add("Distance covered: ${distance.trim()} km")
        if (fuel.isNotBlank()) add("Fuel: ${fuel.trim()} L")
        if (fuelPrice.isNotBlank()) add("Fuel price/L: ${fuelPrice.trim()}")
        if (note.isNotBlank()) add(note.trim())
    }.joinToString(" · ")

    private fun fuelExpenseFields(note: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        note.split(" · ").map { it.trim() }.forEach { part ->
            when {
                part.startsWith("Odometer:", ignoreCase = true) ->
                    out["odometer"] = part.substringAfter(':').replace("km", "", ignoreCase = true).trim()
                part.startsWith("Distance covered:", ignoreCase = true) ->
                    out["distance"] = part.substringAfter(':').replace("km", "", ignoreCase = true).trim()
                part.startsWith("Fuel:", ignoreCase = true) ->
                    out["fuel"] = part.substringAfter(':').replace("L", "", ignoreCase = true).trim()
                part.startsWith("Fuel price/L:", ignoreCase = true) ->
                    out["price"] = part.substringAfter(':').replace(currencySymbolsRegex, "").trim()
            }
        }
        return out
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out += cell.toString()
                    cell.clear()
                }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    private fun parseImportDate(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val formats = listOf("yyyy-MM-dd", "d-MMM-yyyy", "d-MMM-yyyy hh:mm a", "dd/MM/yyyy", "dd-MM-yyyy")
        return formats.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.getDefault()).apply { isLenient = false }.parse(trimmed)?.time
            }.getOrNull()
        }
    }

    private fun normalizeExpenseCategory(raw: String): String {
        val allowed = setOf("Fuel", "Repairs", "Accessories", "Riding Gear", "Food", "Stay", "Transport", "Others")
        return allowed.firstOrNull { it.equals(raw.trim(), ignoreCase = true) } ?: "Others"
    }

    private val currencySymbolsRegex = Regex("[₹€£\$]|\\b(INR|USD|EUR|GBP|AUD|CAD|SGD|AED)\\b", RegexOption.IGNORE_CASE)
}
