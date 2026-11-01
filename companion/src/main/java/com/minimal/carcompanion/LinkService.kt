package com.minimal.carcompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.minimal.carlauncher.core.BatteryHealth
import com.minimal.carlauncher.core.CarLink
import com.minimal.carlauncher.core.FuelModel
import com.minimal.carlauncher.core.InboxFill
import com.minimal.carlauncher.core.TripLog
import com.minimal.carlauncher.core.TripRecord
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phone side of the live link. Listens on [CarLink.PORT]; the car connects every ~30 s while
 * it is on the same network (home Wi-Fi, or the Android Auto link), sends its data and gets
 * back fill-ups logged on this phone and, if the phone has one ready, a newer launcher.
 * Messages that don't decrypt with the car's key are dropped without a reply.
 *
 * From what arrives it also raises car alerts, a trip summary with the parking spot when the
 * car goes quiet, and keeps the car's diagnostics for the app.
 */
class LinkService : Service() {

    @Volatile private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())
    private val tripEnd = Runnable { onCarQuiet() }

    override fun onBind(intent: Intent?): IBinder? = null

    private val summaryCheck = object : Runnable {
        override fun run() { checkSummaries(); main.postDelayed(this, 30 * 60_000L) }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNow(getString(R.string.link_waiting))
        Thread({ serve() }, "car-link").start()
        main.postDelayed(summaryCheck, 60_000L)
    }

    /**
     * Sunday from 8 pm: last 7 days against the week before. The 1st of the month from 9 am:
     * last month against the one before. Each sent once.
     */
    private fun checkSummaries() {
        val d = LinkStore.cached(this) ?: return
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        val sp = getSharedPreferences("companion", Context.MODE_PRIVATE)
        val m = d.model
        val lit: (TripRecord) -> Float = { FuelModel.tripLitres(it, m.kmplCity, m.kmplHwy, m.recentHwyShare) }
        val S = com.minimal.carlauncher.core.Summaries
        if (cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY && cal.get(java.util.Calendar.HOUR_OF_DAY) >= 20) {
            val wk = TripLog.startOfWeek(now)
            if (sp.getLong("weekSent", 0L) != wk) {
                sp.edit().putLong("weekSent", wk).apply()
                val a = S.of(d.trips, wk, Long.MAX_VALUE, lit); val b = S.of(d.trips, S.lastWeekStart(now), wk, lit)
                if (a.trips > 0) summaryNote(ID_WEEK, "Your week in the car", a, b, "last week")
            }
        }
        if (cal.get(java.util.Calendar.DAY_OF_MONTH) == 1 && cal.get(java.util.Calendar.HOUR_OF_DAY) >= 9) {
            val mo = TripLog.startOfMonth(now)
            if (sp.getLong("monthSent", 0L) != mo) {
                sp.edit().putLong("monthSent", mo).apply()
                val prev = S.prevMonthStart(now)
                val before = java.util.Calendar.getInstance().apply { timeInMillis = prev; add(java.util.Calendar.MONTH, -1) }.timeInMillis
                val a = S.of(d.trips, prev, mo, lit); val b = S.of(d.trips, before, prev, lit)
                if (a.trips > 0) summaryNote(ID_MONTH, SimpleDateFormat("MMMM", Locale.US).format(Date(prev)) + " in the car", a, b, "the month before")
            }
        }
    }

    private fun summaryNote(id: Int, title: String, a: com.minimal.carlauncher.core.PeriodSummary,
                            b: com.minimal.carlauncher.core.PeriodSummary, prevName: String) {
        val change = com.minimal.carlauncher.core.Summaries.change(a.km, b.km)
        val text = buildString {
            append(String.format(Locale.US, "%.0f km in %d trips", a.km, a.trips))
            if (change.isNotEmpty()) append(" ($change vs $prevName)")
            append(String.format(Locale.US, " · %s driving · ≈%.1f L · idled %s", dur(a.driveSec), a.litres, dur(a.idleSec)))
            if (a.eco >= 0) append(" · Eco ${a.eco}")
        }
        val n = Notification.Builder(this, ensureChannel(CH_TRIPS, R.string.ch_trips, NotificationManager.IMPORTANCE_DEFAULT))
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        main.removeCallbacks(tripEnd)
        main.removeCallbacks(summaryCheck)
        runCatching { server?.close() }
        server = null
        super.onDestroy()
    }

    private fun serve() {
        try {
            val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(CarLink.PORT)) }
            server = ss
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                Thread { handle(s) }.start()
            }
        } catch (_: Exception) {
            stopSelf()
        }
    }

    @Volatile private var lastOfferNote = 0L

    private fun handle(s: Socket) {
        var keepOpen = false
        try {
            try {
                s.soTimeout = 5_000
                val key = LinkStore.key(this) ?: return
                val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: return
                val text = CarLink.open(key, line) ?: return
                val now = System.currentTimeMillis()

                // A spare ADB tunnel from the car: kept open for Termux's adb (bridge on only).
                if (CarLink.isTunnelRequest(text, now)) { keepOpen = AdbBridge.offerSpare(s); return }

                // Second connection: the car wants the launcher APK we offered.
                CarLink.parseApkRequest(text, now)?.let { ver ->
                    val offer = LinkStore.offer(this) ?: run { LinkStore.note(this, "car asked for $ver, but no file here"); return }
                    if (offer.first.version != ver) { LinkStore.note(this, "car asked for $ver, phone has ${offer.first.version}"); return }
                    LinkStore.note(this, "sending launcher $ver to the car")
                    s.soTimeout = 30_000
                    var sent = 0L
                    try {
                        s.getOutputStream().use { out ->
                            offer.second.inputStream().use { inp ->
                                val buf = ByteArray(64 * 1024)
                                while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); sent += n }
                                out.flush()
                            }
                        }
                        LinkStore.note(this, "sent launcher $ver: ${sent / 1024} KB")
                    } catch (e: Exception) {
                        LinkStore.note(this, "sending $ver stopped after ${sent / 1024} KB: ${e.javaClass.simpleName}")
                    }
                    return
                }

                val msg = CarLink.parseCar(text, now) ?: return
                LinkStore.removeLocalFills(this, msg.acked)
                val offer = LinkStore.offerFor(this, msg.extra.appVersion)
                if (offer != null && now - lastOfferNote > 5 * 60_000L) {
                    lastOfferNote = now
                    LinkStore.note(this, "offering launcher ${offer.version} to the car (car has ${msg.extra.appVersion})")
                }
                // The car's music buttons drive the phone's player; then report what plays.
                msg.extra.cmd?.let { PhoneMedia.run(this, it) }
                val np = if (LinkStore.mediaOn(this)) PhoneMedia.nowPlaying(this, msg.extra.npArtKey) else null
                val reply = CarLink.seal(key, CarLink.phoneMessage(LinkStore.localFills(this), now, offer, np, AdbBridge.isOn(now)))
                s.getOutputStream().apply { write((reply + "\n").toByteArray()); flush() }
                LinkStore.saveLive(this, msg, s.inetAddress?.hostAddress.orEmpty())
                main.post {
                    startForegroundNow(getString(R.string.link_live, SimpleDateFormat("h:mm a", Locale.US).format(Date(now))))
                    LinkStore.notifyListeners()
                    onCarMessage(msg)
                }
            } catch (e: Exception) {
                LinkStore.note(this, "link error: ${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            if (!keepOpen) runCatching { s.close() }
        }
    }

    // ------------------------------------------------------------------ alerts and trip summary

    private fun onCarMessage(msg: CarLink.CarMsg) {
        // The car stopped talking for 3 minutes: it was switched off (or left the phone behind).
        main.removeCallbacks(tripEnd)
        main.postDelayed(tripEnd, QUIET_MS)

        val trip = msg.live
        when (msg.extra.chargeAlert) {
            1 -> alertOnce("charge1:${trip?.start}", "Battery not charging",
                "The alternator isn't charging while driving (${volts(msg.voltage)}). Get the charging system checked.")
            2 -> alertOnce("charge2:${trip?.start}", "Battery overcharging",
                "Charging voltage is too high (${volts(msg.voltage)}). Get the charging system checked.")
        }

        val data = LinkStore.cached(this) ?: return
        val range = data.model.rangeKm
        when (FuelModel.level(range)) {
            4 -> alertOnce("fuel4:${today()}", "Fuel very low", "About $range km left. Refuel soon.")
            3 -> alertOnce("fuel3:${today()}", "Fuel low", "About $range km left.")
        }

        val days = data.battery.filter { it.resting > 0f }
        val last = days.lastOrNull()
        if (last != null && last.day == todayDay()) {
            val prev = days.dropLast(1).lastOrNull()
            when {
                BatteryHealth.restingBand(last.resting) >= BatteryHealth.Rest.WEAK -> alertOnce("bat:${last.day}", "Battery weak this morning",
                    "It read ${volts(last.resting)} before starting. Get it tested before it fails to start.")
                prev != null && prev.resting - last.resting >= 0.3f -> alertOnce("batdrop:${last.day}", "Battery dropped overnight",
                    "${volts(prev.resting)} yesterday, ${volts(last.resting)} this morning. Something may be draining it.")
            }
        }
        data.battery.lastOrNull { it.crank > 0f }?.takeIf { it.day == todayDay() }?.let { d ->
            if (BatteryHealth.crankBand(d.crank) >= BatteryHealth.Crank.WEAK)
                alertOnce("crank:${d.day}", "Weak start this morning",
                    "Voltage dipped to ${String.format(java.util.Locale.US, "%.1f V", d.crank)} while cranking. " +
                        "A battery near the end of its life shows this first - get it tested.")
        }
        data.perf.lastOrNull()?.let { r ->
            val pt = com.minimal.carlauncher.core.PerfTimer
            if (System.currentTimeMillis() - r.at < 30 * 60_000L && pt.best(data.perf, r.kind) == r && data.perf.count { it.kind == r.kind } > 1)
                alertOnce("perf:${r.at}", "New best ${r.kind.replace("-", "\u2013")} km/h",
                    String.format(java.util.Locale.US, "%.1f s", r.sec))
        }
    }

    private fun onCarQuiet() {
        val trip = LinkStore.lastLiveTrip(this) ?: return
        if (trip.isParked && trip.idleSec < TripLog.PARKED_MIN_IDLE_SEC) return
        if (!TripLog.worthKeeping(trip)) return
        val sp = getSharedPreferences("companion", Context.MODE_PRIVATE)
        if (sp.getLong("summaryFor", 0L) == trip.start) return
        sp.edit().putLong("summaryFor", trip.start).apply()

        val m = LinkStore.cached(this)?.model
        val litres = if (m != null) FuelModel.tripLitres(trip, m.kmplCity, m.kmplHwy, m.recentHwyShare) else -1f
        val text = if (trip.isParked) {
            String.format(Locale.US, "Parked with the engine running: %s (≈%.1f L)", dur(trip.idleSec), FuelModel.idleLitres(trip.idleSec))
        } else buildString {
            append(String.format(Locale.US, "%.1f km · %s", trip.km, dur(trip.durationSec)))
            if (litres > 0f) append(String.format(Locale.US, " · ≈%.1f L", litres))
            if (trip.idleSec >= 60) append(" · idled ${dur(trip.idleSec)}")
            com.minimal.carlauncher.core.EcoScore.of(trip)?.let { append(" · Eco ${it.score} (${it.tip.lowercase(Locale.US)})") }
        }
        val pos = LinkStore.pos(this)
        val b = Notification.Builder(this, ensureChannel(CH_TRIPS, R.string.ch_trips, NotificationManager.IMPORTANCE_DEFAULT))
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(if (trip.isParked) "Engine was running while parked" else "Trip done")
            .setContentText(text + if (pos != null) " · Tap for where you parked" else "")
            .setStyle(Notification.BigTextStyle().bigText(text + if (pos != null) "\nParked at " + time(pos.at) else ""))
            .setContentIntent(openApp())
            .setAutoCancel(true)
        if (pos != null) b.addAction(Notification.Action.Builder(null, "Where I parked", mapIntent(pos)).build())
        getSystemService(NotificationManager::class.java).notify(ID_TRIP, b.build())
    }

    private fun alertOnce(key: String, title: String, text: String) {
        val sp = getSharedPreferences("companion", Context.MODE_PRIVATE)
        val seen = sp.getStringSet("alertsSent", emptySet()) ?: emptySet()
        if (key in seen) return
        sp.edit().putStringSet("alertsSent", (seen + key).toList().takeLast(60).toSet()).apply()
        val n = Notification.Builder(this, ensureChannel(CH_ALERTS, R.string.ch_alerts, NotificationManager.IMPORTANCE_HIGH))
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(key.hashCode(), n)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun mapIntent(p: CarLink.Pos): PendingIntent = PendingIntent.getActivity(this, 1,
        Intent(Intent.ACTION_VIEW, LinkStore.mapUri(p)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun ensureChannel(id: String, nameRes: Int, importance: Int): String {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, getString(nameRes), importance))
        return id
    }

    private fun startForegroundNow(text: String) {
        val n = Notification.Builder(this, ensureChannel(CH_LINK, R.string.link_channel, NotificationManager.IMPORTANCE_MIN))
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(getString(R.string.link_title))
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTE_ID, n)
    }

    private fun volts(v: Float) = String.format(Locale.US, "%.1f V", v)
    private fun time(ms: Long) = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun todayDay() = today()
    private fun dur(sec: Long): String {
        val m = (sec / 60).coerceAtLeast(0)
        return if (m < 60) "$m min" else String.format(Locale.US, "%dh %02dm", m / 60, m % 60)
    }

    companion object {
        private const val CH_LINK = "car_link"
        private const val CH_ALERTS = "car_alerts"
        private const val CH_TRIPS = "car_trips"
        private const val NOTE_ID = 7
        private const val ID_TRIP = 8
        private const val ID_WEEK = 9
        private const val ID_MONTH = 10
        private const val QUIET_MS = 3 * 60_000L

        fun start(context: Context) {
            if (LinkStore.key(context) == null || !LinkStore.enabled(context)) return
            runCatching { context.startForegroundService(Intent(context, LinkService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }
}

/** Starts the listener after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) LinkService.start(context)
    }
}

/** Live-link state in the app's prefs, shared by the service and the screen. */
object LinkStore {
    private fun sp(c: Context) = c.getSharedPreferences("companion", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun notifyListeners() = listeners.forEach { it() }

    fun key(c: Context): String? = sp(c).getString("linkKey", null)
    fun setKey(c: Context, k: String) = sp(c).edit().putString("linkKey", k).apply()
    fun enabled(c: Context) = sp(c).getBoolean("linkOn", true)
    fun setEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("linkOn", on).apply()

    fun liveAt(c: Context) = sp(c).getLong("liveAt", 0L)
    /** Share the phone's now-playing (song, cover, queue) with the car. */
    fun mediaOn(c: Context) = sp(c).getBoolean("mediaOn", true)
    fun setMediaOn(c: Context, on: Boolean) = sp(c).edit().putBoolean("mediaOn", on).apply()
    fun carVersion(c: Context): String = sp(c).getString("carVersion", "") ?: ""
    fun diag(c: Context): String = sp(c).getString("diag", "") ?: ""

    /** The phone's own link log (newest first, last 20 lines), shown above the car's diagnostics. */
    @Synchronized
    fun note(c: Context, line: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val old = phoneLog(c).lines().filter { it.isNotBlank() }.take(19)
        sp(c).edit().putString("phoneLog", (listOf("$t $line") + old).joinToString("\n")).apply()
    }
    fun phoneLog(c: Context): String = sp(c).getString("phoneLog", "") ?: ""

    @Synchronized
    fun saveLive(c: Context, m: CarLink.CarMsg, from: String) {
        val e = sp(c).edit()
            .putString("cache", m.backupJson).putString("cacheName", "live")
            .putString("liveTrip", m.live?.let { TripLog.encode(listOf(it)) } ?: "")
            .putFloat("liveVolt", m.voltage)
            .putLong("liveAt", System.currentTimeMillis())
            .putString("liveFrom", from)
        if (m.extra.appVersion.isNotEmpty()) e.putString("carVersion", m.extra.appVersion)
        m.extra.diag?.let { e.putString("diag", it) }
        m.extra.pos?.let { e.putString("pos", "${it.lat},${it.lon},${it.at},${it.speed}") }
        e.apply()
    }

    /** Last position the car reported (where it is now, or where it was parked). */
    fun pos(c: Context): CarLink.Pos? = sp(c).getString("pos", null)?.split(',')?.takeIf { it.size == 4 }?.let {
        runCatching { CarLink.Pos(it[0].toDouble(), it[1].toDouble(), it[2].toLong(), it[3].toFloat()) }.getOrNull()
    }

    fun mapUri(p: CarLink.Pos): Uri = Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(My car)")

    fun lastLiveTrip(c: Context): TripRecord? = TripLog.decode(sp(c).getString("liveTrip", "")).firstOrNull()

    /** The cached data, with the live trip applied when it came over the link. */
    fun cached(c: Context): CarData? {
        val p = sp(c)
        val d = p.getString("cache", null)?.let { CarData.parse(it, p.getString("cacheName", "") ?: "") } ?: return null
        if (p.getString("cacheName", "") != "live") return d
        val trip = TripLog.decode(p.getString("liveTrip", "")).firstOrNull()
        return d.withLive(trip, p.getFloat("liveVolt", -1f))
    }

    // ------------------------------------------------------------------ launcher update for the car

    fun launcherFile(c: Context) = File(c.filesDir, "car-launcher.apk")

    /** The launcher APK this phone holds for the car, with its offer details. */
    fun offer(c: Context): Pair<CarLink.Offer, File>? {
        val p = sp(c)
        val ver = p.getString("offerVer", null) ?: return null
        val f = launcherFile(c)
        if (!f.exists()) return null
        return CarLink.Offer(ver, p.getLong("offerSize", 0L), p.getString("offerSha", "") ?: "") to f
    }

    fun setOffer(c: Context, ver: String, size: Long, sha: String) =
        sp(c).edit().putString("offerVer", ver).putLong("offerSize", size).putString("offerSha", sha).apply()

    fun clearOffer(c: Context) {
        sp(c).edit().remove("offerVer").remove("offerSize").remove("offerSha").apply()
        launcherFile(c).delete()
    }

    /** The offer to send, or null when the car already runs that version (then it's cleaned up). */
    fun offerFor(c: Context, carVersion: String): CarLink.Offer? {
        val o = offer(c)?.first ?: return null
        if (carVersion.isNotEmpty() && com.minimal.carlauncher.core.CompanionUpdate.compare(o.version, carVersion) <= 0) {
            clearOffer(c); return null
        }
        return o
    }

    // ------------------------------------------------------------------ fill-ups kept for the link

    @Synchronized
    fun localFills(c: Context): List<InboxFill> = try {
        val a = JSONArray(sp(c).getString("localFills", "[]"))
        (0 until a.length()).mapNotNull { InboxFill.parse(a.getString(it)) }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun addLocalFill(c: Context, f: InboxFill) {
        val list = localFills(c).filter { it.id != f.id } + f
        sp(c).edit().putString("localFills", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    @Synchronized
    fun removeLocalFills(c: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val list = localFills(c).filter { it.id !in ids }
        sp(c).edit().putString("localFills", JSONArray(list.map { it.toJson() }).toString()).apply()
    }
}
