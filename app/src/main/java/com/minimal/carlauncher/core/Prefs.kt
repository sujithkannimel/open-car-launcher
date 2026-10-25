package com.minimal.carlauncher.core

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * Single SharedPreferences file behind a typed facade.
 *
 * Deliberately not DataStore: the launcher needs a synchronous read of theme and dock
 * contents inside onCreate, before the first frame. SharedPreferences is loaded once and
 * kept in memory, which is exactly the right shape here.
 */
object Prefs {

    private const val FILE = "car_launcher"

    const val KEY_SPEED_UNIT = "pref_speed_unit"
    const val KEY_THEME_MODE = "pref_theme_mode"
    const val KEY_COMPASS_SOURCE = "pref_compass_source"
    const val KEY_COMPASS_16 = "pref_compass_16point"
    const val KEY_NAV_PACKAGE = "pref_nav_package"
    const val KEY_MUSIC_PACKAGE = "pref_music_package"
    const val KEY_PROJECTION_PACKAGE = "pref_projection_package"
    const val KEY_DOCK_SLOTS = "pref_dock_slots"
    const val KEY_DASHCAM_PACKAGE = "pref_dashcam_package"
    const val KEY_DASHCAM_AUTOSTART = "pref_dashcam_autostart"
    const val KEY_DASHCAM_RETURN_HOME = "pref_dashcam_return_home"
    const val KEY_LAST_SEEN_TAG = "pref_last_seen_release_tag"
    const val KEY_LAST_CHECK_MS = "pref_last_update_check_ms"
    const val KEY_CACHED_TAG = "pref_cached_latest_tag"
    const val KEY_CACHED_URL = "pref_cached_latest_url"
    const val KEY_PENDING_APK = "pref_pending_apk_path"
    const val KEY_FIRST_RUN_DONE = "pref_first_run_done"
    const val KEY_RADIO_PACKAGE = "pref_radio_package"
    const val KEY_RADIO_PRESETS = "pref_radio_presets"
    const val KEY_RADIO_LAST = "pref_radio_last_frequency"
    const val KEY_RADIO_LAST_NAME = "pref_radio_last_station"
    const val KEY_VEHICLE_SETTINGS_PACKAGE = "pref_vehicle_settings_package"
    const val KEY_MAP_LAT = "pref_map_lat"
    const val KEY_MAP_LON = "pref_map_lon"
    const val KEY_MAP_ZOOM = "pref_map_zoom"
    const val KEY_MAP_HEADING_UP = "pref_map_heading_up"
    const val KEY_LAST_COURSE = "pref_last_course_deg"
    const val KEY_NAV_DESTINATION = "pref_nav_destination"
    const val KEY_PANEL_ENABLED = "pref_panel_enabled"
    const val KEY_PANEL_HEIGHT = "pref_panel_height"
    const val KEY_PANEL_SIDE_MARGIN = "pref_panel_side_margin"
    const val KEY_PANEL_TRANSPARENT = "pref_panel_transparent"
    const val KEY_LAST_VOLTAGE = "pref_last_voltage"
    const val KEY_TRIP_START = "pref_trip_start"
    const val KEY_GPS_ODO = "pref_gps_odo_km"
    const val KEY_FUEL = "pref_fuel_state"
    const val KEY_WEATHER_JSON = "pref_weather_json"
    const val KEY_STAY_ON_CONNECT = "pref_stay_on_connect"
    const val KEY_WEATHER_AT = "pref_weather_at"
    const val KEY_TRIP_LAST_SEEN = "pref_trip_last_seen"
    const val KEY_KNOB_LX = "pref_knob_left_x"
    const val KEY_KNOB_LY = "pref_knob_left_y"
    const val KEY_KNOB_RX = "pref_knob_right_x"
    const val KEY_KNOB_RY = "pref_knob_right_y"
    const val KEY_KNOB_DISC = "pref_knob_disc"
    const val KEY_KNOB_ARC = "pref_knob_arc_radius"

    // Defaults for the K2401 knobs, in panel pixels (1 dp == 1 px at 160 dpi).
    // Fine-tune on the unit: long-press the battery pill -> adjust mode.
    // Measured from a straight-on photo of the K2401: the knob centres sit almost on the
    // screen's bottom corners, and the chrome ring's outer edge is ~165 px from the centre.
    const val DEF_KNOB_X = 20      // knob centre, distance from its screen edge
    const val DEF_KNOB_Y = 150     // knob centre, from the top of the 170 px strip
    const val DEF_KNOB_DISC = 220  // white disc diameter: fills the whole lens; excess hides under chrome
    const val DEF_KNOB_ARC = 184   // arc radius: just outside the chrome ring

    const val COMPASS_AUTO = "auto"
    const val COMPASS_GPS = "gps"
    const val COMPASS_SENSOR = "sensor"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var speedUnit: String
        get() = sp.getString(KEY_SPEED_UNIT, Format.UNIT_KMH) ?: Format.UNIT_KMH
        set(value) = sp.edit().putString(KEY_SPEED_UNIT, value).apply()

    /** One of the AppCompatDelegate.MODE_NIGHT_* constants. Defaults to the dark cockpit. */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_YES)
        set(value) = sp.edit().putInt(KEY_THEME_MODE, value).apply()

    var compassSource: String
        get() = sp.getString(KEY_COMPASS_SOURCE, COMPASS_AUTO) ?: COMPASS_AUTO
        set(value) = sp.edit().putString(KEY_COMPASS_SOURCE, value).apply()

    var compass16Point: Boolean
        get() = sp.getBoolean(KEY_COMPASS_16, false)
        set(value) = sp.edit().putBoolean(KEY_COMPASS_16, value).apply()

    var navPackage: String?
        get() = sp.getString(KEY_NAV_PACKAGE, null)?.ifBlank { null }
        set(value) = sp.edit().putString(KEY_NAV_PACKAGE, value).apply()

    var musicPackage: String?
        get() = sp.getString(KEY_MUSIC_PACKAGE, null)?.ifBlank { null }
        set(value) = sp.edit().putString(KEY_MUSIC_PACKAGE, value).apply()

    var projectionPackage: String?
        get() = sp.getString(KEY_PROJECTION_PACKAGE, null)?.ifBlank { null }
        set(value) = sp.edit().putString(KEY_PROJECTION_PACKAGE, value).apply()

    /** Dashcam / DVR app started once per launcher process so its overlay is up. */
    var dashcamPackage: String?
        get() = sp.getString(KEY_DASHCAM_PACKAGE, null)?.ifBlank { null }
        set(value) = sp.edit().putString(KEY_DASHCAM_PACKAGE, value).apply()

    var dashcamAutoStart: Boolean
        get() = sp.getBoolean(KEY_DASHCAM_AUTOSTART, false)
        set(value) = sp.edit().putBoolean(KEY_DASHCAM_AUTOSTART, value).apply()

    /**
     * Try to bring the dashboard back after launching the dashcam. Best-effort: Android 10+
     * restricts background activity starts, so some ROMs will ignore it and the user simply
     * presses HOME once.
     */
    var dashcamReturnHome: Boolean
        get() = sp.getBoolean(KEY_DASHCAM_RETURN_HOME, true)
        set(value) = sp.edit().putBoolean(KEY_DASHCAM_RETURN_HOME, value).apply()

    var dockSlots: List<String>
        get() = DockCodec.decode(sp.getString(KEY_DOCK_SLOTS, null))
        set(value) = sp.edit().putString(KEY_DOCK_SLOTS, DockCodec.encode(value)).apply()

    var lastSeenReleaseTag: String
        get() = sp.getString(KEY_LAST_SEEN_TAG, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_SEEN_TAG, value).apply()

    var lastUpdateCheckMs: Long
        get() = sp.getLong(KEY_LAST_CHECK_MS, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_CHECK_MS, value).apply()

    var cachedLatestTag: String
        get() = sp.getString(KEY_CACHED_TAG, "") ?: ""
        set(value) = sp.edit().putString(KEY_CACHED_TAG, value).apply()

    var cachedLatestUrl: String
        get() = sp.getString(KEY_CACHED_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_CACHED_URL, value).apply()

    var pendingApkPath: String
        get() = sp.getString(KEY_PENDING_APK, "") ?: ""
        set(value) = sp.edit().putString(KEY_PENDING_APK, value).apply()

    var firstRunDone: Boolean
        get() = sp.getBoolean(KEY_FIRST_RUN_DONE, false)
        set(value) = sp.edit().putBoolean(KEY_FIRST_RUN_DONE, value).apply()

    // ------------------------------------------------------------------ radio

    /** The head unit's native tuner app. Null means "detect by package name". */
    var radioPackage: String?
        get() = sp.getString(KEY_RADIO_PACKAGE, null)?.ifBlank { null }
        set(value) = sp.edit().putString(KEY_RADIO_PACKAGE, value).apply()

    /** Fixed-length preset slots, each a [RadioFrequency.encode] string or "" for empty. */
    var radioPresets: List<String>
        get() = DockCodec.decode(sp.getString(KEY_RADIO_PRESETS, null), Constants.RADIO_PRESET_COUNT)
        set(value) = sp.edit().putString(KEY_RADIO_PRESETS, DockCodec.encode(value)).apply()

    /**
     * Last frequency seen on the tuner, so the widget shows a station the instant the unit
     * wakes from ACC-off, before the radio app has republished its media session.
     */
    var radioLastFrequency: String
        get() = sp.getString(KEY_RADIO_LAST, "") ?: ""
        set(value) = sp.edit().putString(KEY_RADIO_LAST, value).apply()

    var radioLastStation: String
        get() = sp.getString(KEY_RADIO_LAST_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_RADIO_LAST_NAME, value).apply()

    // ---------------------------------------------------------------- vehicle

    /** The factory "car settings" app. Null falls back to Android's own Settings. */
    var vehicleSettingsPackage: String?
        get() = sp.getString(KEY_VEHICLE_SETTINGS_PACKAGE, null)?.ifBlank { null }
        set(value) = sp.edit().putString(KEY_VEHICLE_SETTINGS_PACKAGE, value).apply()

    // -------------------------------------------------------------------- map

    /** Last camera position, restored on boot so the portal never opens on an empty ocean. */
    fun mapCamera(): Triple<Double, Double, Double>? {
        if (!sp.contains(KEY_MAP_LAT)) return null
        val lat = sp.getFloat(KEY_MAP_LAT, 0f).toDouble()
        val lon = sp.getFloat(KEY_MAP_LON, 0f).toDouble()
        val zoom = sp.getFloat(KEY_MAP_ZOOM, 16f).toDouble()
        if (lat !in -85.0..85.0 || lon !in -180.0..180.0) return null
        return Triple(lat, lon, zoom)
    }

    fun saveMapCamera(lat: Double, lon: Double, zoom: Double) {
        sp.edit()
            .putFloat(KEY_MAP_LAT, lat.toFloat())
            .putFloat(KEY_MAP_LON, lon.toFloat())
            .putFloat(KEY_MAP_ZOOM, zoom.toFloat())
            .apply()
    }

    /** Last GPS course in degrees, or null. Restores the map arrow's direction after a reboot. */
    var lastCourseDeg: Float?
        get() = sp.getFloat(KEY_LAST_COURSE, -1f).takeIf { it in 0f..360f }
        set(value) = sp.edit().putFloat(KEY_LAST_COURSE, value ?: -1f).apply()

    /**
     * Active navigation destination as "lat|lon|name", so a trip survives the ignition being
     * switched off at a fuel stop. Empty when not navigating.
     */
    var navDestination: String
        get() = sp.getString(KEY_NAV_DESTINATION, "") ?: ""
        set(value) = sp.edit().putString(KEY_NAV_DESTINATION, value).apply()

    /** True = map rotates so travel direction is up; false = north-up. */
    var mapHeadingUp: Boolean
        get() = sp.getBoolean(KEY_MAP_HEADING_UP, true)
        set(value) = sp.edit().putBoolean(KEY_MAP_HEADING_UP, value).apply()

    // ------------------------------------------------ bottom panel
    var panelEnabled: Boolean
        get() = sp.getBoolean(KEY_PANEL_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_PANEL_ENABLED, value).apply()

    var panelHeight: Int
        get() = sp.getInt(KEY_PANEL_HEIGHT, 170)
        set(value) = sp.edit().putInt(KEY_PANEL_HEIGHT, value).apply()

    var panelSideMargin: Int
        get() = sp.getInt(KEY_PANEL_SIDE_MARGIN, 200)
        set(value) = sp.edit().putInt(KEY_PANEL_SIDE_MARGIN, value).apply()

    /** Whether the panel background is semi-transparent instead of solid dark. */
    var panelTransparent: Boolean
        get() = sp.getBoolean(KEY_PANEL_TRANSPARENT, false)
        set(value) = sp.edit().putBoolean(KEY_PANEL_TRANSPARENT, value).apply()

    /** Last car battery voltage from the NWD broadcast, shown until the next one arrives. */
    var lastVoltage: Float
        get() = sp.getFloat(KEY_LAST_VOLTAGE, -1f)
        set(value) = sp.edit().putFloat(KEY_LAST_VOLTAGE, value).apply()

    /** Wall-clock ms when the current trip (car start) began. */
    var tripStart: Long
        get() = sp.getLong(KEY_TRIP_START, 0L)
        set(value) = sp.edit().putLong(KEY_TRIP_START, value).apply()

    /** Last heartbeat while the unit was on; a long gap means the car was switched off. */
    var tripLastSeen: Long
        get() = sp.getLong(KEY_TRIP_LAST_SEEN, 0L)
        set(value) = sp.edit().putLong(KEY_TRIP_LAST_SEEN, value).apply()

    /** Cumulative GPS distance in km (PanelService adds to it while driving). */
    var gpsOdoKm: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(KEY_GPS_ODO, 0L))
        set(value) = sp.edit().putLong(KEY_GPS_ODO, java.lang.Double.doubleToRawLongBits(value)).apply()

    /** Fuel estimator state, stored as one "|"-separated line. */
    var fuelState: FuelState
        get() {
            val p = sp.getString(KEY_FUEL, null)?.split("|") ?: return FuelState()
            return try {
                FuelState(
                    tankL = p[0].toFloat(), kmpl = p[1].toFloat(), anchorLitres = p[2].toFloat(),
                    anchorGpsKm = p[3].toDouble(), lastFullOdo = p[4].toInt(),
                    lastFullGpsKm = p[5].toDouble(), partialLitresSinceFull = p[6].toFloat(),
                )
            } catch (_: Exception) { FuelState() }
        }
        set(v) = sp.edit().putString(
            KEY_FUEL,
            listOf(v.tankL, v.kmpl, v.anchorLitres, v.anchorGpsKm, v.lastFullOdo,
                v.lastFullGpsKm, v.partialLitresSinceFull).joinToString("|")
        ).apply()

    fun resetFuel() { sp.edit().remove(KEY_FUEL).apply() }

    /** Keep the current screen when ZLink jumps to the front on phone connect. */
    var stayOnConnect: Boolean
        get() = sp.getBoolean(KEY_STAY_ON_CONNECT, true)
        set(value) = sp.edit().putBoolean(KEY_STAY_ON_CONNECT, value).apply()

    /** Last Open-Meteo reply and when it was fetched, so weather shows at once after a restart. */
    var weatherJson: String
        get() = sp.getString(KEY_WEATHER_JSON, "") ?: ""
        set(value) = sp.edit().putString(KEY_WEATHER_JSON, value).apply()

    var weatherAt: Long
        get() = sp.getLong(KEY_WEATHER_AT, 0L)
        set(value) = sp.edit().putLong(KEY_WEATHER_AT, value).apply()

    // ------------------------------------------------ knob geometry (panel px)
    var knobLeftX: Int
        get() = sp.getInt(KEY_KNOB_LX, DEF_KNOB_X)
        set(value) = sp.edit().putInt(KEY_KNOB_LX, value).apply()

    var knobLeftY: Int
        get() = sp.getInt(KEY_KNOB_LY, DEF_KNOB_Y)
        set(value) = sp.edit().putInt(KEY_KNOB_LY, value).apply()

    /** Distance of the right knob centre from the RIGHT screen edge. */
    var knobRightX: Int
        get() = sp.getInt(KEY_KNOB_RX, DEF_KNOB_X)
        set(value) = sp.edit().putInt(KEY_KNOB_RX, value).apply()

    var knobRightY: Int
        get() = sp.getInt(KEY_KNOB_RY, DEF_KNOB_Y)
        set(value) = sp.edit().putInt(KEY_KNOB_RY, value).apply()

    var knobDisc: Int
        get() = sp.getInt(KEY_KNOB_DISC, DEF_KNOB_DISC)
        set(value) = sp.edit().putInt(KEY_KNOB_DISC, value).apply()

    var knobArcRadius: Int
        get() = sp.getInt(KEY_KNOB_ARC, DEF_KNOB_ARC)
        set(value) = sp.edit().putInt(KEY_KNOB_ARC, value).apply()

    fun resetKnobGeometry() {
        sp.edit()
            .remove(KEY_KNOB_LX).remove(KEY_KNOB_LY)
            .remove(KEY_KNOB_RX).remove(KEY_KNOB_RY)
            .remove(KEY_KNOB_DISC).remove(KEY_KNOB_ARC)
            .apply()
    }
}
