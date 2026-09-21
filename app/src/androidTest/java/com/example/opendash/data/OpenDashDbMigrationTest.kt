package com.example.opendash.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDashDbMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val createdNames = mutableListOf<String>()

    @After
    fun tearDown() {
        createdNames.forEach { context.deleteDatabase(it) }
    }

    @Test
    fun upgradesLegacySchemasToCurrentVersionWithoutDroppingSeededRows() {
        for (version in 1..6) {
            val name = "opendash-migration-v$version.db"
            createdNames += name
            context.deleteDatabase(name)
            createLegacyDatabase(name, version)

            val db = OpenDashDb(context, name)
            val writable = db.writableDatabase

            assertEquals("version $version", 7, writable.version)
            assertTrue("fuel sid column v$version", hasColumn(writable, "fuel_fillup", "sid"))
            assertTrue("fuel vehicle column v$version", hasColumn(writable, "fuel_fillup", "vehicle_id"))
            assertTrue("maintenance vehicle column v$version", hasColumn(writable, "maintenance_item", "vehicle_id"))
            assertTrue("vehicle state table v$version", hasTable(writable, "vehicle_state"))
            assertEquals("fuel row retained v$version", 1, countRows(writable, "fuel_fillup"))
            assertTrue(
                "official maintenance seed retained v$version",
                countRows(writable, "maintenance_item") >= 1,
            )
            db.close()
        }
    }

    private fun createLegacyDatabase(name: String, version: Int) {
        val db = context.openOrCreateDatabase(name, 0, null)
        db.execSQL(
            """CREATE TABLE fuel_fillup(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 date_ms INTEGER NOT NULL,
                 litres REAL NOT NULL,
                 cost REAL NOT NULL,
                 odometer_km INTEGER NOT NULL,
                 location TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE maintenance_item(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 name TEXT NOT NULL,
                 icon_key TEXT NOT NULL,
                 interval_km INTEGER NOT NULL,
                 last_done_odo_km INTEGER NOT NULL,
                 last_done_date_ms INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE TABLE bike_state(id INTEGER PRIMARY KEY, odometer_km INTEGER NOT NULL)")
        db.insert("bike_state", null, ContentValues().apply {
            put("id", 0)
            put("odometer_km", 123)
        })
        db.insert("fuel_fillup", null, ContentValues().apply {
            put("date_ms", 1_000L)
            put("litres", 10.0)
            put("cost", 800.0)
            put("odometer_km", 123)
            put("location", "test")
        })
        db.insert("maintenance_item", null, ContentValues().apply {
            put("name", "Chain sprocket")
            put("icon_key", "chain")
            put("interval_km", 15_000)
            put("last_done_odo_km", 0)
            put("last_done_date_ms", 1_000L)
        })
        if (version >= 2) {
            db.execSQL(
                """CREATE TABLE saved_location(
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     name TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL,
                     note TEXT NOT NULL DEFAULT '', created_ms INTEGER NOT NULL)"""
            )
        }
        if (version >= 3) {
            db.execSQL("ALTER TABLE fuel_fillup ADD COLUMN sid TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE maintenance_item ADD COLUMN sid TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE fuel_fillup SET sid='legacy-fuel'")
            db.execSQL("UPDATE maintenance_item SET sid='seed-chain'")
            if (version >= 2) {
                db.execSQL("ALTER TABLE saved_location ADD COLUMN sid TEXT NOT NULL DEFAULT ''")
            }
        }
        if (version >= 4) {
            db.execSQL(
                """CREATE TABLE ride(
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
            )
        }
        if (version >= 5) {
            db.execSQL(
                """CREATE TABLE expense(
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     sid TEXT NOT NULL DEFAULT '',
                     date_ms INTEGER NOT NULL,
                     category TEXT NOT NULL,
                     amount REAL NOT NULL,
                     note TEXT NOT NULL DEFAULT '')"""
            )
        }
        if (version >= 6) {
            db.execSQL("ALTER TABLE fuel_fillup ADD COLUMN vehicle_id TEXT NOT NULL DEFAULT 'default'")
            db.execSQL("ALTER TABLE maintenance_item ADD COLUMN vehicle_id TEXT NOT NULL DEFAULT 'default'")
            db.execSQL("ALTER TABLE expense ADD COLUMN vehicle_id TEXT NOT NULL DEFAULT 'default'")
            db.execSQL("CREATE TABLE vehicle_state(vehicle_id TEXT PRIMARY KEY, odometer_km INTEGER NOT NULL)")
        }
        db.version = version
        db.close()
    }

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == column) return true
            }
            false
        }

    private fun countRows(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
}
