package com.example.opendash.data

import org.junit.Assert.assertNull
import org.junit.Test

class RideRecorderTest {
    @Test
    fun rejectsInaccurateFixes() {
        val recorder = RideRecorder()
        recorder.start()
        recorder.add(12.0, 77.0, 10f, 35f, 1_000L)
        recorder.add(12.01, 77.01, 10f, 35f, 2_000L)

        assertNull(recorder.stop())
    }

    @Test
    fun stationaryGpsDriftDoesNotCreateRide() {
        val recorder = RideRecorder()
        recorder.start()
        recorder.add(12.0, 77.0, 0f, 5f, 1_000L)
        recorder.add(12.002, 77.002, 0.2f, 5f, 2_000L)

        assertNull(recorder.stop())
    }
}
