package com.minimal.carlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcoScoreTest {

    private fun trip(km: Double, ha: Int, hb: Int, idle: Long = 60, drive: Long = 1800, max: Float = 90f) =
        TripRecord(0, (drive + idle) * 1000, km, drive, idle, max, km * 0.3, ha, hb)

    @Test fun smoothTripScoresHigh() {
        val r = EcoScore.of(trip(30.0, 0, 0))!!
        assertEquals(100, r.score)
        assertEquals("Smooth drive", r.tip)
    }

    @Test fun hardBrakesCostMostAndPickTheTip() {
        val r = EcoScore.of(trip(10.0, 1, 3))!!
        assertEquals(100 - 24 - 6, r.score)
        assertEquals("Brake earlier and more gently", r.tip)
    }

    @Test fun idlingAndSpeedPenalties() {
        val idle = EcoScore.of(trip(20.0, 0, 0, idle = 900, drive = 2100))!!   // 30 % idle
        assertEquals(88, idle.score)
        val fast = EcoScore.of(trip(40.0, 0, 0, max = 140f))!!
        assertEquals(95, fast.score)
    }

    @Test fun noScoreForShortParkedOrOldTrips() {
        assertNull(EcoScore.of(trip(1.0, 0, 0)))
        assertNull(EcoScore.of(TripRecord(0, 1000, 25.0, 1500, 60, 80f, 5.0)))     // no counts recorded
        assertNull(EcoScore.of(TripRecord(0, 600_000, 0.0, 0, 600, 0f, 0.0, 0, 0)))  // parked
    }

    @Test fun summariesAndChange() {
        val t1 = trip(10.0, 0, 0).copy(start = 1000)
        val t2 = trip(20.0, 0, 0).copy(start = 2000)
        val s = Summaries.of(listOf(t1, t2), 0, 5000) { 1f }
        assertEquals(2, s.trips)
        assertEquals(30.0, s.km, 0.01)
        assertEquals(2.0, s.litres, 0.01)
        assertEquals("+50%", Summaries.change(30.0, 20.0))
        assertEquals("", Summaries.change(30.0, 0.0))
        assertTrue(TripLog.decode(TripLog.encode(listOf(t1))).first().hardBrake == 0)
    }
}
