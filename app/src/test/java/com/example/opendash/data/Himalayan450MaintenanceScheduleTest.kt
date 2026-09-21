package com.example.opendash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Himalayan450MaintenanceScheduleTest {
    private fun item(name: String, vehicleId: String = VehicleStore.DEFAULT_VEHICLE_ID) =
        MaintenanceItem(
            name = name,
            iconKey = "wrench",
            intervalKm = 1,
            lastDoneOdoKm = 0,
            lastDoneDateMs = 0,
            vehicleId = vehicleId,
        )

    @Test
    fun replacementItemsUseOfficialRecurringSchedule() {
        for (name in listOf("Engine oil", "Oil filter", "Air filter")) {
            val schedule = Himalayan450MaintenanceSchedule.forItem(item(name))!!
            assertEquals(MaintenanceAction.REPLACE, schedule.action)
            assertEquals(10_000, schedule.intervalKm)
            assertEquals(12, schedule.intervalMonths)
        }
    }

    @Test
    fun wearItemsAreInspectionsNotFixedReplacementClaims() {
        for (name in listOf("Brake pads - front", "Brake pads - rear", "Front tyre", "Rear tyre")) {
            val schedule = Himalayan450MaintenanceSchedule.forItem(item(name))!!
            assertEquals(MaintenanceAction.INSPECT, schedule.action)
            assertEquals(10_000, schedule.intervalKm)
            assertEquals(12, schedule.intervalMonths)
        }
    }

    @Test
    fun driveChainUsesFiveHundredKilometreMaintenanceSchedule() {
        val schedule = Himalayan450MaintenanceSchedule.forItem(item("Drive chain"))!!
        assertEquals(MaintenanceAction.MAINTAIN, schedule.action)
        assertEquals(500, schedule.intervalKm)
        assertNull(schedule.intervalMonths)
    }

    @Test
    fun manualIsNotClaimedForOtherVehicles() {
        assertNull(Himalayan450MaintenanceSchedule.forItem(item("Engine oil", "another-bike")))
    }
}
