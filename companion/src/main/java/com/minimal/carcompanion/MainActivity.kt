package com.minimal.carcompanion

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.minimal.carlauncher.core.BatteryHealth
import com.minimal.carlauncher.core.CompanionUpdate
import com.minimal.carlauncher.core.FuelEvent
import com.minimal.carlauncher.core.FuelModel
import com.minimal.carlauncher.core.Neon
import com.minimal.carlauncher.core.TripLog
import com.minimal.carlauncher.core.TripRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Car Companion: the head unit's trips, fuel and battery on the phone, read from the private
 * GitHub repo the launcher backs up to. Read-only.
 */
class MainActivity : Activity() {

    companion object {
        // Graphite + neon, the same palette as the head unit.
        private val BG = Neon.BG
        private val CARD = Neon.SURFACE
        private val CARD_HI = Neon.SURFACE_HI
        private val BAR = Neon.BAR
        private val DIVIDER = Neon.DIVIDER
        private val DIM = Neon.DIM
        private val TEXT = Neon.TEXT
        private val CYAN = Color.rgb(34, 211, 238)
        private val GREEN = Color.rgb(74, 222, 128)
        private val AMBER = Color.rgb(251, 191, 36)
        private val RED = Color.rgb(244, 63, 94)

        private const val TAB_HOME = 0
        private const val TAB_TRIPS = 1
        private const val TAB_FUEL = 2
        private const val TAB_BATTERY = 3

        private const val RELEASES =
            "https://api.github.com/repos/sujithkannimel/open-car-launcher/releases?per_page=10"
        private const val UPDATE_CHECK_MS = 60 * 60_000L
    }

    private lateinit var content: LinearLayout
    private lateinit var subtitle: TextView
    private class Tab(val pill: FrameLayout, val icon: ImageView, val text: TextView)
    private val tabs = ArrayList<Tab>()
    private lateinit var refreshIcon: ImageView
    private var spin: ObjectAnimator? = null
    private var update: CompanionUpdate.Available? = null
    private var tab = TAB_HOME
    private var data: CarData? = null
    @Volatile private var loading = false

    private val prefs by lazy { getSharedPreferences("companion", Context.MODE_PRIVATE) }
    private var repoName: String
        get() = prefs.getString("repo", "sujithkannimel/car-backups") ?: ""
        set(v) = prefs.edit().putString("repo", v.trim()).apply()
    private var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v.trim()).apply()
    private var passphrase: String
        get() = prefs.getString("pass", "") ?: ""
        set(v) = prefs.edit().putString("pass", v).apply()

    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.US)
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
    private val shortDay = SimpleDateFormat("d MMM", Locale.US)

    @Suppress("DEPRECATION")  // pre-Android 11 inset and system-UI calls
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), px(14), px(14), px(12))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "Car Companion"; setTextColor(TEXT); textSize = 24f; typeface = Typeface.DEFAULT_BOLD
        })
        titles.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, Neon.GRADIENT).apply { cornerRadius = px(2).toFloat() }
        }, LinearLayout.LayoutParams(px(56), px(3)).apply { topMargin = px(4); bottomMargin = px(4) })
        subtitle = TextView(this).apply { setTextColor(DIM); textSize = 13f }
        titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val refreshBtn = iconButton(R.drawable.ic_refresh, "Refresh") { refresh() }
        refreshIcon = refreshBtn.getChildAt(0) as ImageView
        header.addView(refreshBtn)
        header.addView(iconButton(R.drawable.ic_settings, "Settings") { settings() },
            LinearLayout.LayoutParams(px(44), px(44)).apply { leftMargin = px(10) })
        root.addView(header)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(4), px(16), px(28))
        }
        root.addView(ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom navigation: icon in a pill, label below, with a hairline above.
        root.addView(View(this).apply { setBackgroundColor(DIVIDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BAR)
            setPadding(px(8), px(10), px(8), px(10))
        }
        listOf("Home" to R.drawable.ic_home, "Trips" to R.drawable.ic_car,
            "Fuel" to R.drawable.ic_fuel, "Battery" to R.drawable.ic_battery).forEachIndexed { i, (name, iconRes) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { select(i) }
                contentDescription = name
            }
            val pill = FrameLayout(this)
            val icon = ImageView(this).apply { setImageResource(iconRes) }
            pill.addView(icon, FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
            cell.addView(pill, LinearLayout.LayoutParams(px(64), px(32)))
            val label = TextView(this).apply { text = name; textSize = 12f; gravity = Gravity.CENTER; setPadding(0, px(4), 0, 0) }
            cell.addView(label)
            tabs += Tab(pill, icon, label)
            bar.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar)

        // Draw behind the status and navigation bars, and pad for them, on every Android version.
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else {
            root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                b.top to b.bottom
            } else insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            header.setPadding(px(20), top + px(14), px(14), px(12))
            bar.setPadding(px(8), px(10), px(8), bottom + px(10))
            insets
        }
        setContentView(root)

        // Cached data first, so the app opens instantly even offline.
        prefs.getString("cache", null)?.let { data = CarData.parse(it, prefs.getString("cacheName", "") ?: "") }
        select(TAB_HOME)
        if (token.isEmpty()) settings() else refresh()
        checkForUpdate()
    }

    private val myVersion: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0" } catch (_: Exception) { "0" }
    }

    /** Looks for a newer Car Companion in the public launcher repo (no token sent), hourly at most. */
    private fun checkForUpdate(force: Boolean = false) {
        val last = prefs.getLong("updCheckedAt", 0L)
        val cached = prefs.getString("updJson", null)
        if (!force && cached != null && System.currentTimeMillis() - last < UPDATE_CHECK_MS) {
            update = CompanionUpdate.newest(cached, myVersion); render(); return
        }
        Thread {
            val json = try {
                val c = (java.net.URL(RELEASES).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 20_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "CarCompanion")
                }
                try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null }
                finally { c.disconnect() }
            } catch (_: Exception) { null }
            runOnUiThread {
                if (json != null) prefs.edit().putString("updJson", json).putLong("updCheckedAt", System.currentTimeMillis()).apply()
                update = (json ?: cached)?.let { CompanionUpdate.newest(it, myVersion) }
                if (force) Toast.makeText(this, when {
                    update != null -> "Car Companion ${update!!.version} is available"
                    json == null -> "Couldn't check for updates"
                    else -> "You have the latest version ($myVersion)"
                }, Toast.LENGTH_LONG).show()
                render()
            }
        }.start()
    }

    private fun openUpdate(u: CompanionUpdate.Available) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url))) }
        catch (_: Exception) { Toast.makeText(this, "No browser to download the update", Toast.LENGTH_LONG).show() }
    }

    private fun select(t: Int) {
        tab = t
        tabs.forEachIndexed { i, v ->
            val on = i == t
            v.pill.background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                Neon.GRADIENT.map { Neon.alpha(it, 56) }.toIntArray()).apply { cornerRadius = px(16).toFloat() } else null
            v.icon.setColorFilter(if (on) CYAN else DIM)
            v.text.setTextColor(if (on) TEXT else DIM)
            v.text.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        render()
    }

    private fun render() {
        content.removeAllViews()
        val d = data
        subtitle.text = when {
            loading -> "Updating..."
            d == null -> "No data yet"
            else -> "Car data from " + dayFmt.format(Date(d.createdMs)) + ", " + timeFmt.format(Date(d.createdMs))
        }
        if (loading) spin?.start() ?: run {
            spin = ObjectAnimator.ofFloat(refreshIcon, View.ROTATION, 0f, 360f).apply {
                duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); start()
            }
        } else { spin?.cancel(); refreshIcon.rotation = 0f }
        update?.let { u -> content.addView(updateBanner(u), lp(bottom = 12)) }
        if (d == null) {
            content.addView(body(if (token.isEmpty())
                "Tap the settings button and enter your private repo and GitHub token."
            else "Tap refresh to load the car's data."))
            return
        }
        when (tab) {
            TAB_HOME -> renderHome(d)
            TAB_TRIPS -> renderTrips(d)
            TAB_FUEL -> renderFuel(d)
            else -> renderBattery(d)
        }
    }

    // ================================================================ home

    private fun renderHome(d: CarData) {
        val m = d.model
        val now = System.currentTimeMillis()

        // Fuel hero card
        val range = m.rangeKm
        val lvl = FuelModel.level(range)
        val rc = when (lvl) { 4 -> RED; 3 -> AMBER; else -> GREEN }
        val hero = heroBox()
        hero.addView(label("Fuel range"))
        hero.addView(TextView(this).apply {
            text = if (range >= 0) "≈ $range km" else "Set up on the head unit"
            setTextColor(rc); textSize = 38f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, px(2), 0, 0)
        })
        if (d.configured) {
            val frac = (m.litresLeft / d.tank).coerceIn(0f, 1f)
            hero.addView(bar(frac, rc))
            hero.addView(small(String.format(Locale.US, "%.1f L of %.0f L  \u00B7  %.1f km/l", m.litresLeft, d.tank, m.kmplNow)))
            hero.addView(button("Log fill-up", R.drawable.ic_fuel) { logFill(d) }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(46)).apply { topMargin = px(14) })
            if (d.pending.isNotEmpty()) hero.addView(small("Includes ${d.pending.size} fill-up(s) from this phone, waiting for the car to sync."))
        }
        content.addView(hero, lp(bottom = 12))

        val refuel = FuelModel.refuelBy(range, m.dailyKm, d.createdMs)
        val restDay = d.battery.lastOrNull { it.resting > 0f }
        val chargeDay = d.battery.lastOrNull { it.chargeN > 0 }
        val band = restDay?.let { BatteryHealth.restingBand(it.resting) }
        val chargeOk = chargeDay?.let { it.chargeAvg >= BatteryHealth.CHARGE_LOW && it.chargeMax <= BatteryHealth.CHARGE_HIGH }
        grid(listOf(
            Stat(when {
                range in 0..FuelModel.RESERVE_KM -> "Now"
                refuel == null -> "Learning"
                refuel - now < 86_400_000L -> "Today"
                else -> SimpleDateFormat("EEE d", Locale.US).format(Date(refuel))
            }, "Refuel by", when {
                range in 0..90 -> AMBER
                refuel == null -> DIM
                else -> TEXT
            }),
            Stat(if (d.estOdometer >= 0) String.format(Locale.US, "%,d", d.estOdometer) else "--", "Odometer km (est.)", TEXT),
            Stat(restDay?.let { String.format(Locale.US, "%.1f V", it.resting) } ?: "--",
                "Battery at rest" + (band?.let { " \u00B7 " + shortBand(it) } ?: ""), bandColor(band)),
            Stat(chargeDay?.let { String.format(Locale.US, "%.1f V", it.chargeAvg) } ?: "--",
                when (chargeOk) { true -> "Charging \u00B7 OK"; false -> "Charging \u00B7 Low"; null -> "Charging" },
                when (chargeOk) { true -> GREEN; false -> AMBER; null -> TEXT }),
        ))

        // Today
        val today = d.trips.filter { it.start >= TripLog.startOfToday(now) }
        content.addView(section("Today"))
        if (today.isEmpty()) content.addView(body("No finished trips today yet."))
        else grid(listOf(
            Stat(String.format(Locale.US, "%.0f km", today.sumOf { it.km }), "${today.size} trip${if (today.size == 1) "" else "s"}", CYAN),
            Stat(String.format(Locale.US, "≈%.1f L", today.sumOf { litres(d, it).toDouble() }), "Fuel (est.)", TEXT),
            Stat(dur(today.sumOf { it.driveSec }), "Driving", TEXT),
            Stat(dur(today.sumOf { it.idleSec }), "Idling", if (today.sumOf { it.idleSec } > 1200) AMBER else TEXT),
        ))

        // Alerts
        val alerts = ArrayList<Pair<String, Int>>()
        if (band == BatteryHealth.Rest.POOR || band == BatteryHealth.Rest.WEAK)
            alerts += "Battery reads ${shortBand(band!!).lowercase(Locale.US)} at rest (${String.format(Locale.US, "%.1f V", restDay!!.resting)}). Get it tested." to
                (if (band == BatteryHealth.Rest.POOR) RED else AMBER)
        if (chargeOk == false)
            alerts += String.format(Locale.US, "Charging averaged %.1f V while driving (healthy 13.2 - 15.0 V).", chargeDay!!.chargeAvg) to AMBER
        val trend = BatteryHealth.trendPer30Days(d.battery)
        if (trend != null && trend <= -0.04) alerts += "Battery " + BatteryHealth.trendLabel(trend).lowercase(Locale.US) to AMBER
        if (lvl >= 3) alerts += "Fuel low: about $range km left." to (if (lvl == 4) RED else AMBER)
        if (alerts.isNotEmpty()) {
            content.addView(section("Alerts"))
            alerts.forEach { (t, c) -> content.addView(alert(t, c), lp(bottom = 8)) }
        }
        content.addView(hint("Updated by the car after each trip when it has internet."))
    }

    // ================================================================ trips

    private fun renderTrips(d: CarData) {
        val now = System.currentTimeMillis()
        content.addView(section("Totals", top = 4))
        fun tot(from: Long, l: String): Stat {
            val t = TripLog.totals(d.trips, from)
            return Stat(String.format(Locale.US, "%.0f km", t.km), "$l · ${dur(t.driveSec)}", TEXT)
        }
        grid(listOf(
            tot(TripLog.startOfToday(now), "Today"), tot(TripLog.startOfWeek(now), "This week"),
            tot(TripLog.startOfMonth(now), "This month"), tot(0L, "All time"),
        ))
        content.addView(section("Trips"))
        if (d.trips.isEmpty()) { content.addView(body("No finished trips yet.")); return }
        var lastDay = ""
        d.trips.sortedByDescending { it.start }.take(100).forEach { t ->
            val dayName = dayFmt.format(Date(t.start))
            if (dayName != lastDay) {
                lastDay = dayName
                content.addView(label(dayName).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(px(4), px(12), 0, px(8)) })
            }
            val right = String.format(Locale.US, "%.1f km", t.km)
            val detail = "${timeFmt.format(Date(t.start))} - ${timeFmt.format(Date(t.end))}  ·  ${dur(t.durationSec)}  ·  " +
                String.format(Locale.US, "≈%.1f L", litres(d, t))
            content.addView(row(detail, null, right) { tripDetail(d, t) }, lp(bottom = 8))
        }
    }

    private fun tripDetail(d: CarData, t: TripRecord) {
        val hwy = if (t.hwyKm >= 0 && t.km > 0.3) String.format(Locale.US, "%.0f%%", t.hwyKm / t.km * 100) else "--"
        val msg = buildString {
            append("${dayFmt.format(Date(t.start))}, ${timeFmt.format(Date(t.start))} - ${timeFmt.format(Date(t.end))}\n\n")
            append(String.format(Locale.US, "Distance      %.1f km\n", t.km))
            append("Duration      ${dur(t.durationSec)}\n")
            append("Driving       ${dur(t.driveSec)}\n")
            append("Idling        ${dur(t.idleSec)}\n")
            append("Avg speed     ${speed(t.avgKmh)}\n")
            append("Top speed     ${speed(t.maxKmh)}\n")
            append("Highway       $hwy\n")
            append(String.format(Locale.US, "Fuel (est.)   %.2f L  (idling %.2f L)", litres(d, t), FuelModel.idleLitres(t.idleSec)))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Trip").setMessage(msg).setPositiveButton("OK", null).show()
            .findViewById<TextView>(android.R.id.message)?.typeface = Typeface.MONOSPACE
    }

    // ================================================================ fuel

    private fun renderFuel(d: CarData) {
        val m = d.model
        if (!d.configured) { content.addView(body("Fuel isn't set up on the head unit yet.")); return }
        val lastFull = d.fuel.rows.lastOrNull { it.event.kind == FuelEvent.KIND_FILL && it.event.full }
        val sinceFull = lastFull?.let { d.gpsKm - it.event.gpsKm } ?: -1.0
        content.addView(section("Fuel", top = 4))
        content.addView(button("Log fill-up", R.drawable.ic_fuel) { logFill(d) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, px(46)).apply { bottomMargin = px(12) })
        if (d.pending.isNotEmpty()) {
            content.addView(label("Waiting for the car to sync").apply { typeface = Typeface.DEFAULT_BOLD; setPadding(px(4), 0, 0, px(8)) })
            d.pending.sortedBy { it.time }.forEach { f ->
                val parts = listOfNotNull(
                    if (f.litres > 0f) String.format(Locale.US, "%.1f L", f.litres) else null,
                    String.format(Locale.US, "%,d km", f.odo),
                )
                content.addView(row((if (f.full) "Full tank" else "Top-up") + " \u00B7 " + dayFmt.format(Date(f.time)) +
                    ", " + timeFmt.format(Date(f.time)), parts.joinToString("   "), "\u23F3", null), lp(bottom = 8))
            }
            content.addView(hint("The car adds these to its fuel log the next time it's online. The numbers below already include them."))
        }
        grid(listOf(
            Stat("\u2248 ${m.rangeKm} km", "Range left", when (FuelModel.level(m.rangeKm)) { 4 -> RED; 3 -> AMBER; else -> GREEN }),
            Stat(String.format(Locale.US, "%.1f L", m.litresLeft), "In tank (of ${d.tank.toInt()} L)", TEXT),
            Stat(String.format(Locale.US, "%.1f km/l", m.kmplNow),
                if (d.fuel.tanks.isEmpty()) "Mileage (estimate)" else "Mileage (learned)", CYAN),
            Stat(if (sinceFull >= 0) String.format(Locale.US, "%.0f km", sinceFull) else "--", "Since full tank", TEXT),
            Stat(if (m.tanksUsed > 0) String.format(Locale.US, "%.1f", m.kmplCity) else "Learning", "City km/l", if (m.tanksUsed > 0) TEXT else DIM),
            Stat(if (m.tanksUsed > 0) String.format(Locale.US, "%.1f", m.kmplHwy) else "Learning", "Highway km/l", if (m.tanksUsed > 0) TEXT else DIM),
            Stat(String.format(Locale.US, "%.1f L", m.idleLitresSinceFill), "Idling since fill", if (m.idleLitresSinceFill > 1f) AMBER else TEXT),
            Stat(if (m.dailyKm > 0) String.format(Locale.US, "%.0f km", m.dailyKm) else "--", "Per day (avg)", TEXT),
        ))
        content.addView(section("Mileage per tank"))
        content.addView(chartCard(ChartView(this).apply {
            points = d.fuel.tanks.takeLast(12).map { (if (it.time > 0) shortDay.format(Date(it.time)) else "") to it.kmpl }
        }))
        content.addView(section("Fill-ups"))
        val rows = d.fuel.rows.asReversed()
        if (rows.isEmpty()) content.addView(body("No entries yet."))
        rows.forEach { r ->
            val e = r.event
            val what = when (e.kind) {
                FuelEvent.KIND_SETUP -> "Set-up"; FuelEvent.KIND_ODO -> "Odometer"
                else -> if (e.full) "Full tank" else "Top-up"
            }
            val parts = ArrayList<String>()
            if (e.kind == FuelEvent.KIND_FILL && e.litres > 0f) parts += String.format(Locale.US, "%.1f L", e.litres)
            if (r.odoAt >= 0) parts += String.format(Locale.US, "%s%,d km", if (r.odoEstimated) "≈" else "", r.odoAt)
            if (r.kmSincePrev >= 0) parts += String.format(Locale.US, "+%.0f km", r.kmSincePrev)
            val whenTxt = if (e.time > 0) dayFmt.format(Date(e.time)) else "Earlier"
            content.addView(row("$what · $whenTxt", parts.joinToString("   "),
                if (r.measuredKmpl > 0f) String.format(Locale.US, "%.1f km/l", r.measuredKmpl) else "", null), lp(bottom = 8))
        }
        content.addView(hint("Log fill-ups here or on the head unit (Fuel chip > Log fill-up). The same fill entered on both counts once."))
    }

    // ================================================================ battery

    private fun renderBattery(d: CarData) {
        val restDay = d.battery.lastOrNull { it.resting > 0f }
        val chargeDay = d.battery.lastOrNull { it.chargeN > 0 }
        val band = restDay?.let { BatteryHealth.restingBand(it.resting) }
        val ok = chargeDay?.let { it.chargeAvg >= BatteryHealth.CHARGE_LOW && it.chargeMax <= BatteryHealth.CHARGE_HIGH }
        content.addView(section("Battery", top = 4))
        grid(listOf(
            Stat(if (d.lastVoltage > 0) String.format(Locale.US, "%.1f V", d.lastVoltage) else "--", "Last reading", CYAN),
            Stat(restDay?.let { String.format(Locale.US, "%.2f V", it.resting) } ?: "--",
                "At rest · " + (band?.let { shortBand(it) } ?: "no data"), bandColor(band)),
            Stat(chargeDay?.let { String.format(Locale.US, "%.1f V", it.chargeAvg) } ?: "--",
                when (ok) { true -> "Charging · OK"; false -> "Charging · Check"; null -> "Charging" },
                when (ok) { true -> GREEN; false -> AMBER; null -> TEXT }),
            Stat(chargeDay?.let { String.format(Locale.US, "%.1f-%.1f", it.chargeMin, it.chargeMax) } ?: "--", "Charging range (V)", TEXT),
        ))
        content.addView(body("Trend: " + BatteryHealth.trendLabel(BatteryHealth.trendPer30Days(d.battery))))
        content.addView(section("Voltage at rest (each start)"))
        content.addView(chartCard(ChartView(this).apply {
            emptyText = "Appears after a start with the engine not yet running"
            valueFormat = "%.2f"; axisFormat = "%.1f"; minSpan = 0.6f
            points = d.battery.filter { it.resting > 0f }.takeLast(30).map { dayLabel(it.day) to it.resting }
        }))
        content.addView(section("Charging while driving (daily average)"))
        content.addView(chartCard(ChartView(this).apply {
            emptyText = "Appears after driving"
            valueFormat = "%.1f"; axisFormat = "%.1f"; minSpan = 1f
            points = d.battery.filter { it.chargeN > 0 }.takeLast(30).map { dayLabel(it.day) to it.chargeAvg }
        }))
        content.addView(hint("Healthy: at rest 12.4 V or more, charging 13.2 - 15.0 V. The head unit reads about 0.1 - 0.2 V below the battery terminals."))
    }

    // ================================================================ data

    private fun refresh() {
        if (loading) return
        if (token.isEmpty()) { settings(); return }
        loading = true
        render()
        val r = repoName; val t = token; val p = passphrase
        Thread {
            val res = CarCloud.fetch(r, t, p)
            val pending = if (res.data != null) CarCloud.pendingFills(r, t) else emptyList()
            runOnUiThread {
                loading = false
                if (res.data != null && res.rawText != null) {
                    data = res.data.withPending(pending, res.data.fuelEvents)
                    prefs.edit().putString("cache", res.rawText).putString("cacheName", res.data.sourceName).apply()
                } else {
                    Toast.makeText(this, res.error ?: "Couldn't load", Toast.LENGTH_LONG).show()
                }
                render()
            }
        }.start()
    }

    private fun settings() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(22), px(8), px(22), 0) }
        fun field(v: String, hint: String, secret: Boolean) = EditText(this).apply {
            setText(v); this.hint = hint; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
        }
        col.addView(small("Private repo (owner/name)"))
        val repo = field(repoName, "sujithkannimel/car-backups", false); col.addView(repo)
        col.addView(small("Read-only token (github_pat_...)"))
        val tok = field("", if (token.isNotEmpty()) "Saved - leave empty to keep it" else "Paste the token", true); col.addView(tok)
        col.addView(small("Passphrase (only if backups are encrypted)"))
        val pass = field(passphrase, "Leave empty if none", true); col.addView(pass)
        col.addView(TextView(this).apply {
            text = "Car Companion $myVersion  \u00B7  Check for updates"
            setTextColor(CYAN); textSize = 14f; setPadding(0, px(16), 0, px(8))
            setOnClickListener { checkForUpdate(force = true) }
        })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Connect to your car's backups")
            .setView(col)
            .setPositiveButton("Save") { _, _ ->
                repoName = repo.text.toString()
                tok.text.toString().trim().takeIf { it.isNotEmpty() }?.let { token = it }
                passphrase = pass.text.toString()
                refresh()
            }
            .setNeutralButton("Forget") { _, _ ->
                prefs.edit().clear().apply(); data = null; render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun logFill(d: CarData) {
        if (!d.configured) { Toast.makeText(this, "Set up fuel on the head unit first", Toast.LENGTH_LONG).show(); return }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(22), px(8), px(22), 0) }
        val group = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        group.addView(android.widget.RadioButton(this).apply { text = "Filled to full"; id = 1; isChecked = true })
        group.addView(android.widget.RadioButton(this).apply { text = "Added some litres"; id = 2 })
        col.addView(group)
        col.addView(small("Litres from the pump (optional for full - helps it learn)"))
        val litres = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        col.addView(litres)
        val est = d.estOdometer
        col.addView(small(if (est >= 0) "Odometer km (required - about ${String.format(Locale.US, "%,d", est)} by GPS)" else "Odometer km (required)"))
        val odo = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        col.addView(odo)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Log fill-up")
            .setView(col)
            .setPositiveButton("Save") { _, _ ->
                val full = group.checkedRadioButtonId == 1
                val l = litres.text.toString().toFloatOrNull()
                val o = odo.text.toString().toIntOrNull()
                if (o == null || o <= 0) {
                    Toast.makeText(this, "Enter the odometer reading", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                if (est >= 0 && kotlin.math.abs(o - est) > 3000) {
                    Toast.makeText(this, "That odometer is far from the car's (${String.format(Locale.US, "%,d", est)}). Check it.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (!full && (l == null || l <= 0f)) {
                    Toast.makeText(this, "Enter the litres you added", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                val fill = com.minimal.carlauncher.core.InboxFill(
                    java.util.UUID.randomUUID().toString().replace("-", "").take(12),
                    System.currentTimeMillis(), full, l ?: -1f, o)
                Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show()
                val r = repoName; val t = token
                Thread {
                    val err = CarCloud.putFill(r, t, fill)
                    runOnUiThread {
                        if (err == null) {
                            Toast.makeText(this, "Saved. The car adds it next time it's online.", Toast.LENGTH_LONG).show()
                            refresh()
                        } else {
                            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("Not saved").setMessage(err).setPositiveButton("OK", null).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Filled, tonal pill button with an optional leading icon. */
    private fun button(t: String, iconRes: Int = 0, onClick: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(CYAN); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        background = rounded(Color.argb(40, 34, 211, 238), radius = 23)
        setPadding(px(18), 0, px(22), 0)
        if (iconRes != 0) {
            val dr = getDrawable(iconRes)!!.mutate().apply { setTint(CYAN); setBounds(0, 0, px(20), px(20)) }
            setCompoundDrawablesRelative(dr, null, null, null)
            compoundDrawablePadding = px(8)
        }
        setOnClickListener { onClick() }
    }

    private fun updateBanner(u: CompanionUpdate.Available) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = rounded(Color.argb(38, 34, 211, 238), radius = 20)
        setPadding(px(16), px(14), px(16), px(14))
        setOnClickListener { openUpdate(u) }
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_download); setColorFilter(CYAN) },
            LinearLayout.LayoutParams(px(24), px(24)))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, 0, 0) }
        col.addView(TextView(context).apply {
            text = "Update available: ${u.version}"; setTextColor(TEXT); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(context).apply {
            text = "Tap to download, then open it to install. Your settings stay."; setTextColor(DIM); textSize = 13f
        })
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun shortBand(b: BatteryHealth.Rest) = b.label.substringBefore(" -")

    private fun litres(d: CarData, t: TripRecord) =
        FuelModel.tripLitres(t, d.model.kmplCity, d.model.kmplHwy, d.model.recentHwyShare)

    // ================================================================ UI helpers

    private data class Stat(val value: String, val label: String, val color: Int)

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun lp(bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = px(bottom) }

    /** Borderless surface: depth comes from a lighter fill, not an outline. */
    private fun rounded(fill: Int, stroke: Int = Color.TRANSPARENT, radius: Int = 20) = GradientDrawable().apply {
        setColor(fill); cornerRadius = px(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(px(1), stroke)
    }

    /** Round tonal icon button for the header. */
    private fun iconButton(iconRes: Int, desc: String, onClick: () -> Unit) = FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CARD) }
        contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(px(44), px(44))
        addView(ImageView(context).apply { setImageResource(iconRes); setColorFilter(CYAN) },
            FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    private fun cardBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD)
        setPadding(px(18), px(16), px(18), px(16))
    }

    /** The hero card gets a soft diagonal gradient so it stands out from the tiles. */
    private fun heroBox() = cardBox().apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(CARD_HI, CARD)).apply {
            cornerRadius = px(24).toFloat()
        }
        setPadding(px(20), px(18), px(20), px(20))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun small(t: String) = TextView(this).apply {
        text = t; setTextColor(DIM); textSize = 13f; setPadding(0, px(8), 0, px(2))
    }

    private fun section(t: String, top: Int = 18) = TextView(this).apply {
        text = t; setTextColor(TEXT); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        setPadding(px(4), px(top), 0, px(8))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; setTextColor(DIM); textSize = 15f; setPadding(px(4), px(4), px(4), px(8))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; setTextColor(DIM); textSize = 12f; setPadding(px(4), px(12), px(4), 0)
    }

    private fun bar(frac: Float, color: Int) = FrameLayout(this).apply {
        background = rounded(Color.argb(34, 255, 255, 255), radius = 6)
        addView(View(context).apply { background = rounded(color, radius = 6) },
            FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(10)).apply {
            topMargin = px(10); bottomMargin = px(8)
        }
        post { getChildAt(0).layoutParams = FrameLayout.LayoutParams((width * frac).toInt(), ViewGroup.LayoutParams.MATCH_PARENT); requestLayout() }
    }

    /** Two-column grid of stat tiles. */
    private fun grid(stats: List<Stat>) {
        stats.chunked(2).forEach { pair ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, s ->
                val box = cardBox().apply { setPadding(px(16), px(14), px(16), px(14)) }
                box.addView(TextView(this).apply {
                    text = s.value; setTextColor(s.color); textSize = 22f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
                })
                box.addView(label(s.label).apply { setPadding(0, px(4), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                r.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i == 1) leftMargin = px(10)
                })
            }
            if (pair.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = px(10) })
            content.addView(r, lp(bottom = 10))
        }
    }

    private fun chartCard(chart: ChartView) = FrameLayout(this).apply {
        background = rounded(CARD)
        setPadding(px(8), px(8), px(8), px(8))
        addView(chart, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(170)))
    }

    private fun row(title: String, detail: String?, right: String, onClick: (() -> Unit)?): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, radius = 16)
            setPadding(px(14), px(12), px(14), px(12))
            if (onClick != null) setOnClickListener { onClick() }
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply { text = title; setTextColor(TEXT); textSize = 14f })
        if (!detail.isNullOrEmpty()) left.addView(TextView(this).apply {
            text = detail; setTextColor(DIM); textSize = 13f; setPadding(0, px(2), 0, 0)
        })
        r.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right.isNotEmpty()) r.addView(TextView(this).apply {
            text = right; setTextColor(CYAN); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setPadding(px(10), 0, 0, 0)
        })
        return r
    }

    private fun alert(t: String, color: Int) = TextView(this).apply {
        text = t; setTextColor(color); textSize = 14f
        background = rounded(Color.argb(36, Color.red(color), Color.green(color), Color.blue(color)), radius = 16)
        setPadding(px(14), px(12), px(14), px(12))
    }

    private fun bandColor(b: BatteryHealth.Rest?) = when (b) {
        BatteryHealth.Rest.GOOD -> GREEN; BatteryHealth.Rest.OK -> CYAN
        BatteryHealth.Rest.WEAK -> AMBER; BatteryHealth.Rest.POOR -> RED; null -> TEXT
    }

    private fun dayLabel(day: String) =
        BatteryHealth.dayNumber(day)?.let { shortDay.format(Date(it * 86_400_000L)) } ?: day

    private fun dur(sec: Long): String {
        val m = (sec / 60).coerceAtLeast(0)
        return if (m < 60) "$m min" else String.format(Locale.US, "%dh %02dm", m / 60, m % 60)
    }

    private fun speed(kmh: Float) = if (kmh <= 0f) "--" else String.format(Locale.US, "%.0f km/h", kmh)
}
