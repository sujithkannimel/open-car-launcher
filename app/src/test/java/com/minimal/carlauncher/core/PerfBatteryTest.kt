package com.minimal.carlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfBatteryTest {

    /** Feeds one fix per second from [speeds]; returns every finished run. */
    private fun drive(speeds: List<Float>, timer: PerfTimer = PerfTimer()): List<PerfRun> =
        speeds.flatMapIndexed { i, v -> timer.feed(1_000_000L + i * 1000L, v) }

    @Test fun zeroToSixtyAndHundredInterpolated() {
        // 10 km/h per second after pulling away: 2 km/h at t=0.2 s, 60 at 6.0 s, 100 at 10.0 s.
        val runs = drive(listOf(0f, 0f) + (1..12).map { it * 10f })
        val m = runs.associateBy { it.kind }
        assertEquals(5.8f, m["0-60"]!!.sec, 0.05f)
        assertEquals(9.8f, m["0-100"]!!.sec, 0.05f)
        assertEquals(4.0f, m["40-80"]!!.sec, 0.05f)
    }

    @Test fun liftingOffOrGapsCancel() {
        // Lifts at 50 then accelerates again: no 0-60 for that launch.
        assertTrue(drive(listOf(0f, 10f, 30f, 50f, 40f, 60f, 70f)).none { it.kind == "0-60" })
        // A missing fix (3 s gap) drops the run.
        val t = PerfTimer()
        t.feed(0, 0f); t.feed(1000, 20f)
        assertTrue(t.feed(4000, 70f).isEmpty())
    }

    @Test fun gentleDrivingIsNotARun() {
        // 1.5 km/h per second: 40-80 would take 27 s, 0-100 over a minute - both over the limit.
        val runs = drive(listOf(0f) + (1..70).map { it * 1.5f })
        assertTrue(runs.none { it.kind == "40-80" })
        assertTrue(runs.none { it.kind == "0-100" })
    }

    @Test fun bestAndCodec() {
        val runs = listOf(PerfRun("0-60", 7.2f, 1), PerfRun("0-60", 6.9f, 2), PerfRun("40-80", 6.1f, 3))
        assertEquals(6.9f, PerfTimer.best(runs, "0-60")!!.sec, 0.001f)
        assertNull(PerfTimer.best(runs, "0-100"))
        assertEquals(runs, PerfTimer.decode(PerfTimer.encode(runs)))
    }

    @Test fun batteryCrankChargeAndVerdict() {
        assertEquals(10.1f, BatteryHealth.crankDip(listOf(12.3f, 12.2f, 10.1f, 11.5f))!!, 0.001f)
        assertNull(BatteryHealth.crankDip(listOf(12.3f, 12.1f)))          // no real dip caught
        assertNull(BatteryHealth.crankDip(listOf(12.3f)))
        assertEquals(100, BatteryHealth.chargePercent(12.7f))
        assertEquals(50, BatteryHealth.chargePercent(12.15f))
        assertEquals(0, BatteryHealth.chargePercent(11.5f))
        assertEquals(BatteryHealth.Crank.POOR, BatteryHealth.crankBand(9.2f))
        assertEquals(12.2f to 10.1f, BatteryHealth.splitStart(listOf(12.3f, 12.2f, 10.1f, 11.5f)))
        assertEquals(12.1f to null, BatteryHealth.splitStart(listOf(12.3f, 12.1f)))
        assertEquals(null to 7.6f, BatteryHealth.splitStart(listOf(7.6f)))                 // only the crank was caught
        assertEquals(null to 9.5f, BatteryHealth.splitStart(listOf(9.5f, 12.1f)))
        assertEquals(11.8f to 7.4f, BatteryHealth.splitStart(listOf(11.8f, 7.4f, 11.9f)))
        assertEquals(-1f, BatteryHealth.decode("[{\"d\":\"2026-11-02\",\"r\":7.6}]").first().resting)

        var days = BatteryHealth.addResting(emptyList(), "2026-11-01", 12.5f)
        assertEquals(BatteryHealth.Level.GOOD, BatteryHealth.verdict(days).level)
        days = BatteryHealth.addCrank(days, "2026-11-01", 9.4f)
        val v = BatteryHealth.verdict(days)
        assertEquals(BatteryHealth.Level.REPLACE, v.level)
        assertTrue(v.reasons.single().startsWith("Starting dip"))
        assertEquals(BatteryHealth.Level.UNKNOWN, BatteryHealth.verdict(emptyList()).level)
        assertEquals(days, BatteryHealth.decode(BatteryHealth.encode(days)))
    }

    @Test fun smartChargingDoesNotAlarm() {
        val w = ChargeWatch()
        var t = 0L
        var raised = 0
        // 30 min of smart charging: 12.6 V cruising, a 14.5 V charge every 4 minutes.
        repeat(60) { i ->
            t += 30_000L
            val s = w.feed(t, if (i % 8 == 0) 14.5f else 12.6f)
            if (s.newlyRaised) raised++
        }
        assertEquals(0, raised)
        assertEquals(0, w.alert)
    }

    @Test fun deadAlternatorAlarmsOnceAndDrainIsCounted() {
        val w = ChargeWatch()
        var t = 0L; var raised = 0; var drain = 0
        repeat(40) {                                    // 20 min, never charging, 12.2 V
            t += 30_000L
            val s = w.feed(t, 12.2f)
            if (s.newlyRaised) raised++
            drain += s.drainSec
        }
        assertEquals(1, raised)
        assertEquals(1, w.alert)
        assertEquals(39 * 30, drain)
        assertEquals(0, w.feed(t + 30_000L, 14.2f).alert)   // charging again clears it
        assertEquals(false, w.feed(t + 60_000L, 12.0f).newlyRaised)
    }

    @Test fun overchargeAndVerdictUsesMaxNotAverage() {
        val w = ChargeWatch()
        w.feed(0L, 15.3f)
        val s = w.feed(30_000L, 15.4f)
        assertEquals(2, s.alert); assertEquals(true, s.newlyRaised)
        var days = BatteryHealth.addResting(emptyList(), "2026-11-09", 12.6f)
        repeat(12) { i -> days = BatteryHealth.addCharging(days, "2026-11-09", if (i % 3 == 0) 14.4f else 12.7f) }
        assertEquals(BatteryHealth.Level.GOOD, BatteryHealth.verdict(days).level)   // avg 13.3 is fine with smart charging
        days = BatteryHealth.addDrain(days, "2026-11-09", 600)
        assertEquals(BatteryHealth.Level.WATCH, BatteryHealth.verdict(days).level)
        assertEquals(600, BatteryHealth.decode(BatteryHealth.encode(days)).first().drainSec)
    }
}
