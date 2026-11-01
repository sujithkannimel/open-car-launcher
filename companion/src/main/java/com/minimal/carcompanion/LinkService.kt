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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.minimal.carlauncher.core.CarLink
import com.minimal.carlauncher.core.InboxFill
import com.minimal.carlauncher.core.TripLog
import org.json.JSONArray
import java.io.BufferedReader
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
 * back any fill-ups logged on this phone. Messages that don't decrypt with the car's key are
 * dropped without a reply.
 */
class LinkService : Service() {

    @Volatile private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNow(getString(R.string.link_waiting))
        Thread({ serve() }, "car-link").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
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

    private fun handle(s: Socket) {
        s.use {
            try {
                s.soTimeout = 5_000
                val key = LinkStore.key(this) ?: return
                val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: return
                val text = CarLink.open(key, line) ?: return
                val now = System.currentTimeMillis()
                val msg = CarLink.parseCar(text, now) ?: return
                LinkStore.removeLocalFills(this, msg.acked)
                val reply = CarLink.seal(key, CarLink.phoneMessage(LinkStore.localFills(this), now))
                s.getOutputStream().apply { write((reply + "\n").toByteArray()); flush() }
                LinkStore.saveLive(this, msg, s.inetAddress?.hostAddress.orEmpty())
                main.post {
                    startForegroundNow(getString(R.string.link_live, SimpleDateFormat("h:mm a", Locale.US).format(Date(now))))
                    LinkStore.notifyListeners()
                }
            } catch (_: Exception) {}
        }
    }

    private fun startForegroundNow(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.link_channel),
                NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(getString(R.string.link_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTE_ID, n)
    }

    companion object {
        private const val CHANNEL = "car_link"
        private const val NOTE_ID = 7

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

    @Synchronized
    fun saveLive(c: Context, m: CarLink.CarMsg, from: String) {
        sp(c).edit()
            .putString("cache", m.backupJson).putString("cacheName", "live")
            .putString("liveTrip", m.live?.let { TripLog.encode(listOf(it)) } ?: "")
            .putFloat("liveVolt", m.voltage)
            .putLong("liveAt", System.currentTimeMillis())
            .putString("liveFrom", from)
            .apply()
    }

    /** The cached data, with the live trip applied when it came over the link. */
    fun cached(c: Context): CarData? {
        val p = sp(c)
        val d = p.getString("cache", null)?.let { CarData.parse(it, p.getString("cacheName", "") ?: "") } ?: return null
        if (p.getString("cacheName", "") != "live") return d
        val trip = TripLog.decode(p.getString("liveTrip", "")).firstOrNull()
        return d.withLive(trip, p.getFloat("liveVolt", -1f))
    }

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
