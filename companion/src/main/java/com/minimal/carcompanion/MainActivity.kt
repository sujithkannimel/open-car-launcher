package com.minimal.carcompanion

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.minimal.carlauncher.core.BatteryHealth
import com.minimal.carlauncher.core.FuelEvent
import com.minimal.carlauncher.core.FuelModel
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
        private val BG = Color.rgb(13, 17, 23)
        private val CARD = Color.rgb(22, 27, 34)
        private val STROKE = Color.rgb(48, 54, 61)
        private val DIM = Color.rgb(130, 150, 170)
        private val TEXT = Color.rgb(225, 232, 240)
        private val CYAN = Color.rgb(34, 211, 238)
        private val GREEN = Color.rgb(74, 222, 128)
        private val AMBER = Color.rgb(251, 191, 36)
        private val RED = Color.rgb(244, 63, 94)

        private const val TAB_HOME = 0
        private const val TAB_TRIPS = 1
        private const val TAB_FUEL = 2
        private const val TAB_BATTERY = 3
    }

    private lateinit var content: LinearLayout
    private lateinit var subtitle: TextView
    private val tabs = ArrayList<TextView>()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), px(18), px(12), px(10))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "Car Companion"; setTextColor(TEXT); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        })
        subtitle = TextView(this).apply { setTextColor(DIM); textSize = 13f }
        titles.addView(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton("↻") { refresh() })
        header.addView(iconButton("⚙") { settings() })
        root.addView(header)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(4), px(16), px(24))
        }
        root.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom tabs
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CARD)
            setPadding(px(8), px(8), px(8), px(10))
        }
        listOf("Home", "Trips", "Fuel", "Battery").forEachIndexed { i, label ->
            val t = TextView(this).apply {
                text = label; gravity = Gravity.CENTER; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, px(12), 0, px(12))
                setOnClickListener { select(i) }
            }
            tabs += t
            bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = px(4); rightMargin = px(4)
            })
        }
        root.addView(bar)
        setContentView(root)

        // Cached data first, so the app opens instantly even offline.
        prefs.getString("cache", null)?.let { data = CarData.parse(it, prefs.getString("cacheName", "") ?: "") }
        select(TAB_HOME)
        if (token.isEmpty()) settings() else refresh()
    }

    private fun select(t: Int) {
        tab = t
        tabs.forEachIndexed { i, v ->
            v.setTextColor(if (i == t) CYAN else DIM)
            v.background = if (i == t) rounded(Color.argb(40, 34, 211, 238), Color.argb(120, 34, 211, 238), 12) else null
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
        if (d == null) {
            content.addView(body(if (token.isEmpty())
                "Tap ⚙ and enter your private repo and a read-only GitHub token."
            else "Tap ↻ to load the car's data."))
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
        val hero = cardBox()
        hero.addView(label("FUEL RANGE"))
        hero.addView(TextView(this).apply {
            text = if (range >= 0) "≈ $range km" else "Set up on the head unit"
            setTextColor(rc); textSize = 34f; typeface = Typeface.DEFAULT_BOLD
        })
        if (d.configured) {
            val frac = (m.litresLeft / d.tank).coerceIn(0f, 1f)
            hero.addView(bar(frac, rc))
            hero.addView(small(String.format(Locale.US, "%.1f L of %.0f L  ·  %.1f km/l", m.litresLeft, d.tank, m.kmplNow)))
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
                refuel == null -> "--"
                refuel - now < 86_400_000L -> "Today"
                else -> SimpleDateFormat("EEE d", Locale.US).format(Date(refuel))
            }, "REFUEL BY", if (range in 0..90) AMBER else TEXT),
            Stat(if (d.estOdometer >= 0) String.format(Locale.US, "%,d", d.estOdometer) else "--", "ODOMETER KM (EST.)", TEXT),
            Stat(restDay?.let { String.format(Locale.US, "%.1f V", it.resting) } ?: "--",
                "BATTERY " + (band?.label?.uppercase(Locale.US) ?: "AT REST"), bandColor(band)),
            Stat(chargeDay?.let { String.format(Locale.US, "%.1f V", it.chargeAvg) } ?: "--",
                when (chargeOk) { true -> "CHARGING OK"; false -> "CHARGING LOW"; null -> "CHARGING" },
                when (chargeOk) { true -> GREEN; false -> AMBER; null -> TEXT }),
        ))

        // Today
        val today = d.trips.filter { it.start >= TripLog.startOfToday(now) }
        content.addView(section("Today"))
        if (today.isEmpty()) content.addView(body("No finished trips today yet."))
        else grid(listOf(
            Stat(String.format(Locale.US, "%.0f km", today.sumOf { it.km }), "${today.size} TRIP${if (today.size == 1) "" else "S"}", CYAN),
            Stat(String.format(Locale.US, "≈%.1f L", today.sumOf { litres(d, it).toDouble() }), "FUEL (EST.)", TEXT),
            Stat(dur(today.sumOf { it.driveSec }), "DRIVING", TEXT),
            Stat(dur(today.sumOf { it.idleSec }), "IDLING", if (today.sumOf { it.idleSec } > 1200) AMBER else TEXT),
        ))

        // Alerts
        val alerts = ArrayList<Pair<String, Int>>()
        if (band == BatteryHealth.Rest.POOR || band == BatteryHealth.Rest.WEAK)
            alerts += "Battery reads ${band!!.label.lowercase(Locale.US)} at rest (${String.format(Locale.US, "%.1f V", restDay!!.resting)}). Get it tested." to
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
            tot(TripLog.startOfToday(now), "TODAY"), tot(TripLog.startOfWeek(now), "THIS WEEK"),
            tot(TripLog.startOfMonth(now), "THIS MONTH"), tot(0L, "ALL"),
        ))
        content.addView(section("Trips"))
        if (d.trips.isEmpty()) { content.addView(body("No finished trips yet.")); return }
        var lastDay = ""
        d.trips.sortedByDescending { it.start }.take(100).forEach { t ->
            val dayName = dayFmt.format(Date(t.start))
            if (dayName != lastDay) {
                lastDay = dayName
                content.addView(label(dayName.uppercase(Locale.US)).apply { setPadding(px(4), px(10), 0, px(6)) })
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
        grid(listOf(
            Stat("≈ ${m.rangeKm} km", "RANGE LEFT", when (FuelModel.level(m.rangeKm)) { 4 -> RED; 3 -> AMBER; else -> GREEN }),
            Stat(String.format(Locale.US, "%.1f L", m.litresLeft), "IN TANK (OF ${d.tank.toInt()} L)", TEXT),
            Stat(String.format(Locale.US, "%.1f km/l", m.kmplNow),
                if (d.fuel.tanks.isEmpty()) "MILEAGE (ESTIMATE)" else "MILEAGE (LEARNED)", CYAN),
            Stat(if (sinceFull >= 0) String.format(Locale.US, "%.0f km", sinceFull) else "--", "SINCE FULL TANK", TEXT),
            Stat(if (m.tanksUsed > 0) String.format(Locale.US, "%.1f", m.kmplCity) else "learning", "CITY KM/L", TEXT),
            Stat(if (m.tanksUsed > 0) String.format(Locale.US, "%.1f", m.kmplHwy) else "learning", "HIGHWAY KM/L", TEXT),
            Stat(String.format(Locale.US, "%.1f L", m.idleLitresSinceFill), "IDLING SINCE FILL", if (m.idleLitresSinceFill > 1f) AMBER else TEXT),
            Stat(if (m.dailyKm > 0) String.format(Locale.US, "%.0f km", m.dailyKm) else "--", "PER DAY (AVG)", TEXT),
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
        content.addView(hint("Fill-ups are logged on the head unit (Fuel chip > Log fill-up)."))
    }

    // ================================================================ battery

    private fun renderBattery(d: CarData) {
        val restDay = d.battery.lastOrNull { it.resting > 0f }
        val chargeDay = d.battery.lastOrNull { it.chargeN > 0 }
        val band = restDay?.let { BatteryHealth.restingBand(it.resting) }
        val ok = chargeDay?.let { it.chargeAvg >= BatteryHealth.CHARGE_LOW && it.chargeMax <= BatteryHealth.CHARGE_HIGH }
        content.addView(section("Battery", top = 4))
        grid(listOf(
            Stat(if (d.lastVoltage > 0) String.format(Locale.US, "%.1f V", d.lastVoltage) else "--", "LAST READING", CYAN),
            Stat(restDay?.let { String.format(Locale.US, "%.2f V", it.resting) } ?: "--",
                "AT REST · " + (band?.label?.uppercase(Locale.US) ?: "NO DATA"), bandColor(band)),
            Stat(chargeDay?.let { String.format(Locale.US, "%.1f V", it.chargeAvg) } ?: "--",
                when (ok) { true -> "CHARGING · OK"; false -> "CHARGING · CHECK"; null -> "CHARGING" },
                when (ok) { true -> GREEN; false -> AMBER; null -> TEXT }),
            Stat(chargeDay?.let { String.format(Locale.US, "%.1f-%.1f", it.chargeMin, it.chargeMax) } ?: "--", "CHARGING RANGE (V)", TEXT),
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
            runOnUiThread {
                loading = false
                if (res.data != null && res.rawText != null) {
                    data = res.data
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

    private fun litres(d: CarData, t: TripRecord) =
        FuelModel.tripLitres(t, d.model.kmplCity, d.model.kmplHwy, d.model.recentHwyShare)

    // ================================================================ UI helpers

    private data class Stat(val value: String, val label: String, val color: Int)

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun lp(bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = px(bottom) }

    private fun rounded(fill: Int, stroke: Int = STROKE, radius: Int = 16) = GradientDrawable().apply {
        setColor(fill); cornerRadius = px(radius).toFloat(); setStroke(px(1), stroke)
    }

    private fun iconButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(CYAN); textSize = 24f; gravity = Gravity.CENTER
        setPadding(px(12), px(4), px(12), px(4))
        setOnClickListener { onClick() }
    }

    private fun cardBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD)
        setPadding(px(18), px(16), px(18), px(16))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
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
        background = rounded(Color.argb(40, 255, 255, 255), Color.TRANSPARENT, 6)
        addView(View(context).apply { background = rounded(color, color, 6) },
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
                val box = cardBox().apply { setPadding(px(14), px(12), px(14), px(12)) }
                box.addView(TextView(this).apply {
                    text = s.value; setTextColor(s.color); textSize = 21f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
                })
                box.addView(label(s.label).apply { setPadding(0, px(4), 0, 0) })
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
            background = rounded(CARD, STROKE, 12)
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
        background = rounded(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)), color, 12)
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
