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
      {"type":"node","id":4,"lat":8.7000,"lon":76.9800,"tags":{"man_made":"surveillance","name":"KL-KEL-KMVD-50-SVDS-01-3"}}
    ]}"""

    @Test fun parsesKindsDropsParkingAndMergesDuplicates() {
        val c = Cameras.fromOverpass(overpass)
        assertEquals(2, c.size)                                       // 1 and 2 are the same spot; 3 is parking
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
        assertNull(Cameras.ahead(list, 8.4950, 76.9000, 0f, 50f))        // 890 m: too far at 50 km/h
        assertNotNull(Cameras.ahead(listOf(Camera("osm:9", 8.5050, 76.9000, "ai")), 8.5000, 76.9000, 0f, 90f)) // 556 m at 90
        assertNull(Cameras.ahead(list, 8.5000, 76.9000, 0f, 50f, muted = listOf(Camera("osm:other", 8.50301, 76.90001, "ai"))))
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
