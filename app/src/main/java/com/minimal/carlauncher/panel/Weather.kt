package com.minimal.carlauncher.panel

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/** One hour of forecast. */
data class WeatherHour(val label: String, val code: Int, val temp: Int, val isDay: Boolean)

/** One day of forecast. */
data class WeatherDay(val label: String, val code: Int, val min: Int, val max: Int, val rainPct: Int)

data class Weather(
    val temp: Int,
    val feels: Int,
    val humidity: Int,
    val windKmh: Int,
    val code: Int,
    val isDay: Boolean,
    val hours: List<WeatherHour>,
    val days: List<WeatherDay>,
    /** Local time of the reading, "HH:mm". */
    val updated: String,
)

/** Icon families the panel draws. */
enum class WeatherKind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, THUNDER, SNOW }

/**
 * Weather from Open-Meteo (free, no account, no API key). Only a location rounded to
 * ~1 km (2 decimals) is sent; nothing identifies the car or the user.
 */
object WeatherApi {

    private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"

    fun url(lat: Double, lon: Double): String = String.format(
        Locale.US,
        "%s?latitude=%.2f&longitude=%.2f" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
            "&hourly=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=4",
        ENDPOINT, lat, lon
    )

    /** Blocking fetch; call off the main thread. Returns the raw JSON or null. */
    fun fetch(lat: Double, lon: Double): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url(lat, lon)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "MinimalCarLauncher")
            }
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    fun parse(json: String): Weather? = try {
        val root = JSONObject(json)
        val cur = root.getJSONObject("current")
        val curTime = cur.getString("time")                 // e.g. 2026-10-25T14:15
        val hourly = root.getJSONObject("hourly")
        val hTimes = hourly.getJSONArray("time")
        val hTemps = hourly.getJSONArray("temperature_2m")
        val hCodes = hourly.getJSONArray("weather_code")
        val hDay = hourly.optJSONArray("is_day")
        val hourKey = curTime.take(13) + ":00"
        var start = 0
        for (i in 0 until hTimes.length()) {
            if (hTimes.getString(i) >= hourKey) { start = i; break }
        }
        val hours = (1..6).mapNotNull { k ->
            val i = start + k
            if (i >= hTimes.length()) null else WeatherHour(
                label = hourLabel(hTimes.getString(i)),
                code = hCodes.getInt(i),
                temp = Math.round(hTemps.getDouble(i)).toInt(),
                isDay = (hDay?.optInt(i, 1) ?: 1) == 1,
            )
        }
        val daily = root.getJSONObject("daily")
        val dTimes = daily.getJSONArray("time")
        val dRain = daily.optJSONArray("precipitation_probability_max")
        val days = (0 until minOf(4, dTimes.length())).map { i ->
            WeatherDay(
                label = if (i == 0) "Today" else dayLabel(dTimes.getString(i)),
                code = daily.getJSONArray("weather_code").getInt(i),
                min = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
                max = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
                rainPct = dRain?.optInt(i, 0) ?: 0,
            )
        }
        Weather(
            temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
            feels = Math.round(cur.getDouble("apparent_temperature")).toInt(),
            humidity = cur.optInt("relative_humidity_2m", 0),
            windKmh = Math.round(cur.optDouble("wind_speed_10m", 0.0)).toInt(),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            hours = hours,
            days = days,
            updated = curTime.drop(11).take(5),
        )
    } catch (_: Exception) {
        null
    }

    /** WMO weather interpretation codes -> icon family. */
    fun kind(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.CLEAR
        1, 2 -> WeatherKind.PARTLY
        3 -> WeatherKind.CLOUDY
        45, 48 -> WeatherKind.FOG
        in 51..57 -> WeatherKind.DRIZZLE
        in 61..67, in 80..82 -> WeatherKind.RAIN
        in 71..77, 85, 86 -> WeatherKind.SNOW
        in 95..99 -> WeatherKind.THUNDER
        else -> WeatherKind.CLOUDY
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        in 71..77 -> "Snow"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "Cloudy"
    }

    private fun hourLabel(iso: String): String {
        val h = iso.drop(11).take(2).toIntOrNull() ?: return iso
        return when {
            h == 0 -> "12 AM"
            h < 12 -> "$h AM"
            h == 12 -> "12 PM"
            else -> "${h - 12} PM"
        }
    }

    private fun dayLabel(isoDate: String): String = try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate)
        SimpleDateFormat("EEE", Locale.US).format(d!!)
    } catch (_: Exception) {
        isoDate
    }
}
