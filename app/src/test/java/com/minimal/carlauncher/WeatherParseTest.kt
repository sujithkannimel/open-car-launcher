package com.minimal.carlauncher

import com.minimal.carlauncher.panel.WeatherApi
import com.minimal.carlauncher.panel.WeatherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherParseTest {

    private val sample = """
    {"current":{"time":"2026-10-25T14:15","temperature_2m":29.4,"apparent_temperature":33.1,
      "relative_humidity_2m":74,"weather_code":2,"wind_speed_10m":11.6,"is_day":1},
     "hourly":{"time":["2026-10-25T13:00","2026-10-25T14:00","2026-10-25T15:00","2026-10-25T16:00",
                       "2026-10-25T17:00","2026-10-25T18:00","2026-10-25T19:00","2026-10-25T20:00"],
               "temperature_2m":[29,29.4,29.8,29,28.2,27.1,26.4,25.9],
               "weather_code":[2,2,3,61,61,3,1,0],
               "is_day":[1,1,1,1,1,1,0,0]},
     "daily":{"time":["2026-10-25","2026-10-26","2026-10-27","2026-10-28"],
              "weather_code":[61,3,95,0],
              "temperature_2m_max":[31.2,30.4,29.9,31],
              "temperature_2m_min":[24.1,24.6,23.8,24],
              "precipitation_probability_max":[70,20,85,5]}}
    """.trimIndent()

    @Test
    fun parsesCurrentHoursAndDays() {
        val w = WeatherApi.parse(sample)
        assertNotNull(w)
        w!!
        assertEquals(29, w.temp)
        assertEquals(33, w.feels)
        assertEquals(74, w.humidity)
        assertEquals(12, w.windKmh)
        assertEquals("14:15", w.updated)
        assertTrue(w.isDay)
        // Hours start after the current hour (14:00).
        assertEquals("3 PM", w.hours.first().label)
        assertEquals(6, w.hours.size)
        assertEquals(false, w.hours[4].isDay)       // 7 PM
        assertEquals("Today", w.days[0].label)
        assertEquals(70, w.days[0].rainPct)
        assertEquals(24, w.days[0].min)
        assertEquals(31, w.days[0].max)
    }

    @Test
    fun badJsonGivesNull() {
        assertNull(WeatherApi.parse("not json"))
        assertNull(WeatherApi.parse("{}"))
    }

    @Test
    fun codeFamilies() {
        assertEquals(WeatherKind.CLEAR, WeatherApi.kind(0))
        assertEquals(WeatherKind.PARTLY, WeatherApi.kind(2))
        assertEquals(WeatherKind.RAIN, WeatherApi.kind(63))
        assertEquals(WeatherKind.RAIN, WeatherApi.kind(81))
        assertEquals(WeatherKind.THUNDER, WeatherApi.kind(95))
        assertEquals(WeatherKind.FOG, WeatherApi.kind(45))
    }

    @Test
    fun urlRoundsLocation() {
        val u = WeatherApi.url(9.931233, 76.267304)
        assertTrue(u.contains("latitude=9.93&longitude=76.27"))
    }
}
