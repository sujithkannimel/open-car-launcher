package com.minimal.carlauncher

import com.minimal.carlauncher.core.BatteryDay
import com.minimal.carlauncher.core.BatteryHealth
import com.minimal.carlauncher.core.FuelEstimator
import com.minimal.carlauncher.core.FuelEvent
import com.minimal.carlauncher.core.FuelModel
import com.minimal.carlauncher.core.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelModelTest {

    private val day = 86_400_000L

    private fun setup() = FuelEvent(FuelEvent.KIND_SETUP, 1L, 0.0, tankL = 40f, kmpl = 18f, fraction = 1f)
    private fun full(t: Long, gps: Double, litres: Float = -1f) =
        FuelEvent(FuelEvent.KIND_FILL, t, gps, litres = litres, full = true)

    @Test
    fun withoutLearningItMatchesTheSingleAverage() {
        val r = FuelEstimator.replay(listOf(setup(), full(10L, 0.0)))
        val m = FuelModel.compute(r, emptyList(), 100.0, 20L)
        assertEquals(0, m.tanksUsed)
        assertEquals(FuelEstimator.rangeKm(r.state, 100.0).toDouble(), m.rangeKm.toDouble(), 1.0)
        assertEquals(18f, m.kmplNow, 0.01f)
    }

    @Test
    fun learnsCityAndHighwaySplit() {
        // True car: city 15 km/l, highway 22 km/l, no idling.
        val events = mutableListOf(setup(), full(1 * day, 0.0))
        val trips = ArrayList<TripRecord>()
        var gps = 0.0
        val mixes = listOf(0.1, 0.8, 0.3, 0.9, 0.2, 0.6)
        mixes.forEachIndexed { i, f ->
            val start = (2 + i * 10) * day
            val km = 400.0
            trips += TripRecord(start, start + 3_600_000L, km, 3600, 0, 90f, km * f)
            gps += km
            val litres = (km * (1 - f) / 15.0 + km * f / 22.0).toFloat()
            events += full(start + day, gps, litres)
        }
        val r = FuelEstimator.replay(events)
        val m = FuelModel.compute(r, trips, gps, 70 * day)
        assertEquals(6, m.tanksUsed)
        assertTrue("city ${m.kmplCity}", kotlin.math.abs(m.kmplCity - 15f) < 1.0f)
        assertTrue("hwy ${m.kmplHwy}", kotlin.math.abs(m.kmplHwy - 22f) < 1.5f)
        assertTrue(m.kmplHwy > m.kmplCity)
    }

    @Test
    fun tripLitresIncludesIdle() {
        val t = TripRecord(0L, 1L, 30.0, 1800, 3600, 60f, 0.0)          // all city, 1 h idle
        val l = FuelModel.tripLitres(t, 15f, 22f, -1.0)
        assertEquals(2.0f + FuelModel.IDLE_LPH, l, 0.01f)
    }

    @Test
    fun refuelForecast() {
        val now = 30 * day
        val trips = (0 until 10).map { TripRecord(now - (10 - it) * day, now - (10 - it) * day + 1, 40.0, 3600, 0, 80f, 0.0) }
        val daily = FuelModel.dailyKm(trips, now)
        assertEquals(40.0, daily, 1.0)
        val by = FuelModel.refuelBy(250, daily, now)!!
        assertEquals(5.0, (by - now) / day.toDouble(), 0.2)              // (250-50)/40
        assertNull(FuelModel.refuelBy(250, -1.0, now))
        assertEquals(-1.0, FuelModel.dailyKm(trips.takeLast(1), now), 0.0)
    }

    @Test
    fun batteryRestingAndTrend() {
        var days = emptyList<BatteryDay>()
        days = BatteryHealth.addResting(days, "2026-10-01", 12.6f)
        days = BatteryHealth.addResting(days, "2026-10-01", 12.5f)        // keeps the lowest
        assertEquals(12.5f, days.single().resting, 0.001f)
        for (i in 2..20) days = BatteryHealth.addResting(days, String.format("2026-10-%02d", i), 12.5f - i * 0.01f)
        val trend = BatteryHealth.trendPer30Days(days)
        assertNotNull(trend)
        assertEquals(-0.30, trend!!, 0.05)
        assertTrue(BatteryHealth.trendLabel(trend).startsWith("Declining"))
        days = BatteryHealth.addCharging(days, "2026-10-20", 14.1f)
        days = BatteryHealth.addCharging(days, "2026-10-20", 13.9f)
        val d = days.last()
        assertEquals(14.0f, d.chargeAvg, 0.01f)
        assertEquals(13.9f, d.chargeMin, 0.001f)
        assertEquals(days, BatteryHealth.decode(BatteryHealth.encode(days)))
        assertEquals(BatteryHealth.Rest.GOOD, BatteryHealth.restingBand(12.6f))
        assertEquals(BatteryHealth.Rest.POOR, BatteryHealth.restingBand(11.9f))
    }
}
