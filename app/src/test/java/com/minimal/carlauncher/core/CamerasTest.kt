package com.minimal.carlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamerasTest {

    private val overpass = """{"elements":[
      {"type":"node","id":1,"lat":8.5000,"lon":76.9000,"tags":{"man_made":"surveillance","name":"KL-KEL-KMVD-50-AICS-01-1"}},
      {"type":"node","id":2,"lat":8.50001,"lon":76.90001,"tags":{"highway":"speed_camera","maxspeed":"50"}},
      {"type":"node","id":3,"lat":8.6000,"lon":76.9500,"tags":{"man_made":"surveillance","name":"KL-KEL-KMVD-50-PVDS-01-2"}},
      {"type":"node","id":4,"lat":8.7000,"lon":76.9800,"tags":{"man_made":"surveillance","name":"KL-KEL-KMVD-50-SVDS-01-3"}},
      {"type":"node","id":5,"lat":8.8000,"lon":76.9000,"tags":{"man_made":"surveillance","name":"SBI ATM"}},
      {"type":"node","id":6,"lat":8.9000,"lon":76.9000,"tags":{"man_made":"surveillance","surveillance:type":"camera"}},
      {"type":"node","id":7,"lat":9.0000,"lon":76.9000,"tags":{"man_made":"surveillance","operator":"Kerala Police"}}
    ]}"""

    @Test fun parsesKindsDropsParkingAndMergesDuplicates() {
        val c = Cameras.fromOverpass(overpass)
        assertEquals(3, c.size)                                       // 1+2 same spot; 3 parking; 5 ATM and 6 private CCTV dropped
        assertTrue(c.any { it.id == "osm:7" })                         // police-operated counts
        val merged = c.first { it.lat < 8.55 }
        assertEquals("osm:2", merged.id); assertEquals("speed", merged.kind); assertEquals(50, merged.limit)
        assertEquals("speed", c.first { it.id == "osm:4" }.kind)       // SVDS = speed violation detection
    }

    @Test fun onlyCamerasAheadWarn() {
        val cam = Camera("osm:9", 8.5030, 76.9000, "ai")                 // ~330 m due north
        val list = listOf(cam)
        val north = Cameras.ahead(list, 8.5000, 76.9000, 0f, 50f)
        assertNotNull(north); assertTrue(north!!.distanceM in 320..340)
        assertNull(Cameras.ahead(list, 8.5000, 76.9000, 180f, 50f))      // driving away
        assertNull(Cameras.ahead(list, 8.5000, 76.9000, 0f, 8f))         // crawling: heading unreliable
        assertNull(Cameras.ahead(list, 8.4950, 76.9000, 0f, 50f))        // 890 m: too far for 700 m
        assertNotNull(Cameras.ahead(list, 8.4950, 76.9000, 0f, 70f, rangeM = Cameras.alertRange(1000))) // within 1 km
        assertNotNull(Cameras.ahead(list, 8.5000, 76.9000, 50f, 50f))    // 330 m, 50 deg off: past a bend
        assertNull(Cameras.ahead(list, 8.5000, 76.8990, 90f, 50f))       // 72 deg off: beside the road, not ahead
        assertNull(Cameras.ahead(list, 8.5000, 76.9000, 0f, 50f, muted = listOf(Camera("osm:other", 8.50301, 76.90001, "ai"))))
    }

    @Test fun trackFollowsUntilPassedOrLeft() {
        val cam = Camera("osm:9", 8.5030, 76.9000, "ai")
        val t = CameraTrack(cam, 330.0)
        for (lat in listOf(8.5005, 8.5015, 8.5025, 8.5028)) assertEquals(CameraTrack.Step.APPROACHING, t.update(lat, 76.9))
        assertEquals(CameraTrack.Step.APPROACHING, t.update(8.5033, 76.9))   // 33 m: GPS noise, not yet passed
        assertEquals(CameraTrack.Step.PASSED, t.update(8.5036, 76.9))        // 66 m past the closest point
        val other = CameraTrack(cam, 330.0)                                  // camera on a parallel road
        assertEquals(CameraTrack.Step.APPROACHING, other.update(8.5010, 76.9))
        var step = CameraTrack.Step.APPROACHING
        for (lon in listOf(76.9010, 76.9020, 76.9030, 76.9040, 76.9050)) step = other.update(8.5010, lon)
        assertEquals(CameraTrack.Step.LEFT, step)
    }

    @Test fun sanityVersionDiffAndCodec() {
        val old = (1..100).map { Camera("osm:$it", 8.0 + it / 1000.0, 76.0, "ai") }
        assertFalse(Cameras.saneReplacement(old.take(40), old))             // lost more than half: keep the old list
        assertTrue(Cameras.saneReplacement(old.take(60), old))
        assertFalse(Cameras.saneReplacement(old.take(10), emptyList()))
        assertEquals(5 to 0, Cameras.diff(old.take(95), old))
        val set = CameraSet(Cameras.versionOf("2026-11-02", old), 123L, old)
        assertTrue(set.version.startsWith("2026-11-02/100/"))
        assertEquals(set, Cameras.decodeSet(Cameras.encodeSet(set)))
        val now = 1_800_000_000_000L
        val ph = CarLink.parsePhone(CarLink.phoneMessage(emptyList(), now, cams = Cameras.encodeSet(set)), now)!!
        assertEquals(set, Cameras.decodeSet(ph.cams))
        assertNull(CarLink.parsePhone(CarLink.phoneMessage(emptyList(), now), now)!!.cams)
        val m = CarLink.parseCar(CarLink.carMessage("""{"app":"car_launcher","v":1,"created":1,"prefs":{}}""", null, 12f, now, emptyList(),
            CarLink.CarExtra(camVer = set.version)), now)!!
        assertEquals(set.version, m.extra.camVer)
    }
}
