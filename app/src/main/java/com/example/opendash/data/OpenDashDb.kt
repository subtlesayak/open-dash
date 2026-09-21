package com.example.opendash.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * On-device SQLite — the source of truth for the Garage (maintenance + fuel), saved
 * destinations, and the odometer. Plain SQLiteOpenHelper (no Room/KSP).
 *
 * Every syncable row carries a stable [sid] (a UUID = its Firestore doc id) so the same
 * record maps 1:1 across devices. [SyncRepository] mirrors these rows to Firestore and
 * applies remote changes back via the upsert / delete-by-sid methods. All calls are
 * synchronous; callers run them off the main thread.
 */
class OpenDashDb internal constructor(context: Context, name: String = "opendash.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 7) {

    companion object {
        @Volatile private var instance: OpenDashDb? = null
        fun get(context: Context): OpenDashDb =
            instance ?: synchronized(this) {
                instance ?: OpenDashDb(context).also { instance = it }
            }
        const val DEFAULT_ODOMETER = 325   // seeded from the bike's current ODO; user-editable
        fun newSid(): String = UUID.randomUUID().toString()

        private const val CREATE_RIDE =
            """CREATE TABLE IF NOT EXISTS ride(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 start_ms INTEGER NOT NULL,
                 end_ms INTEGER NOT NULL,
                 distance_m REAL NOT NULL,
                 duration_s INTEGER NOT NULL,
                 avg_speed REAL NOT NULL,
                 max_speed REAL NOT NULL,
                 track TEXT NOT NULL DEFAULT '',
                 start_lat REAL NOT NULL DEFAULT 0,
                 start_lng REAL NOT NULL DEFAULT 0,
                 end_lat REAL NOT NULL DEFAULT 0,
                 end_lng REAL NOT NULL DEFAULT 0)"""

        private const val CREATE_EXPENSE =
            """CREATE TABLE IF NOT EXISTS expense(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 date_ms INTEGER NOT NULL,
                 category TEXT NOT NULL,
                 amount REAL NOT NULL,
                 note TEXT NOT NULL DEFAULT '',
                 vehicle_id TEXT NOT NULL DEFAULT 'default')"""
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE fuel_fillup(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 date_ms INTEGER NOT NULL,
                 litres REAL NOT NULL,
                 cost REAL NOT NULL,
                 odometer_km INTEGER NOT NULL,
                 location TEXT NOT NULL DEFAULT '',
                 vehicle_id TEXT NOT NULL DEFAULT 'default')"""
        )
        db.execSQL(
            """CREATE TABLE maintenance_item(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 name TEXT NOT NULL,
                 icon_key TEXT NOT NULL,
                 interval_km INTEGER NOT NULL,
                 last_done_odo_km INTEGER NOT NULL,
                 last_done_date_ms INTEGER NOT NULL,
                 vehicle_id TEXT NOT NULL DEFAULT 'default')"""
        )
        db.execSQL("CREATE TABLE bike_state(id INTEGER PRIMARY KEY, odometer_km INTEGER NOT NULL)")
        db.execSQL("INSERT INTO bike_state(id, odometer_km) VALUES (0, $DEFAULT_ODOMETER)")
        db.execSQL("CREATE TABLE vehicle_state(vehicle_id TEXT PRIMARY KEY, odometer_km INTEGER NOT NULL)")
        db.execSQL("INSERT INTO vehicle_state(vehicle_id, odometer_km) VALUES ('default', $DEFAULT_ODOMETER)")
        db.execSQL(
            """CREATE TABLE saved_location(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 name TEXT NOT NULL,
                 lat REAL NOT NULL,
                 lng REAL NOT NULL,
                 note TEXT NOT NULL DEFAULT '',
                 created_ms INTEGER NOT NULL)"""
        )
        db.execSQL(CREATE_RIDE)
        db.execSQL(CREATE_EXPENSE)
        seedMaintenance(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS saved_location(
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     name TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL,
                     note TEXT NOT NULL DEFAULT '', created_ms INTEGER NOT NULL)"""
            )
        }
        if (oldVersion < 3) {
            // Add sid columns + backfill existing rows with random UUID-like ids.
            for (t in listOf("fuel_fillup", "maintenance_item", "saved_location")) {
                runCatching { db.execSQL("ALTER TABLE $t ADD COLUMN sid TEXT NOT NULL DEFAULT ''") }
                db.execSQL(
                    "UPDATE $t SET sid = lower(hex(randomblob(16))) WHERE sid IS NULL OR sid = ''"
                )
            }
        }
        if (oldVersion < 4) db.execSQL(CREATE_RIDE)
        if (oldVersion < 5) db.execSQL(CREATE_EXPENSE)
        if (oldVersion < 6) {
            for (table in listOf("fuel_fillup", "maintenance_item", "expense")) {
                runCatching {
                    db.execSQL("ALTER TABLE $table ADD COLUMN vehicle_id TEXT NOT NULL DEFAULT 'default'")
                }
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS vehicle_state(vehicle_id TEXT PRIMARY KEY, odometer_km INTEGER NOT NULL)")
            db.execSQL(
                "INSERT OR IGNORE INTO vehicle_state(vehicle_id, odometer_km) " +
                    "SELECT 'default', odometer_km FROM bike_state WHERE id=0"
            )
            db.execSQL("UPDATE maintenance_item SET name='Chain sprocket' WHERE sid='seed-chain'")
            db.execSQL("UPDATE maintenance_item SET name='Brake pads - front' WHERE sid='seed-brakes'")
            seedMaintenanceForVehicle(db, VehicleStore.DEFAULT_VEHICLE_ID)
        }
        if (oldVersion < 7) {
            // Correct only untouched legacy defaults. User-edited intervals remain unchanged.
            db.execSQL("UPDATE maintenance_item SET interval_km=10000 WHERE vehicle_id='default' AND sid='seed-oil' AND interval_km=5000")
            db.execSQL("UPDATE maintenance_item SET interval_km=10000 WHERE vehicle_id='default' AND sid='seed-oilfilter' AND interval_km=5000")
            db.execSQL("UPDATE maintenance_item SET interval_km=10000 WHERE vehicle_id='default' AND sid='seed-airfilter' AND interval_km=8000")
            db.execSQL("UPDATE maintenance_item SET interval_km=10000 WHERE vehicle_id='default' AND sid IN ('seed-brakes','seed-brakes-rear') AND interval_km=6000")
            db.execSQL("UPDATE maintenance_item SET interval_km=10000 WHERE vehicle_id='default' AND sid IN ('seed-front-tyre','seed-rear-tyre') AND interval_km=15000")
            db.execSQL("UPDATE maintenance_item SET name='Drive chain', interval_km=500 WHERE vehicle_id='default' AND sid='seed-chain' AND interval_km=15000")
        }
    }

    private fun seedMaintenance(db: SQLiteDatabase) {
        seedMaintenanceForVehicle(db, VehicleStore.DEFAULT_VEHICLE_ID)
    }

    private fun seedMaintenanceForVehicle(db: SQLiteDatabase, vehicleId: String) {
        // The bundled schedule is sourced specifically for the default Himalayan 450.
        // Other vehicles remain empty until the rider adds model-appropriate intervals.
        if (vehicleId != VehicleStore.DEFAULT_VEHICLE_ID) return
        val now = System.currentTimeMillis()
        // DETERMINISTIC sids: every fresh install seeds the same ids, so when two devices
        // sync these dedupe (upsert by sid) instead of producing duplicates.
        data class Seed(val sid: String, val name: String, val icon: String, val interval: Int)
        val seeds = listOf(
            Seed("seed-oil", "Engine oil", "drop", 10000),
            Seed("seed-oilfilter", "Oil filter", "fuel", 10000),
            Seed("seed-brakes", "Brake pads - front", "gauge", 10000),
            Seed("seed-brakes-rear", "Brake pads - rear", "gauge", 10000),
            Seed("seed-front-tyre", "Front tyre", "gauge", 10000),
            Seed("seed-rear-tyre", "Rear tyre", "gauge", 10000),
            Seed("seed-airfilter", "Air filter", "wrench", 10000),
            Seed("seed-chain", "Drive chain", "chain", 500),
        )
        for (s in seeds) {
            val sid = if (vehicleId == VehicleStore.DEFAULT_VEHICLE_ID) s.sid else "$vehicleId-${s.sid}"
            db.insertWithOnConflict("maintenance_item", null, ContentValues().apply {
                put("sid", sid)
                put("name", s.name)
                put("icon_key", s.icon)
                put("interval_km", s.interval)
                put("last_done_odo_km", 0)
                put("last_done_date_ms", now)
                put("vehicle_id", vehicleId)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun ensureMaintenanceForVehicle(vehicleId: String) {
        seedMaintenanceForVehicle(writableDatabase, vehicleId)
    }

    // ── Odometer (synced as a single doc) ──────────────────────────────────
    fun odometer(vehicleId: String = VehicleStore.DEFAULT_VEHICLE_ID): Int {
        val stored = readableDatabase.rawQuery(
            "SELECT odometer_km FROM vehicle_state WHERE vehicle_id=?",
            arrayOf(vehicleId),
        ).use { if (it.moveToFirst()) it.getInt(0) else null }
        if (stored != null) return stored
        val fuelOdo = readableDatabase.rawQuery(
            "SELECT MAX(odometer_km) FROM fuel_fillup WHERE vehicle_id=?",
            arrayOf(vehicleId),
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) else 0 }
        setOdometer(fuelOdo, vehicleId)
        return fuelOdo
    }

    fun setOdometer(km: Int, vehicleId: String = VehicleStore.DEFAULT_VEHICLE_ID) {
        writableDatabase.insertWithOnConflict(
            "vehicle_state",
            null,
            ContentValues().apply { put("vehicle_id", vehicleId); put("odometer_km", km) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ── Fuel ──────────────────────────────────────────────────────────────
    fun fuelFills(vehicleId: String = VehicleStore.DEFAULT_VEHICLE_ID): List<FuelFillup> {
        val out = ArrayList<FuelFillup>()
        readableDatabase.rawQuery(
            "SELECT id,sid,date_ms,litres,cost,odometer_km,location,vehicle_id FROM fuel_fillup " +
                "WHERE vehicle_id=? ORDER BY odometer_km DESC, date_ms DESC", arrayOf(vehicleId),
        ).use { c ->
            while (c.moveToNext()) out.add(
                FuelFillup(
                    id = c.getLong(0), sid = c.getString(1), dateMs = c.getLong(2), litres = c.getDouble(3),
                    cost = c.getDouble(4), odometerKm = c.getInt(5), location = c.getString(6) ?: "",
                    vehicleId = c.getString(7) ?: VehicleStore.DEFAULT_VEHICLE_ID,
                )
            )
        }
        return out
    }

    /** Insert or update by sid. */
    fun upsertFuel(f: FuelFillup) {
        val cv = ContentValues().apply {
            put("sid", f.sid); put("date_ms", f.dateMs); put("litres", f.litres)
            put("cost", f.cost); put("odometer_km", f.odometerKm); put("location", f.location)
            put("vehicle_id", f.vehicleId)
        }
        if (writableDatabase.update("fuel_fillup", cv, "sid=?", arrayOf(f.sid)) == 0)
            writableDatabase.insert("fuel_fillup", null, cv)
    }

    fun deleteFuelBySid(sid: String) =
        writableDatabase.delete("fuel_fillup", "sid=?", arrayOf(sid))

    // ── Expenses ─────────────────────────────────────────────────────────
    fun expenses(vehicleId: String = VehicleStore.DEFAULT_VEHICLE_ID): List<Expense> {
        val out = ArrayList<Expense>()
        readableDatabase.rawQuery(
            "SELECT id,sid,date_ms,category,amount,note,vehicle_id FROM expense " +
                "WHERE vehicle_id=? ORDER BY date_ms DESC", arrayOf(vehicleId),
        ).use { c ->
            while (c.moveToNext()) out.add(
                Expense(
                    id = c.getLong(0), sid = c.getString(1), dateMs = c.getLong(2),
                    category = c.getString(3), amount = c.getDouble(4), note = c.getString(5) ?: "",
                    vehicleId = c.getString(6) ?: VehicleStore.DEFAULT_VEHICLE_ID,
                )
            )
        }
        return out
    }

    fun upsertExpense(e: Expense) {
        val cv = ContentValues().apply {
            put("sid", e.sid); put("date_ms", e.dateMs); put("category", e.category)
            put("amount", e.amount); put("note", e.note)
            put("vehicle_id", e.vehicleId)
        }
        if (writableDatabase.update("expense", cv, "sid=?", arrayOf(e.sid)) == 0)
            writableDatabase.insert("expense", null, cv)
    }

    fun deleteExpenseBySid(sid: String) =
        writableDatabase.delete("expense", "sid=?", arrayOf(sid))

    // ── Maintenance ───────────────────────────────────────────────────────
    fun maintenanceItems(vehicleId: String = VehicleStore.DEFAULT_VEHICLE_ID): List<MaintenanceItem> {
        val out = ArrayList<MaintenanceItem>()
        readableDatabase.rawQuery(
            "SELECT id,sid,name,icon_key,interval_km,last_done_odo_km,last_done_date_ms,vehicle_id " +
                "FROM maintenance_item WHERE vehicle_id=? ORDER BY id ASC", arrayOf(vehicleId),
        ).use { c ->
            while (c.moveToNext()) out.add(
                MaintenanceItem(
                    id = c.getLong(0), sid = c.getString(1), name = c.getString(2), iconKey = c.getString(3),
                    intervalKm = c.getInt(4), lastDoneOdoKm = c.getInt(5), lastDoneDateMs = c.getLong(6),
                    vehicleId = c.getString(7) ?: VehicleStore.DEFAULT_VEHICLE_ID,
                )
            )
        }
        return out
            .groupBy { it.name.trim().lowercase() }
            .values
            .map { duplicates ->
                duplicates.maxWithOrNull(
                    compareBy<MaintenanceItem> { it.lastDoneOdoKm }
                        .thenBy { it.lastDoneDateMs },
                ) ?: duplicates.first()
            }
    }

    fun upsertMaintenance(m: MaintenanceItem) {
        val item = normalizeOfficialDefault(m)
        val cv = ContentValues().apply {
            put("sid", item.sid); put("name", item.name); put("icon_key", item.iconKey)
            put("interval_km", item.intervalKm); put("last_done_odo_km", item.lastDoneOdoKm)
            put("last_done_date_ms", item.lastDoneDateMs)
            put("vehicle_id", item.vehicleId)
        }
        if (writableDatabase.update("maintenance_item", cv, "sid=?", arrayOf(item.sid)) == 0)
            writableDatabase.insert("maintenance_item", null, cv)
    }

    private fun normalizeOfficialDefault(item: MaintenanceItem): MaintenanceItem {
        if (item.vehicleId != VehicleStore.DEFAULT_VEHICLE_ID) return item
        return when {
            item.sid == "seed-oil" && item.intervalKm == 5000 -> item.copy(intervalKm = 10000)
            item.sid == "seed-oilfilter" && item.intervalKm == 5000 -> item.copy(intervalKm = 10000)
            item.sid == "seed-airfilter" && item.intervalKm == 8000 -> item.copy(intervalKm = 10000)
            item.sid in setOf("seed-brakes", "seed-brakes-rear") && item.intervalKm == 6000 -> item.copy(intervalKm = 10000)
            item.sid in setOf("seed-front-tyre", "seed-rear-tyre") && item.intervalKm == 15000 -> item.copy(intervalKm = 10000)
            item.sid == "seed-chain" && item.intervalKm == 15000 -> item.copy(name = "Drive chain", intervalKm = 500)
            else -> item
        }
    }

    fun deleteMaintenanceBySid(sid: String) =
        writableDatabase.delete("maintenance_item", "sid=?", arrayOf(sid))

    // ── Saved destinations ─────────────────────────────────────────────────
    fun savedLocations(): List<SavedLocation> {
        val out = ArrayList<SavedLocation>()
        readableDatabase.rawQuery(
            "SELECT id,sid,name,lat,lng,note,created_ms FROM saved_location ORDER BY created_ms DESC", null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                SavedLocation(
                    id = c.getLong(0), sid = c.getString(1), name = c.getString(2), lat = c.getDouble(3),
                    lng = c.getDouble(4), note = c.getString(5) ?: "", createdMs = c.getLong(6),
                )
            )
        }
        return out
    }

    fun upsertSaved(s: SavedLocation) {
        val cv = ContentValues().apply {
            put("sid", s.sid); put("name", s.name); put("lat", s.lat); put("lng", s.lng)
            put("note", s.note); put("created_ms", s.createdMs)
        }
        if (writableDatabase.update("saved_location", cv, "sid=?", arrayOf(s.sid)) == 0)
            writableDatabase.insert("saved_location", null, cv)
    }

    fun deleteSavedBySid(sid: String) =
        writableDatabase.delete("saved_location", "sid=?", arrayOf(sid))

    // ── Rides ──────────────────────────────────────────────────────────────
    fun rides(): List<Ride> {
        val out = ArrayList<Ride>()
        readableDatabase.rawQuery(
            "SELECT id,sid,start_ms,end_ms,distance_m,duration_s,avg_speed,max_speed," +
                "track,start_lat,start_lng,end_lat,end_lng FROM ride ORDER BY start_ms DESC", null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                Ride(
                    id = c.getLong(0), sid = c.getString(1), startMs = c.getLong(2), endMs = c.getLong(3),
                    distanceMeters = c.getDouble(4), durationSec = c.getLong(5), avgSpeedMps = c.getDouble(6),
                    maxSpeedMps = c.getDouble(7), trackPolyline = c.getString(8) ?: "",
                    startLat = c.getDouble(9), startLng = c.getDouble(10), endLat = c.getDouble(11), endLng = c.getDouble(12),
                )
            )
        }
        return out
    }

    fun upsertRide(r: Ride) {
        val cv = ContentValues().apply {
            put("sid", r.sid); put("start_ms", r.startMs); put("end_ms", r.endMs)
            put("distance_m", r.distanceMeters); put("duration_s", r.durationSec)
            put("avg_speed", r.avgSpeedMps); put("max_speed", r.maxSpeedMps); put("track", r.trackPolyline)
            put("start_lat", r.startLat); put("start_lng", r.startLng); put("end_lat", r.endLat); put("end_lng", r.endLng)
        }
        if (writableDatabase.update("ride", cv, "sid=?", arrayOf(r.sid)) == 0)
            writableDatabase.insert("ride", null, cv)
    }

    fun deleteRideBySid(sid: String) =
        writableDatabase.delete("ride", "sid=?", arrayOf(sid))
}
