package com.minimal.carlauncher.panel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import com.minimal.carlauncher.core.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Overlay panel service that draws the bottom strip in the 170 px black area
 * between the two physical knobs on the K2401 head unit.
 *
 * **Positioning (proven and mandatory):**
 * - Screen: 1280×720 px at 160 dpi
 * - Firmware limits app windows to 550 px high, leaving a 170 px black strip
 * - The overlay uses `Gravity.TOP | Gravity.LEFT` with `y = screenHeight - panelHeight`
 * - DO NOT use `Gravity.BOTTOM` — it lands too high due to the 550 px window limit
 * - Side padding: ~200 dp for the physical dual knobs
 *
 * **Lifecycle:**
 * This service is started by [com.minimal.carlauncher.ui.HomeActivity] in its `onResume`.
 * Since the launcher is the HOME app, Android always restarts it after ACC on, which in turn
 * restarts this service. The NWD firmware force-stops all third-party apps on ACC off and sets
 * `stopped=true`, blocking all broadcasts — but a HOME activity start is not a broadcast, so
 * this bypass works where BOOT_COMPLETED / ACC_ON receivers cannot.
 */
class PanelService : Service() {

    private var wm: WindowManager? = null
    private var panel: View? = null
    private var handle: View? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var hidden = false
    private var dens = 1f

    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            checkViews()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        val action = intent?.action

        // Foreground notification — MIN importance so it's silent and barely visible.
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bottom panel", NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Bottom panel is running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        )

        if (!Settings.canDrawOverlays(this)) {
            log("overlay permission missing — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        dens = resources.displayMetrics.density

        registerScreenReceiver()

        // Wake action: always force-rebuild (called after ACC on via the launcher).
        if (action == ACTION_WAKE) {
            log("wake — force rebuilding panel")
            hidden = false
            build()
            restartWatchdog()
            return START_STICKY
        }

        // Ping (keep-alive): only rebuild if views are gone.
        if (action == ACTION_PING) {
            val ok = if (hidden) handle?.isAttachedToWindow == true
            else panel?.isAttachedToWindow == true
            if (ok) return START_STICKY
            log("ping — views missing, rebuilding")
        }

        log(
            when {
                intent == null -> "service restarted by system"
                action == ACTION_PING -> "revived by keep-alive"
                else -> "service started"
            }
        )
        hidden = false
        build()
        restartWatchdog()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(watchdog)
        unregisterScreenReceiver()
        clear()
        log("service destroyed")
        super.onDestroy()
    }

    // --------------------------------------------------------------------- view management

    private fun build() {
        clear()
        val real = DisplayMetrics()
        wm?.defaultDisplay?.getRealMetrics(real) ?: return

        if (hidden) {
            addHandle(real)
        } else {
            addPanel(real)
        }
    }

    /**
     * The full bottom panel: dark bar with icon tiles on left/right and a clock in the center.
     *
     * Layout:
     * ```
     * [knob margin] [Apps] [Nav]    08:23    [Phone] [Hide] [knob margin]
     * ```
     */
    private fun addPanel(real: DisplayMetrics) {
        val panelHeightPx = Prefs.panelHeight    // stored in px, default 170
        val sideMarginDp = Prefs.panelSideMargin // dp, default 200
        val tileSize = px(Math.max(30f, Math.min(70f, (panelHeightPx / dens) - 20f)))

        val root = FrameLayout(this).apply {
            setBackgroundColor(PANEL_BG)
            setPadding(px(sideMarginDp.toFloat()), 0, px(sideMarginDp.toFloat()), 0)
        }

        // Left group: Apps + Navigation
        val left = row().apply {
            addView(tile("⊞", COLOR_APPS, tileSize) { openAppGrid() })
            addView(tile("▲", COLOR_NAV, tileSize) { openMaps() })
        }
        root.addView(left, FrameLayout.LayoutParams(W, W, Gravity.START or Gravity.CENTER_VERTICAL))

        // Center: clock
        val clockLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val timeClock = TextClock(this).apply {
            setFormat12Hour("h:mm")
            setFormat24Hour("HH:mm")
            setTextColor(Color.WHITE)
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val dateClock = TextClock(this).apply {
            setFormat12Hour("EEE, d MMM")
            setFormat24Hour("EEE, d MMM")
            setTextColor(Color.rgb(180, 190, 200))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        clockLayout.addView(timeClock, LinearLayout.LayoutParams(W, W))
        clockLayout.addView(dateClock, LinearLayout.LayoutParams(W, W))
        root.addView(clockLayout, FrameLayout.LayoutParams(W, W, Gravity.CENTER))

        // Right group: Phone + Hide
        val right = row().apply {
            addView(tile("☎", COLOR_PHONE, tileSize) { openDialer() })
            addView(tile("▼", COLOR_HIDE, tileSize) {
                hidden = true
                build()
            })
        }
        root.addView(right, FrameLayout.LayoutParams(W, W, Gravity.END or Gravity.CENTER_VERTICAL))

        try {
            wm?.addView(root, overlayParams(M, panelHeightPx, real.heightPixels - panelHeightPx))
            panel = root
        } catch (e: Exception) {
            log("panel addView failed: $e")
        }
    }

    /**
     * A small "Show panel" handle when the panel is hidden.
     */
    private fun addHandle(real: DisplayMetrics) {
        val panelHeightPx = Prefs.panelHeight
        val h = px(36f)

        val t = TextView(this).apply {
            text = "▲ Show panel"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(60, 66, 76))
            setOnClickListener {
                hidden = false
                build()
            }
        }

        try {
            wm?.addView(
                t,
                overlayParams(px(140f), h, real.heightPixels - panelHeightPx + (panelHeightPx - h) / 2)
                    .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
            )
            handle = t
        } catch (e: Exception) {
            log("handle addView failed: $e")
        }
    }

    // --------------------------------------------------------------------- overlay window params

    /**
     * Creates WindowManager.LayoutParams anchored at **TOP|LEFT** with a computed y offset.
     * Using Gravity.BOTTOM does NOT work on this head unit — the firmware's 550 px app window
     * limit causes bottom-anchored overlays to land too high.
     */
    private fun overlayParams(w: Int, h: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = 0
            this.y = y
        }

    // --------------------------------------------------------------------- helper views

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun tile(label: String, color: Int, size: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = px(14f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(px(5f), 0, px(5f), 0)
            }
            setOnClickListener { onClick() }
        }

    // --------------------------------------------------------------------- app launchers

    private fun openAppGrid() {
        try {
            val intent = Intent(this, Class.forName("com.minimal.carlauncher.ui.HomeActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("open_drawer", true)
            startActivity(intent)
        } catch (e: Exception) {
            log("openAppGrid failed: $e")
        }
    }

    private fun openMaps() {
        val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        startSafely(intent)
    }

    private fun openDialer() {
        startSafely(Intent(Intent.ACTION_DIAL))
    }

    private fun startSafely(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --------------------------------------------------------------------- watchdog & screen receiver

    private fun checkViews() {
        if (wm == null) return
        if (hidden) {
            if (handle == null || handle?.isAttachedToWindow != true) {
                log("handle missing — re-adding")
                build()
            }
        } else {
            if (panel == null || panel?.isAttachedToWindow != true) {
                log("panel window gone — re-adding")
                build()
            }
        }
    }

    private fun restartWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (wm == null) return
                val act = intent.action
                if (act == Intent.ACTION_SCREEN_ON || act == Intent.ACTION_USER_PRESENT) {
                    if (!hidden && panel?.isAttachedToWindow != true) {
                        log("$act — re-adding panel")
                        build()
                    } else if (hidden && handle?.isAttachedToWindow != true) {
                        log("$act — re-adding handle")
                        build()
                    }
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenReceiver, filter)
        } catch (_: Exception) {}
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
    }

    // --------------------------------------------------------------------- utility

    private fun px(dp: Float): Int = Math.round(dp * dens)

    private fun remove(v: View?) {
        if (v != null && wm != null) {
            try { wm?.removeView(v) } catch (_: Exception) {}
        }
    }

    private fun clear() {
        remove(panel)
        remove(handle)
        panel = null
        handle = null
    }

    companion object {
        private const val CHANNEL_ID = "bottom_panel"
        private const val NOTIFICATION_ID = 42
        private const val WATCHDOG_INTERVAL_MS = 1500L

        const val ACTION_WAKE = "com.minimal.carlauncher.panel.WAKE"
        const val ACTION_PING = "com.minimal.carlauncher.panel.PING"

        private const val PANEL_BG = 0xFF10141A.toInt()
        private const val COLOR_APPS = 0xFF2870E6.toInt()  // blue
        private const val COLOR_NAV = 0xFF00969F.toInt()    // teal
        private const val COLOR_PHONE = 0xFF32B446.toInt()  // green
        private const val COLOR_HIDE = 0xFF5A626E.toInt()   // grey

        private const val W = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val M = ViewGroup.LayoutParams.MATCH_PARENT

        @Volatile
        var isRunning = false
            private set

        /** Simple one-line logger persisted in SharedPreferences for on-device debugging. */
        fun log(context: Context, msg: String) {
            val prefs = context.getSharedPreferences("panel_log", Context.MODE_PRIVATE)
            val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + msg
            val old = prefs.getString("log", "")?.split("\n") ?: emptyList()
            val combined = (listOf(line) + old.filter { it.isNotEmpty() }.take(19))
                .joinToString("\n")
            prefs.edit().putString("log", combined).apply()
        }

        fun log(msg: String) {
            // In-process only — used from within the service where context is `this`.
            android.util.Log.d("PanelService", msg)
        }
    }

    private fun log(msg: String) {
        Companion.log(this, msg)
    }
}
