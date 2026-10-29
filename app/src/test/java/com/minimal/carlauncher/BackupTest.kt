package com.minimal.carlauncher

import com.minimal.carlauncher.core.Backup
import com.minimal.carlauncher.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    @Test
    fun roundTripKeepsTypesAndDropsLocations() {
        val prefs = mapOf<String, Any?>(
            Prefs.KEY_FUEL_EVENTS to "[{\"k\":\"setup\"}]",
            Prefs.KEY_KNOB_LX to 20,
            Prefs.KEY_GPS_ODO to 4640470418077436087L,
            Prefs.KEY_LAST_VOLTAGE to 14.2f,
            Prefs.KEY_STAY_ON_CONNECT to true,
            "pref_some_set" to setOf("b", "a"),
            Prefs.KEY_MAP_LAT to 9.05f,                 // location: must not be saved
            Prefs.KEY_WEATHER_JSON to "{}",             // location: must not be saved
            Prefs.KEY_CUR_TRIP_DRIVE to 99L,            // trip in progress: not saved
        )
        val json = Backup.toJson(prefs, 123L)
        assertFalse(json.contains(Prefs.KEY_MAP_LAT))
        assertFalse(json.contains(Prefs.KEY_WEATHER_JSON))
        val p = Backup.parse(json)!!
        assertEquals(123L, p.createdMs)
        assertEquals("[{\"k\":\"setup\"}]", p.values[Prefs.KEY_FUEL_EVENTS])
        assertEquals(20, p.values[Prefs.KEY_KNOB_LX])
        assertEquals(4640470418077436087L, p.values[Prefs.KEY_GPS_ODO])
        assertEquals(14.2f, p.values[Prefs.KEY_LAST_VOLTAGE] as Float, 0.0001f)
        assertEquals(true, p.values[Prefs.KEY_STAY_ON_CONNECT])
        assertEquals(setOf("a", "b"), p.values["pref_some_set"])
        assertFalse(p.values.containsKey(Prefs.KEY_CUR_TRIP_DRIVE))
    }

    @Test
    fun rejectsForeignFiles() {
        assertNull(Backup.parse("not json"))
        assertNull(Backup.parse("{\"app\":\"other\",\"prefs\":{}}"))
    }

    @Test
    fun gpsOdometerContinuesAfterReinstall() {
        assertEquals(178.0 + 12.0, Backup.mergedGpsKm(178.0, 12.0), 1e-9)   // reinstall
        assertEquals(200.0, Backup.mergedGpsKm(178.0, 200.0), 1e-9)          // same install
        assertTrue(Backup.mergedGpsKm(0.0, 5.0) == 5.0)
    }
}
