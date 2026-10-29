package com.minimal.carlauncher

import com.minimal.carlauncher.core.FuelEstimator
import com.minimal.carlauncher.core.FuelEvent
import com.minimal.carlauncher.core.FuelState
import com.minimal.carlauncher.core.TripLog
import com.minimal.carlauncher.core.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelReplayTest {

    private fun setup(gps: Double, odo: Int = -1) =
        FuelEvent(FuelEvent.KIND_SETUP, 1L, gps, odo = odo, tankL = 37f, kmpl = 18f, fraction = 1f)

    private fun full(gps: Double, litres: Float = -1f, odo: Int = -1) =
        FuelEvent(FuelEvent.KIND_FILL, 2L, gps, odo = odo, litres = litres, full = true)

    @Test
    fun replayLearnsFromOdometerAndBuildsRows() {
        val r = FuelEstimator.replay(listOf(
            setup(0.0),
            full(0.0, odo = 59_800),
            full(450.0, litres = 25f, odo = 60_300),         // 500 km on 25 L = 20 km/l
        ))
        assertEquals(1, r.tanks.size)
        assertEquals(20f, r.tanks[0].kmpl, 0.01f)
        assertEquals(18f * 0.6f + 20f * 0.4f, r.state.kmpl, 0.01f)
        assertEquals(3, r.rows.size)
        assertEquals(500.0, r.rows[2].kmSincePrev, 0.01)
        assertEquals(20f, r.rows[2].measuredKmpl, 0.01f)
        assertEquals(60_300 + 50, r.estimatedOdo(500.0))
    }

    @Test
    fun odometerCorrectionRebasesRange() {
        // GPS saw only 100 km since the full fill, the odometer says 160.
        val events = listOf(setup(0.0), full(0.0, odo = 59_800),
            FuelEvent(FuelEvent.KIND_ODO, 3L, 100.0, odo = 59_960))
        val before = FuelEstimator.replay(events.dropLast(1)).state
        val after = FuelEstimator.replay(events).state
        // Float maths: allow 1 km either way.
        assertTrue(kotlin.math.abs(FuelEstimator.rangeKm(before, 100.0) - (37 * 18 - 100)) <= 1)
        assertTrue(kotlin.math.abs(FuelEstimator.rangeKm(after, 100.0) - (37 * 18 - 160)) <= 1)
    }

    @Test
    fun deletingAnEntryIsExact() {
        val a = listOf(setup(0.0), full(0.0, odo = 59_800))
        val b = a + FuelEvent(FuelEvent.KIND_FILL, 4L, 50.0, litres = 10f)
        assertEquals(FuelEstimator.replay(a).state.lastFullOdo, FuelEstimator.replay(b.dropLast(1)).state.lastFullOdo)
        assertEquals(
            FuelEstimator.rangeKm(FuelEstimator.replay(a).state, 50.0),
            FuelEstimator.rangeKm(FuelEstimator.replay(b.dropLast(1)).state, 50.0)
        )
    }

    @Test
    fun jsonRoundTrip() {
        val ev = listOf(setup(1.5, 59_000), full(10.25, 12.5f, 59_100))
        assertEquals(ev, FuelEstimator.decode(FuelEstimator.encode(ev)))
        assertTrue(FuelEstimator.decode("garbage").isEmpty())
    }

    @Test
    fun migrationKeepsRange() {
        val old = FuelEstimator.fill(FuelEstimator.setup(37f, 18f, 0.5f, 0.0), true, null, 59_858, 20.0)
        val replayed = FuelEstimator.replay(FuelEstimator.migrate(old)).state
        assertEquals(FuelEstimator.rangeKm(old, 120.0), FuelEstimator.rangeKm(replayed, 120.0))
        assertEquals(59_858, replayed.lastFullOdo)
        assertTrue(FuelEstimator.migrate(FuelState()).isEmpty())
    }

    @Test
    fun tripLogTotalsAndFiltering() {
        val t1 = TripRecord(1_000L, 601_000L, 12.0, 500L, 100L, 80f)
        val tiny = TripRecord(2_000L, 3_000L, 0.05, 10L, 0L, 5f)
        assertFalse(TripLog.worthKeeping(tiny))
        val list = TripLog.append(TripLog.append(emptyList(), t1), tiny)
        assertEquals(1, list.size)
        assertEquals(12.0, TripLog.totals(list, 0L).km, 0.001)
        assertEquals(0, TripLog.totals(list, 5_000L).trips)
        assertEquals(list, TripLog.decode(TripLog.encode(list)))
        assertEquals((12.0 / (500 / 3600.0)).toFloat(), t1.avgKmh, 0.1f)
    }
}
