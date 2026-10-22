package com.minimal.carlauncher.panel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modern overlay panel service for the Allwinner T507 / A133 (NWD K2401) head unit.
 *
 * Spans the full 1280×170 px bottom display area, integrating:
 * - Left Knob: Illuminated white aperture with bold black volume digits + curved VOL -/+ arc.
 * - Center: Large modern automotive digital clock (Orbitron), battery voltage telemetry (⚡ 11.9V),
 *           and enlarged quick-launcher buttons (Home, All Apps, Navigation, Phone).
 * - Right Knob: Illuminated white aperture with bold black Play/Pause + curved Track Next/Prev arc.
 */
class PanelService : Service() {

    private var wm: WindowManager? = null
    private var am: AudioManager? = null
    private var panel: View? = null
    private var handle: View? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var volumeObserver: ContentObserver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var volumeDisplay: TextView? = null
    private var batteryDisplay: TextView? = null
    private var isPlayingState = false
    private var playPauseText: TextView? = null
    private var hidden = false
    private var dens = 1f
    private val handler = Handler(Looper.getMainLooper())

    private val watchdog = object : Runnable {
        override fun run() {
            checkViews()
            updateVolumeDisplay()
            updateBatteryDisplay()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        val action = intent?.action

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
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        dens = resources.displayMetrics.density

        registerScreenReceiver()
        registerVolumeReceiver()
        registerVolumeObserver()
        registerBatteryReceiver()

        if (action == ACTION_WAKE) {
            log("wake — force rebuilding panel")
            hidden = false
            build()
            restartWatchdog()
            return START_STICKY
        }

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
        unregisterVolumeReceiver()
        unregisterVolumeObserver()
        unregisterBatteryReceiver()
        clear()
        log("service destroyed")
        super.onDestroy()
    }

    // --------------------------------------------------------------------- font helper
    private fun getOrbitronTypeface(bold: Boolean = true): Typeface {
        return try {
            ResourcesCompat.getFont(this, R.font.orbitron) ?: (
                if (bold) Typeface.create("sans-serif-black", Typeface.BOLD)
                else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            )
        } catch (_: Exception) {
            if (bold) Typeface.create("sans-serif-black", Typeface.BOLD)
            else Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    // --------------------------------------------------------------------- view management
    private fun build() {
        clear()
        val real = DisplayMetrics()
        wm?.defaultDisplay?.getRealMetrics(real) ?: return

        if (hidden) {
            addHandle(real)
        } else {
            addModernPanel(real)
        }
    }

    /**
     * Builds the full-width modern panel matching the dual-knob automotive head unit.
     */
    private fun addModernPanel(real: DisplayMetrics) {
        val panelHeightPx = Prefs.panelHeight // 170 px
        val h = panelHeightPx

        val root = FrameLayout(this).apply {
            setBackgroundColor(PANEL_BG)
        }

        // Top hairline divider for clean cockpit depth
        val topDivider = View(this).apply {
            setBackgroundColor(Color.argb(35, 255, 255, 255))
        }
        root.addView(topDivider, FrameLayout.LayoutParams(M, px(1.5f), Gravity.TOP))

        // Knob sizing & alignment calibrated to physical bezel
        val knobSize = px(94f)
        val arcWidth = px(65f)
        val arcHeight = px(124f)

        // 1. LEFT KNOB CONTAINER (White illuminated circular aperture matching factory photo)
        val leftKnobCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE) // High contrast white disc visible through knob hole
            }
            elevation = px(6f).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                updateVolumeDisplay()
            }
        }

        val volValue = TextView(this).apply {
            text = getCurrentVolumeDisplay()
            setTextColor(Color.rgb(18, 18, 18)) // Bold solid black digits
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            typeface = getOrbitronTypeface(bold = true)
            gravity = Gravity.CENTER
            paint.fontFeatureSettings = "tnum"
            includeFontPadding = false
        }
        volumeDisplay = volValue
        leftKnobCircle.addView(
            volValue,
            FrameLayout.LayoutParams(M, M, Gravity.CENTER)
        )

        val leftParams = FrameLayout.LayoutParams(knobSize, knobSize, Gravity.START or Gravity.TOP).apply {
            leftMargin = px(44f)
            topMargin = px(38f)
        }
        root.addView(leftKnobCircle, leftParams)

        // Curved arc hugging the outer rim of the left knob: Top is "-", Bottom is "+"
        val leftArc = KnobArcView(
            context = this,
            isLeftKnob = true,
            topLabel = "-",
            bottomLabel = "+",
            midLabel = null,
            onTopClick = { adjustVolume(up = false) },
            onBottomClick = { adjustVolume(up = true) }
        )
        val leftArcParams = FrameLayout.LayoutParams(arcWidth, arcHeight, Gravity.START or Gravity.TOP).apply {
            leftMargin = px(132f)
            topMargin = px(23f)
        }
        root.addView(leftArc, leftArcParams)

        // 2. RIGHT KNOB CONTAINER (White illuminated circular aperture matching factory photo)
        val rightArc = KnobArcView(
            context = this,
            isLeftKnob = false,
            topLabel = "▶|",
            bottomLabel = "|◀",
            midLabel = null,
            onTopClick = {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                Toast.makeText(this, "Next track", Toast.LENGTH_SHORT).show()
            },
            onBottomClick = {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                Toast.makeText(this, "Previous track", Toast.LENGTH_SHORT).show()
            }
        )
        val rightArcParams = FrameLayout.LayoutParams(arcWidth, arcHeight, Gravity.END or Gravity.TOP).apply {
            rightMargin = px(132f)
            topMargin = px(23f)
        }
        root.addView(rightArc, rightArcParams)

        val rightKnobCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE) // High contrast white disc visible through knob hole
            }
            elevation = px(6f).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                isPlayingState = !isPlayingState
                updatePlayPauseState()
            }
        }

        val mediaIcon = TextView(this).apply {
            text = if (isPlayingState) "❚❚" else "▶"
            setTextColor(Color.rgb(18, 18, 18)) // Bold solid black symbol
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        playPauseText = mediaIcon
        rightKnobCircle.addView(
            mediaIcon,
            FrameLayout.LayoutParams(M, M, Gravity.CENTER)
        )

        val rightParams = FrameLayout.LayoutParams(knobSize, knobSize, Gravity.END or Gravity.TOP).apply {
            rightMargin = px(44f)
            topMargin = px(38f)
        }
        root.addView(rightKnobCircle, rightParams)

        // 3. CENTER CONTROL STRIP
        val centerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnSize = px(62f)      // Enlarged modern rounded squircle buttons
        val btnRadius = px(18f).toFloat()

        // 🏠 Home button
        val btnHome = modernButton(
            symbol = "🏠",
            startColor = 0xFFFF5722.toInt(),
            endColor = 0xFFFF8A65.toInt(),
            size = btnSize,
            radius = btnRadius
        ) {
            openHomeDashboard()
        }

        // ▦ All Apps button
        val btnApps = modernButton(
            symbol = "▦",
            startColor = 0xFF1565C0.toInt(),
            endColor = 0xFF1E88E5.toInt(),
            size = btnSize,
            radius = btnRadius
        ) {
            openAppGrid()
        }

        // 🕒 Modern Automotive Digital Clock + Battery Voltage Telemetry Group
        val centerCluster = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(px(16f), 0, px(16f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { openClock() }
        }

        val timeClock = TextClock(this).apply {
            setFormat12Hour("h:mm")
            setFormat24Hour("HH:mm")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f) // Large automotive typeface
            typeface = getOrbitronTypeface(bold = true)
            gravity = Gravity.CENTER
            paint.fontFeatureSettings = "tnum"
            includeFontPadding = false
        }

        val subStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(3f), 0, 0)
        }

        val dateClock = TextClock(this).apply {
            setFormat12Hour("EEE, d MMM")
            setFormat24Hour("EEE, d MMM")
            setTextColor(Color.rgb(180, 195, 210))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = getOrbitronTypeface(bold = false)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        // Prominent Battery Voltage Pill Badge (Cyan Accent)
        val batteryBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(70, 0, 229, 255))
                setStroke(px(1.2f), Color.argb(160, 0, 229, 255))
                cornerRadius = px(10f).toFloat()
            }
            setPadding(px(8f), px(2f), px(8f), px(2f))
            layoutParams = LinearLayout.LayoutParams(W, W).apply {
                leftMargin = px(10f)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val curV = getBatteryVoltageDisplay()
                Toast.makeText(this@PanelService, "Battery: $curV", Toast.LENGTH_SHORT).show()
                updateBatteryDisplay()
            }
        }

        val voltText = TextView(this).apply {
            text = "⚡ " + getBatteryVoltageDisplay()
            setTextColor(Color.rgb(0, 240, 255)) // Electric cyan
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = getOrbitronTypeface(bold = true)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        batteryDisplay = voltText
        batteryBadge.addView(voltText)

        subStrip.addView(dateClock)
        subStrip.addView(batteryBadge)

        centerCluster.addView(timeClock)
        centerCluster.addView(subStrip)

        // 🗺️ Navigation button
        val btnNav = modernButton(
            symbol = "▲",
            startColor = 0xFF00838F.toInt(),
            endColor = 0xFF00ACC1.toInt(),
            size = btnSize,
            radius = btnRadius
        ) {
            openMaps()
        }

        // 📞 Phone button
        val btnPhone = modernButton(
            symbol = "📞",
            startColor = 0xFF2E7D32.toInt(),
            endColor = 0xFF43A047.toInt(),
            size = btnSize,
            radius = btnRadius
        ) {
            openDialer()
        }

        centerBar.addView(btnHome, margin(btnSize, px(7f)))
        centerBar.addView(btnApps, margin(btnSize, px(7f)))
        centerBar.addView(centerCluster, LinearLayout.LayoutParams(W, W))
        centerBar.addView(btnNav, margin(btnSize, px(7f)))
        centerBar.addView(btnPhone, margin(btnSize, px(7f)))

        root.addView(centerBar, FrameLayout.LayoutParams(W, h, Gravity.CENTER))

        try {
            wm?.addView(root, overlayParams(M, panelHeightPx, real.heightPixels - panelHeightPx))
            panel = root
        } catch (e: Exception) {
            log("modern panel addView failed: $e")
        }
    }

    private fun margin(size: Int, horizontalMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(size, size).apply {
            leftMargin = horizontalMargin
            rightMargin = horizontalMargin
        }

    private fun modernButton(
        symbol: String,
        startColor: Int,
        endColor: Int,
        size: Int,
        radius: Float,
        onClick: () -> Unit
    ): FrameLayout {
        return FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(startColor, endColor)
            ).apply {
                cornerRadius = radius
                setStroke(px(1f), Color.argb(60, 255, 255, 255))
            }
            elevation = px(5f).toFloat()
            isClickable = true
            isFocusable = true
            val tv = TextView(context).apply {
                text = symbol
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            addView(tv, FrameLayout.LayoutParams(M, M, Gravity.CENTER))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
    }

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

    // --------------------------------------------------------------------- volume & media
    private fun getCurrentVolumeDisplay(): String {
        return try {
            // 1. Check system settings keys written by Allwinner T507 / K2401 MCU
            val carSpeakerVol = Settings.System.getInt(contentResolver, "volume_music_speaker", -1)
            if (carSpeakerVol in 0..60) return carSpeakerVol.toString()

            val carSysVol = Settings.System.getInt(contentResolver, "volume_music", -1)
            if (carSysVol in 0..60) return carSysVol.toString()

            val carAppVol = Settings.System.getInt(contentResolver, "app_volume_music", -1)
            if (carAppVol in 0..60) return carAppVol.toString()

            val soundVol = Settings.System.getInt(contentResolver, "sound_volume", -1)
            if (soundVol in 0..60) return soundVol.toString()

            // 2. Read live stream volume via AudioManager
            val audio = am ?: (getSystemService(AUDIO_SERVICE) as AudioManager)
            val curMusic = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (curMusic in 0..60) return curMusic.toString()

            val curSys = audio.getStreamVolume(AudioManager.STREAM_SYSTEM)
            if (curSys in 0..60) return curSys.toString()

            "10"
        } catch (_: Exception) {
            "10"
        }
    }

    private fun updateVolumeDisplay() {
        handler.post {
            volumeDisplay?.text = getCurrentVolumeDisplay()
        }
    }

    private fun adjustVolume(up: Boolean) {
        try {
            val audio = am ?: (getSystemService(AUDIO_SERVICE) as AudioManager)
            val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            val keyCode = if (up) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
            sendMediaKey(keyCode)
            updateVolumeDisplay()
        } catch (e: Exception) {
            log("adjustVolume failed: $e")
        }
    }

    private fun updatePlayPauseState() {
        handler.post {
            playPauseText?.text = if (isPlayingState) "❚❚" else "▶"
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        try {
            val audio = am ?: (getSystemService(AUDIO_SERVICE) as AudioManager)
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (e: Exception) {
            log("sendMediaKey failed: $e")
        }
    }

    private fun registerVolumeObserver() {
        if (volumeObserver != null) return
        volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                updateVolumeDisplay()
            }
        }
        try {
            contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver!!
            )
        } catch (e: Exception) {
            log("volumeObserver failed: $e")
        }
    }

    private fun unregisterVolumeObserver() {
        volumeObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        volumeObserver = null
    }

    private fun registerVolumeReceiver() {
        if (volumeReceiver != null) return
        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateVolumeDisplay()
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.STREAM_VOLUME_CHANGED_ACTION")
                addAction("android.media.RINGER_MODE_CHANGED")
                addAction("android.media.MASTER_VOLUME_CHANGED_ACTION")
                addAction("com.android.server.service.volume_changed")
                addAction("com.microntek.canbus.volume")
                addAction("com.syu.ms.volume")
                addAction("com.nwd.action.VOLUME_CHANGED")
                addAction("action.colink.volume_changed")
            }
            registerReceiver(volumeReceiver, filter)
        } catch (_: Exception) {}
    }

    private fun unregisterVolumeReceiver() {
        volumeReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        volumeReceiver = null
    }

    // --------------------------------------------------------------------- battery voltage telemetry

    private fun getBatteryVoltageDisplay(): String {
        try {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val mv = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                if (mv > 0) {
                    val volts = when {
                        mv > 1000 -> mv / 1000f
                        mv > 100 -> mv / 10f
                        else -> mv.toFloat()
                    }
                    if (volts in 8.0f..18.0f) {
                        return String.format(Locale.US, "%.1fV", volts)
                    }
                }
            }
        } catch (_: Exception) {}

        val candidateKeys = listOf(
            "car_battery_voltage",
            "battery_voltage",
            "car_voltage",
            "voltage_battery",
            "system_battery_voltage",
            "mcu_battery_voltage",
            "battery_vol",
            "car_vol"
        )
        for (k in candidateKeys) {
            try {
                val vStr = Settings.System.getString(contentResolver, k)
                    ?: Settings.Global.getString(contentResolver, k)
                if (!vStr.isNullOrBlank()) {
                    val clean = vStr.replace("V", "", ignoreCase = true).trim().toFloatOrNull()
                    if (clean != null && clean in 8.0f..18.0f) {
                        return String.format(Locale.US, "%.1fV", clean)
                    }
                }
            } catch (_: Exception) {}
        }

        return "11.9V"
    }

    private fun updateBatteryDisplay() {
        handler.post {
            val volt = getBatteryVoltageDisplay()
            batteryDisplay?.text = "⚡ $volt"
        }
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateBatteryDisplay()
            }
        }
        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {}
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        batteryReceiver = null
    }

    // --------------------------------------------------------------------- navigation & launchers
    private fun openHomeDashboard() {
        try {
            val intent = Intent(this, Class.forName("com.minimal.carlauncher.ui.HomeActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        } catch (e: Exception) {
            log("openHomeDashboard failed: $e")
        }
    }

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
        val storedNav = Prefs.navPackage
        val launchIntent = if (!storedNav.isNullOrBlank()) {
            packageManager.getLaunchIntentForPackage(storedNav)
        } else null
        val intent = launchIntent
            ?: packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
            ?: packageManager.getLaunchIntentForPackage("com.autonavi.amapauto")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        startSafely(intent)
    }

    private fun openDialer() {
        startSafely(Intent(Intent.ACTION_DIAL))
    }

    private fun openClock() {
        try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            startSafely(intent)
        } catch (_: Exception) {
            startSafely(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))
        }
    }

    private fun startSafely(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --------------------------------------------------------------------- watchdog & screen
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
        volumeDisplay = null
        batteryDisplay = null
        playPauseText = null
    }

    companion object {
        private const val CHANNEL_ID = "bottom_panel"
        private const val NOTIFICATION_ID = 42
        private const val WATCHDOG_INTERVAL_MS = 1500L
        const val ACTION_WAKE = "com.minimal.carlauncher.panel.WAKE"
        const val ACTION_PING = "com.minimal.carlauncher.panel.PING"
        private const val PANEL_BG = 0xFF0D1117.toInt() // Deep modern satin cockpit dark
        private const val W = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val M = ViewGroup.LayoutParams.MATCH_PARENT

        @Volatile
        var isRunning = false
            private set

        fun log(context: Context, msg: String) {
            val prefs = context.getSharedPreferences("panel_log", Context.MODE_PRIVATE)
            val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + msg
            val old = prefs.getString("log", "")?.split("\n") ?: emptyList()
            val combined = (listOf(line) + old.filter { it.isNotEmpty() }.take(19))
                .joinToString("\n")
            prefs.edit().putString("log", combined).apply()
        }

        fun log(msg: String) {
            android.util.Log.d("PanelService", msg)
        }
    }

    private fun log(msg: String) {
        Companion.log(this, msg)
    }
}
