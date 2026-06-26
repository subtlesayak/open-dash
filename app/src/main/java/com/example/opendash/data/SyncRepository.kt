package com.example.opendash.data

import android.content.Context
import com.example.opendash.util.DebugLog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single store the Garage + saved-location ViewModels talk to. Local SQLite is the
 * source of truth; every mutation is mirrored to Firestore under users/{uid}/… and
 * live snapshot listeners apply remote changes back into SQLite — so data syncs across
 * the rider's devices (and works offline via Firestore's local cache).
 *
 * Sync model (single user, last-write-wins): each row has a stable [sid] = its Firestore
 * doc id, so the same record maps 1:1 everywhere. On [startSync] each collection is
 * uploaded once IF the cloud copy is empty (lets an existing device seed the cloud),
 * then kept live. Conflicts are rare for one rider and resolve last-write-wins.
 */
class SyncRepository private constructor(context: Context) {
    companion object {
        private const val TAG = "SyncRepository"
        @Volatile private var instance: SyncRepository? = null
        fun get(context: Context): SyncRepository =
            instance ?: synchronized(this) { instance ?: SyncRepository(context.applicationContext).also { instance = it } }
    }

    private val db = OpenDashDb.get(context)
    // Firebase is optional (bring-your-own-project). When no google-services.json was
    // bundled, these stay null and every mirror/listen call is a no-op — the app runs
    // fully local. See [FirebaseGate].
    private val firebaseOn = FirebaseGate.isConfigured(context)
    private val fs: FirebaseFirestore? = if (firebaseOn) FirebaseFirestore.getInstance() else null
    private val auth: FirebaseAuth? = if (firebaseOn) FirebaseAuth.getInstance() else null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bumped on every local OR remote data change so ViewModels reload. */
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private fun bump() { _revision.value++ }

    private val regs = mutableListOf<ListenerRegistration>()

    private fun userDoc(): DocumentReference? =
        auth?.currentUser?.uid?.let { fs?.collection("users")?.document(it) }

    // ── Reads (local) ────────────────────────────────────────────────────
    fun odometer(vehicleId: String = VehicleStore.activeVehicleId.value) = db.odometer(vehicleId)
    fun fuelFills(vehicleId: String = VehicleStore.activeVehicleId.value) = db.fuelFills(vehicleId)
    fun expenses(vehicleId: String = VehicleStore.activeVehicleId.value) = db.expenses(vehicleId)
    fun maintenanceItems(vehicleId: String = VehicleStore.activeVehicleId.value) = db.maintenanceItems(vehicleId)
    fun ensureMaintenance(vehicleId: String = VehicleStore.activeVehicleId.value) {
        db.ensureMaintenanceForVehicle(vehicleId); bump()
    }
    fun savedLocations() = db.savedLocations()
    fun rides() = db.rides()

    // ── Mutations (local write-through + Firestore mirror) ────────────────
    fun setOdometer(km: Int, vehicleId: String = VehicleStore.activeVehicleId.value) {
        db.setOdometer(km, vehicleId); pushOdometer(km, vehicleId); bump()
    }

    fun addFuel(
        litres: Double,
        cost: Double,
        odoKm: Int,
        location: String,
        vehicleId: String = VehicleStore.activeVehicleId.value,
    ) {
        val prevOdo = db.odometer(vehicleId)
        val f = FuelFillup(sid = OpenDashDb.newSid(), dateMs = System.currentTimeMillis(),
            litres = litres, cost = cost, odometerKm = odoKm, location = location, vehicleId = vehicleId)
        db.upsertFuel(f); pushFuel(f)
        if (odoKm > prevOdo) { db.setOdometer(odoKm, vehicleId); pushOdometer(odoKm, vehicleId) }
        bump()
    }
    fun deleteFuel(f: FuelFillup) { db.deleteFuelBySid(f.sid); userDoc()?.collection("fuel")?.document(f.sid)?.delete(); bump() }

    fun addExpense(
        category: String,
        amount: Double,
        note: String,
        dateMs: Long = System.currentTimeMillis(),
        vehicleId: String = VehicleStore.activeVehicleId.value,
    ) {
        val e = Expense(
            sid = OpenDashDb.newSid(),
            dateMs = dateMs,
            category = category,
            amount = amount,
            note = note,
            vehicleId = vehicleId,
        )
        db.upsertExpense(e); pushExpense(e); bump()
    }
    fun upsertExpense(e: Expense) {
        db.upsertExpense(e)
        pushExpense(e)
        bump()
    }
    fun deleteExpense(e: Expense) { db.deleteExpenseBySid(e.sid); userDoc()?.collection("expenses")?.document(e.sid)?.delete(); bump() }

    fun addMaintenance(
        name: String,
        icon: String,
        intervalKm: Int,
        lastDoneOdoKm: Int,
        vehicleId: String = VehicleStore.activeVehicleId.value,
    ) {
        val m = MaintenanceItem(sid = OpenDashDb.newSid(), name = name, iconKey = icon,
            intervalKm = intervalKm, lastDoneOdoKm = lastDoneOdoKm,
            lastDoneDateMs = System.currentTimeMillis(), vehicleId = vehicleId)
        db.upsertMaintenance(m); pushMaintenance(m); bump()
    }
    fun markServiceDone(m: MaintenanceItem, odoKm: Int) {
        logService(m, odoKm, m.intervalKm)
    }
    fun logService(m: MaintenanceItem, odoKm: Int, intervalKm: Int) {
        val u = m.copy(
            intervalKm = intervalKm,
            lastDoneOdoKm = odoKm,
            lastDoneDateMs = System.currentTimeMillis(),
        )
        db.upsertMaintenance(u); pushMaintenance(u); bump()
    }
    fun deleteMaintenance(m: MaintenanceItem) { db.deleteMaintenanceBySid(m.sid); userDoc()?.collection("maintenance")?.document(m.sid)?.delete(); bump() }

    fun addSaved(name: String, lat: Double, lng: Double, note: String) {
        val s = SavedLocation(sid = OpenDashDb.newSid(), name = name, lat = lat, lng = lng, note = note)
        db.upsertSaved(s); pushSaved(s); bump()
    }
    fun renameSaved(s: SavedLocation, name: String, note: String) {
        val u = s.copy(name = name, note = note); db.upsertSaved(u); pushSaved(u); bump()
    }
    fun deleteSaved(s: SavedLocation) { db.deleteSavedBySid(s.sid); userDoc()?.collection("saved")?.document(s.sid)?.delete(); bump() }

    /** Persist a finished ride (local + cloud). */
    fun addRide(r: Ride) { db.upsertRide(r); pushRide(r); bump() }
    fun deleteRide(r: Ride) { db.deleteRideBySid(r.sid); userDoc()?.collection("rides")?.document(r.sid)?.delete(); bump() }

    // ── Firestore push helpers ───────────────────────────────────────────
    private fun pushOdometer(km: Int, vehicleId: String) {
        userDoc()?.collection("state")?.document("bike-$vehicleId")
            ?.set(mapOf("odometerKm" to km, "vehicleId" to vehicleId))
    }
    private fun pushFuel(f: FuelFillup) {
        userDoc()?.collection("fuel")?.document(f.sid)?.set(
            mapOf("dateMs" to f.dateMs, "litres" to f.litres, "cost" to f.cost, "odometerKm" to f.odometerKm,
                "location" to f.location, "vehicleId" to f.vehicleId))
    }
    private fun pushMaintenance(m: MaintenanceItem) {
        userDoc()?.collection("maintenance")?.document(m.sid)?.set(
            mapOf("name" to m.name, "iconKey" to m.iconKey, "intervalKm" to m.intervalKm,
                "lastDoneOdoKm" to m.lastDoneOdoKm, "lastDoneDateMs" to m.lastDoneDateMs,
                "vehicleId" to m.vehicleId))
    }
    private fun pushExpense(e: Expense) {
        userDoc()?.collection("expenses")?.document(e.sid)?.set(
            mapOf("dateMs" to e.dateMs, "category" to e.category, "amount" to e.amount,
                "note" to e.note, "vehicleId" to e.vehicleId))
    }
    private fun pushSaved(s: SavedLocation) {
        userDoc()?.collection("saved")?.document(s.sid)?.set(
            mapOf("name" to s.name, "lat" to s.lat, "lng" to s.lng, "note" to s.note, "createdMs" to s.createdMs))
    }
    private fun pushRide(r: Ride) {
        userDoc()?.collection("rides")?.document(r.sid)?.set(
            mapOf("startMs" to r.startMs, "endMs" to r.endMs, "distanceMeters" to r.distanceMeters,
                "durationSec" to r.durationSec, "avgSpeedMps" to r.avgSpeedMps, "maxSpeedMps" to r.maxSpeedMps,
                "track" to r.trackPolyline, "startLat" to r.startLat, "startLng" to r.startLng,
                "endLat" to r.endLat, "endLng" to r.endLng))
    }

    // ── Sync lifecycle ───────────────────────────────────────────────────
    fun startSync() {
        val u = userDoc() ?: return
        stopSync()
        DebugLog.i(TAG) { "startSync for uid=${auth?.currentUser?.uid}" }

        listen(u.collection("fuel"),
            uploadLocal = { VehicleStore.vehicles.value.flatMap { db.fuelFills(it.id) }.forEach { pushFuel(it) } },
            apply = { doc -> db.upsertFuel(FuelFillup(
                sid = doc.id, dateMs = doc.getLong("dateMs") ?: 0L, litres = doc.getDouble("litres") ?: 0.0,
                cost = doc.getDouble("cost") ?: 0.0, odometerKm = (doc.getLong("odometerKm") ?: 0L).toInt(),
                location = doc.getString("location") ?: "",
                vehicleId = doc.getString("vehicleId") ?: VehicleStore.DEFAULT_VEHICLE_ID)) },
            remove = { db.deleteFuelBySid(it) })

        listen(u.collection("maintenance"),
            uploadLocal = { VehicleStore.vehicles.value.flatMap { db.maintenanceItems(it.id) }.forEach { pushMaintenance(it) } },
            apply = { doc -> db.upsertMaintenance(MaintenanceItem(
                sid = doc.id, name = doc.getString("name") ?: "", iconKey = doc.getString("iconKey") ?: "wrench",
                intervalKm = (doc.getLong("intervalKm") ?: 0L).toInt(), lastDoneOdoKm = (doc.getLong("lastDoneOdoKm") ?: 0L).toInt(),
                lastDoneDateMs = doc.getLong("lastDoneDateMs") ?: 0L,
                vehicleId = doc.getString("vehicleId") ?: VehicleStore.DEFAULT_VEHICLE_ID)) },
            remove = { db.deleteMaintenanceBySid(it) })

        listen(u.collection("expenses"),
            uploadLocal = { VehicleStore.vehicles.value.flatMap { db.expenses(it.id) }.forEach { pushExpense(it) } },
            apply = { doc -> db.upsertExpense(Expense(
                sid = doc.id, dateMs = doc.getLong("dateMs") ?: 0L,
                category = doc.getString("category") ?: "Others",
                amount = doc.getDouble("amount") ?: 0.0,
                note = doc.getString("note") ?: "",
                vehicleId = doc.getString("vehicleId") ?: VehicleStore.DEFAULT_VEHICLE_ID)) },
            remove = { db.deleteExpenseBySid(it) })

        listen(u.collection("saved"),
            uploadLocal = { db.savedLocations().forEach { pushSaved(it) } },
            apply = { doc -> db.upsertSaved(SavedLocation(
                sid = doc.id, name = doc.getString("name") ?: "", lat = doc.getDouble("lat") ?: 0.0,
                lng = doc.getDouble("lng") ?: 0.0, note = doc.getString("note") ?: "",
                createdMs = doc.getLong("createdMs") ?: System.currentTimeMillis())) },
            remove = { db.deleteSavedBySid(it) })

        listen(u.collection("rides"),
            uploadLocal = { db.rides().forEach { pushRide(it) } },
            apply = { doc -> db.upsertRide(Ride(
                sid = doc.id, startMs = doc.getLong("startMs") ?: 0L, endMs = doc.getLong("endMs") ?: 0L,
                distanceMeters = doc.getDouble("distanceMeters") ?: 0.0, durationSec = doc.getLong("durationSec") ?: 0L,
                avgSpeedMps = doc.getDouble("avgSpeedMps") ?: 0.0, maxSpeedMps = doc.getDouble("maxSpeedMps") ?: 0.0,
                trackPolyline = doc.getString("track") ?: "",
                startLat = doc.getDouble("startLat") ?: 0.0, startLng = doc.getDouble("startLng") ?: 0.0,
                endLat = doc.getDouble("endLat") ?: 0.0, endLng = doc.getDouble("endLng") ?: 0.0)) },
            remove = { db.deleteRideBySid(it) })

        // Odometer: single doc. Pull if present, else seed the cloud from local.
        val activeVehicleId = VehicleStore.activeVehicleId.value
        regs += u.collection("state").document("bike-$activeVehicleId").addSnapshotListener { snap, _ ->
            io.launch {
                val km = snap?.getLong("odometerKm")?.toInt()
                if (km != null) {
                    db.setOdometer(km, activeVehicleId); bump()
                } else {
                    pushOdometer(db.odometer(activeVehicleId), activeVehicleId)
                }
            }
        }
    }

    fun stopSync() { regs.forEach { it.remove() }; regs.clear() }

    private fun listen(
        col: CollectionReference,
        uploadLocal: () -> Unit,
        apply: (DocumentSnapshot) -> Unit,
        remove: (String) -> Unit,
    ) {
        // One-time: if the cloud copy is empty, push our local rows up to seed it.
        col.get().addOnSuccessListener { qs -> if (qs.isEmpty) io.launch { uploadLocal() } }
        regs += col.addSnapshotListener { snap, err ->
            if (err != null || snap == null) return@addSnapshotListener
            io.launch {
                for (ch in snap.documentChanges) {
                    when (ch.type) {
                        DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> apply(ch.document)
                        DocumentChange.Type.REMOVED -> remove(ch.document.id)
                    }
                }
                if (snap.documentChanges.isNotEmpty()) bump()
            }
        }
    }
}
