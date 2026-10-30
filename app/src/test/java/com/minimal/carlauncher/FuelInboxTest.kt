package com.minimal.carlauncher

import com.minimal.carlauncher.core.FuelEstimator
import com.minimal.carlauncher.core.FuelEvent
import com.minimal.carlauncher.core.FuelInbox
import com.minimal.carlauncher.core.InboxFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelInboxTest {

    // Like the car: set-up, full fill typed at 59,854 km when the GPS counter was 75.5.
    private val base = listOf(
        FuelEvent(FuelEvent.KIND_SETUP, 0L, 75.5, tankL = 37f, kmpl = 18f, fraction = 1f),
        FuelEvent(FuelEvent.KIND_FILL, 0L, 75.5, odo = 59_854, full = true),
    )

    @Test
    fun phoneFillIsPlacedByOdometerEvenWhenItArrivesLate() {
        // Filled at 60,154 km (300 km later), but the car only syncs after another 40 km.
        val fill = InboxFill("a1", 5L, true, 16.5f, 60_154)
        val r = FuelInbox.merge(base, listOf(fill), currentGps = 75.5 + 340)
        assertEquals(1, r.added)
        val added = r.events.last()
        assertEquals(75.5 + 300, added.gpsKm, 0.001)
        val rep = FuelEstimator.replay(r.events)
        assertEquals(1, rep.tanks.size)
        assertEquals(300.0 / 16.5, rep.tanks[0].kmpl.toDouble(), 0.01)    // real km/l, same as a car entry
        // Range counts the 40 km driven since the fill.
        assertEquals((37 * rep.state.kmpl - 40).toDouble(), FuelEstimator.rangeKm(rep.state, 75.5 + 340).toDouble(), 2.0)
    }

    @Test
    fun sameFillEnteredInCarIsNotAddedTwice() {
        val inCar = base + FuelEvent(FuelEvent.KIND_FILL, 9L, 375.5, odo = 60_154, litres = 16.5f, full = true)
        val r = FuelInbox.merge(inCar, listOf(InboxFill("b2", 9L, true, 16.5f, 60_155)), 380.0)
        assertEquals(0, r.added)
        assertEquals(listOf("b2"), r.processedIds)
        assertEquals(inCar, r.events)
    }

    @Test
    fun earlierFillIsInsertedInDrivingOrder() {
        val later = base + FuelEvent(FuelEvent.KIND_ODO, 9L, 275.5, odo = 60_054)
        val r = FuelInbox.merge(later, listOf(InboxFill("c3", 5L, false, 10f, 59_954)), 300.0)
        assertEquals(1, r.added)
        assertEquals(FuelEvent.KIND_FILL, r.events[2].kind)        // before the odometer entry
        assertEquals(175.5, r.events[2].gpsKm, 0.001)
        assertTrue(r.events.zipWithNext().all { (a, b) -> a.gpsKm <= b.gpsKm })
    }

    @Test
    fun parsing() {
        val f = InboxFill("d4", 1L, true, -1f, 60_000)
        assertEquals(f, InboxFill.parse(f.toJson()))
        assertNull(InboxFill.parse("{\"app\":\"x\"}"))
        assertNull(InboxFill.parse(InboxFill("e5", 1L, false, -1f, 60_000).toJson()))   // top-up needs litres
    }
}
