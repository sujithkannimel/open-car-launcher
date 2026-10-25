package com.minimal.carlauncher

import com.minimal.carlauncher.core.FuelEstimator
import com.minimal.carlauncher.core.FuelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelEstimatorTest {

    @Test
    fun unconfiguredHasNoRange() {
        val s = FuelState()
        assertFalse(s.configured)
        assertEquals(-1, FuelEstimator.rangeKm(s, 100.0))
        assertEquals(0, FuelEstimator.level(s, 100.0))
    }

    @Test
    fun setupAndCountDown() {
        val s = FuelEstimator.setup(tankL = 40f, kmpl = 20f, fraction = 0.5f, gpsKm = 1000.0)
        assertEquals(400, FuelEstimator.rangeKm(s, 1000.0))   // 20 L * 20 km/l
        assertEquals(300, FuelEstimator.rangeKm(s, 1100.0))   // drove 100 km
        assertEquals(0, FuelEstimator.rangeKm(s, 2000.0))     // never negative
    }

    @Test
    fun partialFillAddsLitresCappedAtTank() {
        var s = FuelEstimator.setup(40f, 20f, 0.25f, 0.0)      // 10 L
        s = FuelEstimator.fill(s, full = false, litres = 15f, odo = null, gpsKm = 0.0)
        assertEquals(25f, s.anchorLitres, 0.01f)
        s = FuelEstimator.fill(s, full = false, litres = 100f, odo = null, gpsKm = 0.0)
        assertEquals(40f, s.anchorLitres, 0.01f)
    }

    @Test
    fun learnsKmplBetweenFullFillsUsingOdometer() {
        var s = FuelEstimator.setup(40f, 15f, 1f, 0.0)
        s = FuelEstimator.fill(s, full = true, litres = null, odo = 10_000, gpsKm = 0.0)
        // 500 km on the odometer (GPS saw less, odometer wins), refilled 25 L -> 20 km/l
        s = FuelEstimator.fill(s, full = true, litres = 25f, odo = 10_500, gpsKm = 450.0)
        val expected = 15f * 0.6f + 20f * 0.4f
        assertEquals(expected, s.kmpl, 0.01f)
        assertEquals(40f, s.anchorLitres, 0.01f)
        assertTrue(FuelEstimator.rangeKm(s, 450.0) > 600)
    }

    @Test
    fun ignoresShortStretchesAndAbsurdValues() {
        var s = FuelEstimator.setup(40f, 18f, 1f, 0.0)
        s = FuelEstimator.fill(s, true, null, null, 0.0)
        s = FuelEstimator.fill(s, true, 5f, null, 20.0)          // only 20 km: no learning
        assertEquals(18f, s.kmpl, 0.01f)
        s = FuelEstimator.fill(s, true, 1f, null, 520.0)         // 500 km/l: rejected
        assertEquals(18f, s.kmpl, 0.01f)
    }

    @Test
    fun levels() {
        val s = FuelEstimator.setup(40f, 10f, 1f, 0.0)          // 400 km range
        assertEquals(2, FuelEstimator.level(s, 0.0))
        assertEquals(3, FuelEstimator.level(s, 330.0))          // 70 km left
        assertEquals(4, FuelEstimator.level(s, 370.0))          // 30 km left
    }
}
