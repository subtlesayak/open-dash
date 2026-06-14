package com.example.northstar.data

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
class NorthstarDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "northstar.db", null, 4) {

    companion object {
        @Volatile private var instance: NorthstarDb? = null
        fun get(context: Context): NorthstarDb =
            instance ?: synchronized(this) {
                instance ?: NorthstarDb(context).also { instance = it }
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
                 location TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE maintenance_item(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 sid TEXT NOT NULL DEFAULT '',
                 name TEXT NOT NULL,
                 icon_key TEXT NOT NULL,
                 interval_km INTEGER NOT NULL,
                 last_done_odo_km INTEGER NOT NULL,
                 last_done_date_ms INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE TABLE bike_state(id INTEGER PRIMARY KEY, odometer_km INTEGER NOT NULL)")
        db.execSQL("INSERT INTO bike_state(id, odometer_km) VALUES (0, $DEFAULT_ODOMETER)")
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
    }

    private fun seedMaintenance(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        // DETERMINISTIC sids: every fresh install seeds the same ids, so when two devices
        // sync these dedupe (upsert by sid) instead of producing duplicates.
        data class Seed(val sid: String, val name: String, val icon: String, val interval: Int)
        val seeds = listOf(
            Seed("seed-chain", "Chain clean & lube", "chain", 500),
            Seed("seed-oil", "Engine oil", "drop", 5000),
            Seed("seed-airfilter", "Air filter", "wrench", 8000),
            Seed("seed-brakes", "Brake pads (front)", "gauge", 6000),
            Seed("seed-coolant", "Coolant", "thermo", 12000),
        )
        for (s in seeds) {
            db.insert("maintenance_item", null, ContentValues().apply {
                put("sid", s.sid)
                put("name", s.name)
                put("icon_key", s.icon)
                put("interval_km", s.interval)
                put("last_done_odo_km", 0)
                put("last_done_date_ms", now)
            })
        }
    }

    // ── Odometer (synced as a single doc) ──────────────────────────────────
    fun odometer(): Int =
        readableDatabase.rawQuery("SELECT odometer_km FROM bike_state WHERE id=0", null).use {
            if (it.moveToFirst()) it.getInt(0) else DEFAULT_ODOMETER
        }

    fun setOdometer(km: Int) {
        writableDatabase.update("bike_state", ContentValues().apply { put("odometer_km", km) }, "id=0", null)
    }

    // ── Fuel ──────────────────────────────────────────────────────────────
    fun fuelFills(): List<FuelFillup> {
        val out = ArrayList<FuelFillup>()
        readableDatabase.rawQuery(
            "SELECT id,sid,date_ms,litres,cost,odometer_km,location FROM fuel_fillup " +
                "ORDER BY odometer_km DESC, date_ms DESC", null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                FuelFillup(
                    id = c.getLong(0), sid = c.getString(1), dateMs = c.getLong(2), litres = c.getDouble(3),
                    cost = c.getDouble(4), odometerKm = c.getInt(5), location = c.getString(6) ?: "",
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
        }
        if (writableDatabase.update("fuel_fillup", cv, "sid=?", arrayOf(f.sid)) == 0)
            writableDatabase.insert("fuel_fillup", null, cv)
    }

    fun deleteFuelBySid(sid: String) =
        writableDatabase.delete("fuel_fillup", "sid=?", arrayOf(sid))

    // ── Maintenance ───────────────────────────────────────────────────────
    fun maintenanceItems(): List<MaintenanceItem> {
        val out = ArrayList<MaintenanceItem>()
        readableDatabase.rawQuery(
            "SELECT id,sid,name,icon_key,interval_km,last_done_odo_km,last_done_date_ms " +
                "FROM maintenance_item ORDER BY id ASC", null,
        ).use { c ->
            while (c.moveToNext()) out.add(
                MaintenanceItem(
                    id = c.getLong(0), sid = c.getString(1), name = c.getString(2), iconKey = c.getString(3),
                    intervalKm = c.getInt(4), lastDoneOdoKm = c.getInt(5), lastDoneDateMs = c.getLong(6),
                )
            )
        }
        return out
    }

    fun upsertMaintenance(m: MaintenanceItem) {
        val cv = ContentValues().apply {
            put("sid", m.sid); put("name", m.name); put("icon_key", m.iconKey)
            put("interval_km", m.intervalKm); put("last_done_odo_km", m.lastDoneOdoKm)
            put("last_done_date_ms", m.lastDoneDateMs)
        }
        if (writableDatabase.update("maintenance_item", cv, "sid=?", arrayOf(m.sid)) == 0)
            writableDatabase.insert("maintenance_item", null, cv)
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
