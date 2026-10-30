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
    const val KEY_FUEL_EVENTS = "pref_fuel_events"
    const val KEY_LAST_AUTO_BACKUP = "pref_last_auto_backup_day"
    const val KEY_CLOUD_REPO = "pref_cloud_repo"
    const val KEY_CLOUD_TOKEN = "pref_cloud_token"
    const val KEY_CLOUD_PASS = "pref_cloud_pass"
    const val KEY_CLOUD_LAST_DAY = "pref_cloud_last_day"
    const val KEY_CLOUD_STATUS = "pref_cloud_status"
    const val KEY_CLOUD_LATEST_KEY = "pref_cloud_latest_key"
    const val KEY_CLOUD_LATEST_AT = "pref_cloud_latest_at"
    const val KEY_TRIP_HISTORY = "pref_trip_history"
    const val KEY_CUR_TRIP_GPS = "pref_cur_trip_gps"
    const val KEY_TRIP_LAST_GPS = "pref_trip_last_gps"
    const val KEY_CUR_TRIP_DRIVE = "pref_cur_trip_drive"
    const val KEY_CUR_TRIP_IDLE = "pref_cur_trip_idle"
    const val KEY_CUR_TRIP_MAX = "pref_cur_trip_max"
    const val KEY_CUR_TRIP_HWY = "pref_cur_trip_hwy"
    const val KEY_BATTERY_LOG = "pref_battery_log"
    const val KEY_WEATHER_JSON = "pref_weather_json"
    const val KEY_STAY_ON_CONNECT = "pref_stay_on_connect"
    const val KEY_USER_OPENED_ZLINK = "pref_user_opened_zlink_at"
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

    /** Old single-line fuel state (before the fill-up log); read once for migration. */
    private fun legacyFuelState(): FuelState? {
        val p = sp.getString(KEY_FUEL, null)?.split("|") ?: return null
        return try {
            FuelState(
                tankL = p[0].toFloat(), kmpl = p[1].toFloat(), anchorLitres = p[2].toFloat(),
                anchorGpsKm = p[3].toDouble(), lastFullOdo = p[4].toInt(),
                lastFullGpsKm = p[5].toDouble(), partialLitresSinceFull = p[6].toFloat(),
            )
        } catch (_: Exception) { null }
    }

    /** Everything the driver entered about fuel, oldest first. */
    var fuelEvents: List<FuelEvent>
        get() {
            val raw = sp.getString(KEY_FUEL_EVENTS, null)
            if (raw != null) return FuelEstimator.decode(raw)
            val migrated = legacyFuelState()?.let { FuelEstimator.migrate(it) } ?: emptyList()
            if (migrated.isNotEmpty()) {
                sp.edit().putString(KEY_FUEL_EVENTS, FuelEstimator.encode(migrated)).remove(KEY_FUEL).apply()
            }
            return migrated
        }
        set(value) = sp.edit().putString(KEY_FUEL_EVENTS, FuelEstimator.encode(value)).apply()

    fun addFuelEvent(e: FuelEvent) { fuelEvents = fuelEvents + e }

    fun fuelReplay(): FuelReplay = FuelEstimator.replay(fuelEvents)

    /** Current fuel state, rebuilt from the fill-up log. */
    val fuelState: FuelState get() = fuelReplay().state

    fun resetFuel() { sp.edit().remove(KEY_FUEL).remove(KEY_FUEL_EVENTS).apply() }

    // ------------------------------------------------ backup

    /** "yyyy-MM-dd" of the last automatic backup. */
    var lastAutoBackupDay: String
        get() = sp.getString(KEY_LAST_AUTO_BACKUP, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_AUTO_BACKUP, value).apply()

    fun backupJson(): String = Backup.toJson(sp.all, System.currentTimeMillis())

    // ------------------------------------------------ cloud backup (GitHub, private repo)
    // Never part of a backup file: see Backup.EXCLUDED.

    /** "owner/name" of the private backup repo, or "". */
    var cloudRepo: String
        get() = sp.getString(KEY_CLOUD_REPO, "") ?: ""
        set(value) = sp.edit().putString(KEY_CLOUD_REPO, value.trim()).apply()

    var cloudToken: String
        get() = sp.getString(KEY_CLOUD_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_CLOUD_TOKEN, value.trim()).apply()

    /** Optional passphrase; empty = upload unencrypted (repo is private). */
    var cloudPass: String
        get() = sp.getString(KEY_CLOUD_PASS, "") ?: ""
        set(value) = sp.edit().putString(KEY_CLOUD_PASS, value).apply()

    var cloudLastDay: String
        get() = sp.getString(KEY_CLOUD_LAST_DAY, "") ?: ""
        set(value) = sp.edit().putString(KEY_CLOUD_LAST_DAY, value).apply()

    /** Human-readable result of the last cloud upload. */
    var cloudStatus: String
        get() = sp.getString(KEY_CLOUD_STATUS, "") ?: ""
        set(value) = sp.edit().putString(KEY_CLOUD_STATUS, value).apply()

    /** What latest.json last contained (trips + fuel log), to skip unchanged uploads. */
    var cloudLatestKey: String
        get() = sp.getString(KEY_CLOUD_LATEST_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_CLOUD_LATEST_KEY, value).apply()

    var cloudLatestAt: Long
        get() = sp.getLong(KEY_CLOUD_LATEST_AT, 0L)
        set(value) = sp.edit().putLong(KEY_CLOUD_LATEST_AT, value).apply()

    /** Changes whenever a trip is saved or the fuel log is edited. */
    fun latestKey(): String =
        (sp.getString(KEY_TRIP_HISTORY, "") ?: "").hashCode().toString() + "/" +
            (sp.getString(KEY_FUEL_EVENTS, "") ?: "").hashCode().toString()

    val cloudConfigured: Boolean get() = cloudRepo.contains('/') && cloudToken.isNotEmpty()

    /**
     * Replaces settings, trip history and fuel log with the backup's values. Values that
     * are not in the backup (the trip in progress, locations) are left alone.
     */
    fun restore(b: Backup.Parsed) {
        val currentKm = gpsOdoKm
        val e = sp.edit()
        for ((k, v) in b.values) {
            when (v) {
                is String -> e.putString(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
        }
        e.commit()
        (b.values[KEY_GPS_ODO] as? Long)?.let { bits ->
            gpsOdoKm = Backup.mergedGpsKm(java.lang.Double.longBitsToDouble(bits), currentKm)
        }
        // Shift the trip in progress by the same amount so it keeps its distance so far.
        val delta = gpsOdoKm - currentKm
        if (delta != 0.0) {
            curTripGpsStart = curTripGpsStart + delta
            tripLastGps = tripLastGps + delta
        }
    }

    // ------------------------------------------------ trips

    var tripHistory: List<TripRecord>
        get() = TripLog.decode(sp.getString(KEY_TRIP_HISTORY, null))
        set(value) = sp.edit().putString(KEY_TRIP_HISTORY, TripLog.encode(value)).apply()

    /** GPS odometer when the current trip began. */
    var curTripGpsStart: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(KEY_CUR_TRIP_GPS, 0L))
        set(value) = sp.edit().putLong(KEY_CUR_TRIP_GPS, java.lang.Double.doubleToRawLongBits(value)).apply()

    /** GPS odometer at the last heartbeat (the end of the trip if the car is switched off). */
    var tripLastGps: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(KEY_TRIP_LAST_GPS, 0L))
        set(value) = sp.edit().putLong(KEY_TRIP_LAST_GPS, java.lang.Double.doubleToRawLongBits(value)).apply()

    var curTripDriveSec: Long
        get() = sp.getLong(KEY_CUR_TRIP_DRIVE, 0L)
        set(value) = sp.edit().putLong(KEY_CUR_TRIP_DRIVE, value).apply()

    var curTripIdleSec: Long
        get() = sp.getLong(KEY_CUR_TRIP_IDLE, 0L)
        set(value) = sp.edit().putLong(KEY_CUR_TRIP_IDLE, value).apply()

    /** km of the trip in progress driven at highway speed. */
    var curTripHwyKm: Float
        get() = sp.getFloat(KEY_CUR_TRIP_HWY, 0f)
        set(value) = sp.edit().putFloat(KEY_CUR_TRIP_HWY, value).apply()

    /** Daily resting / charging voltage summary. */
    var batteryLog: List<BatteryDay>
        get() = BatteryHealth.decode(sp.getString(KEY_BATTERY_LOG, null))
        set(value) = sp.edit().putString(KEY_BATTERY_LOG, BatteryHealth.encode(value)).apply()

    /** Trip in progress as a record (for totals, fuel and the live display), or null. */
    fun liveTrip(now: Long): TripRecord? {
        val start = tripStart
        if (start <= 0L) return null
        val km = (gpsOdoKm - curTripGpsStart).coerceAtLeast(0.0)
        return TripRecord(start, now, km, curTripDriveSec, curTripIdleSec, curTripMaxKmh,
            curTripHwyKm.toDouble().coerceAtMost(km))
    }

    /** City / highway fuel model over all recorded trips including the live one. */
    fun fuelModel(now: Long = System.currentTimeMillis()): FuelModelResult {
        val trips = tripHistory + listOfNotNull(liveTrip(now))
        return FuelModel.compute(fuelReplay(), trips, gpsOdoKm, now)
    }

    var curTripMaxKmh: Float
        get() = sp.getFloat(KEY_CUR_TRIP_MAX, 0f)
        set(value) = sp.edit().putFloat(KEY_CUR_TRIP_MAX, value).apply()

    /** Keep the current screen when ZLink jumps to the front on phone connect. */
    var stayOnConnect: Boolean
        get() = sp.getBoolean(KEY_STAY_ON_CONNECT, true)
        set(value) = sp.edit().putBoolean(KEY_STAY_ON_CONNECT, value).apply()

    /** Wall-clock ms when the driver opened the projection app from the Phone Link tile. */
    var userOpenedZlinkAt: Long
        get() = sp.getLong(KEY_USER_OPENED_ZLINK, 0L)
        set(value) = sp.edit().putLong(KEY_USER_OPENED_ZLINK, value).apply()

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
