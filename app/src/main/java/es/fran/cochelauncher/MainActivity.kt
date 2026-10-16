package es.fran.cochelauncher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var dark = true
    private val p get() = if (dark) Palette.DARK else Palette.LIGHT

    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private var speedView: TextView? = null
    private var speedHint: TextView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var playBtn: ImageView? = null
    private var volBar: ProgressBar? = null
    private var volText: TextView? = null

    private var controller: MediaController? = null
    private var hasMediaAccess = false

    private var lastFix: Location? = null
    private var lastFixAt = 0L
    private var ticks = 0

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 2_000)
        }
    }

    // ---------- ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        root = FrameLayout(this)
        setContentView(root)
        Sound.init(this)
        dark = Theme.isDark(this)
        build()
        askLocationOnce()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        Apps.all(this, refresh = true)
        dark = Theme.isDark(this)
        build()
        connectMedia()
        startSpeed()
        try {
            val f = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(volReceiver, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(volReceiver, f)
        } catch (e: Exception) {
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        disconnectMedia()
        stopSpeed()
        try {
            unregisterReceiver(volReceiver)
        } catch (e: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Es la pantalla de inicio: "atrás" no hace nada.
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startSpeed()
        tick()
    }

    // ---------- interfaz ----------

    private fun build() {
        val c = p
        root.removeAllViews()
        root.setBackgroundColor(c.bg)
        window.statusBarColor = c.bg
        window.navigationBarColor = c.bg

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(16))
        }
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        // Cabecera: fecha y hora, discretas.
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        main.addView(header, LinearLayout.LayoutParams(-1, dp(40)))
        dateView = TextView(this).apply {
            setTextColor(c.sub)
            textSize = 17f
        }
        header.addView(dateView, LinearLayout.LayoutParams(0, -2, 1f))
        clockView = TextView(this).apply {
            setTextColor(c.text)
            textSize = 28f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        header.addView(clockView)

        // Centro: velocidad a la izquierda, música a la derecha.
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        main.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        val speedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        mid.addView(speedBox, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(16) })
        speedView = TextView(this).apply {
            text = "--"
            setTextColor(c.text)
            textSize = 150f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        speedBox.addView(speedView)
        speedBox.addView(TextView(this).apply {
            text = "km/h"
            setTextColor(c.sub)
            textSize = 22f
        })
        speedHint = TextView(this).apply {
            setTextColor(c.sub)
            textSize = 13f
        }
        speedBox.addView(speedHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        mid.addView(buildMediaCard(c), LinearLayout.LayoutParams(0, -1, 1.2f))

        // Barra inferior.
        main.addView(buildDock(c), LinearLayout.LayoutParams(-1, dp(96)).apply { topMargin = dp(16) })

        tick()
        updateMedia()
        updateVolume()
    }

    private fun buildMediaCard(c: Palette): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(c)
            setPadding(dp(22), dp(18), dp(22), dp(16))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener {
                if (!hasMediaAccess) openMediaAccessSettings()
                else Apps.launch(this@MainActivity, Apps.resolve(this@MainActivity, Slot.BTMUSIC))
            }
        }
        card.addView(info, LinearLayout.LayoutParams(-1, -2))
        info.addView(TextView(this).apply {
            text = "Reproduciendo"
            setTextColor(c.accent)
            textSize = 13f
        })
        titleView = TextView(this).apply {
            setTextColor(c.text)
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        info.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        artistView = TextView(this).apply {
            setTextColor(c.sub)
            textSize = 17f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        info.addView(artistView)

        card.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        card.addView(controls, LinearLayout.LayoutParams(-1, -2))
        controls.addView(roundButton(c, R.drawable.ic_prev, c.bg, c.text) { mediaCmd(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, LinearLayout.LayoutParams(dp(62), dp(62)))
        playBtn = roundButton(c, R.drawable.ic_play, c.accent, Color.WHITE) { mediaCmd(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        controls.addView(playBtn, LinearLayout.LayoutParams(dp(76), dp(76)).apply { leftMargin = dp(28); rightMargin = dp(28) })
        controls.addView(roundButton(c, R.drawable.ic_next, c.bg, c.text) { mediaCmd(KeyEvent.KEYCODE_MEDIA_NEXT) }, LinearLayout.LayoutParams(dp(62), dp(62)))

        card.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Volumen.
        val vol = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        card.addView(vol, LinearLayout.LayoutParams(-1, -2))
        vol.addView(roundButton(c, R.drawable.ic_vol_down, c.bg, c.text) { changeVolume(AudioManager.ADJUST_LOWER) }, LinearLayout.LayoutParams(dp(54), dp(54)))
        volBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(c.accent)
            progressBackgroundTintList = ColorStateList.valueOf(c.sub)
        }
        vol.addView(volBar, LinearLayout.LayoutParams(0, dp(10), 1f).apply { leftMargin = dp(14); rightMargin = dp(14) })
        vol.addView(roundButton(c, R.drawable.ic_vol_up, c.bg, c.text) { changeVolume(AudioManager.ADJUST_RAISE) }, LinearLayout.LayoutParams(dp(54), dp(54)))
        volText = TextView(this).apply {
            setTextColor(c.sub)
            textSize = 15f
            gravity = Gravity.END
        }
        vol.addView(volText, LinearLayout.LayoutParams(dp(40), -2))
        return card
    }

    private fun roundButton(c: Palette, icon: Int, bg: Int, fg: Int, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(fg, PorterDuff.Mode.SRC_IN)
            background = circleBg(c, bg)
            setPadding(dp(15), dp(15), dp(15), dp(15))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun buildDock(c: Palette): View {
        val dock = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg(c, 26)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val main = listOf(
            Slot.PHONE to R.drawable.ic_phone,
            Slot.RADIO to R.drawable.ic_radio,
            Slot.BTMUSIC to R.drawable.ic_music,
            Slot.AUTO to R.drawable.ic_car,
        )
        for ((slot, icon) in main) {
            val app = Apps.resolve(this, slot)
            dock.addView(dockItem(c, icon, slot.title, c.accent, c.text,
                onClick = { Apps.launch(this, app) },
                onLong = { pickApp(slot) }), LinearLayout.LayoutParams(0, -1, 1.15f))
        }
        // Separador.
        dock.addView(View(this).apply { setBackgroundColor(c.press) }, LinearLayout.LayoutParams(dp(1), dp(48)).apply { leftMargin = dp(6); rightMargin = dp(6) })

        val modeName = when (Theme.mode(this)) {
            "dark" -> "Oscuro"
            "light" -> "Claro"
            else -> "Auto"
        }
        dock.addView(dockItem(c, R.drawable.ic_eq, "Sonido", c.text, c.sub, onClick = {
            startActivity(Intent(this, SoundActivity::class.java))
        }), LinearLayout.LayoutParams(0, -1, 1f))
        dock.addView(dockItem(c, R.drawable.ic_theme, modeName, c.text, c.sub, onClick = { cycleTheme() }), LinearLayout.LayoutParams(0, -1, 1f))
        dock.addView(dockItem(c, R.drawable.ic_power, "Pantalla", c.text, c.sub, onClick = {
            startActivity(Intent(this, BlackoutActivity::class.java))
        }), LinearLayout.LayoutParams(0, -1, 1f))
        dock.addView(dockItem(c, R.drawable.ic_apps, "Apps", c.text, c.sub, onClick = {
            startActivity(Intent(this, AppsActivity::class.java))
        }), LinearLayout.LayoutParams(0, -1, 1f))
        return dock
    }

    private fun dockItem(c: Palette, icon: Int, label: String, iconColor: Int, textColor: Int,
                         onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            background = cardBg(c, 18, c.card)
            isClickable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        box.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        box.addView(TextView(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 14f
            maxLines = 1
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        return box
    }

    private fun pickApp(slot: Slot) {
        val apps = Apps.all(this, refresh = true)
        val labels = arrayOf("Detección automática") + apps.map { it.label }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("App para " + slot.title)
            .setItems(labels) { _, which ->
                Apps.assign(this, slot, if (which == 0) null else apps[which - 1])
                build()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- reloj, velocidad y modo día/noche ----------

    private fun tick() {
        val now = Date()
        clockView?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val d = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "ES")).format(now)
        dateView?.text = d.replaceFirstChar { it.uppercase() }

        // Si el GPS lleva unos segundos sin dar posición, no mostramos una velocidad vieja.
        if (SystemClock.elapsedRealtime() - lastFixAt > 5_000) {
            speedView?.text = "--"
            speedHint?.text = if (hasLocationPermission()) "Buscando GPS" else "Sin permiso de ubicación"
        }

        ticks++
        if (ticks % 15 == 0) {
            val newDark = Theme.isDark(this)
            if (newDark != dark) {
                dark = newDark
                build()
            }
        }
    }

    private fun cycleTheme() {
        val next = when (Theme.mode(this)) {
            "auto" -> "dark"
            "dark" -> "light"
            else -> "auto"
        }
        Theme.setMode(this, next)
        val msg = when (next) {
            "dark" -> "Modo oscuro fijo"
            "light" -> "Modo claro fijo"
            else -> "Automático: oscuro de la puesta al amanecer"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        dark = Theme.isDark(this)
        build()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askLocationOnce() {
        if (prefs.getBoolean("asked_loc", false)) return
        prefs.edit().putBoolean("asked_loc", true).apply()
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    private val locListener = LocationListener { loc -> onFix(loc) }

    private fun startSpeed() {
        if (!hasLocationPermission()) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(locListener)
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
        } catch (e: Exception) {
        }
    }

    private fun stopSpeed() {
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locListener)
        } catch (e: Exception) {
        }
    }

    private fun onFix(loc: Location) {
        val prev = lastFix
        var mps = if (loc.hasSpeed()) loc.speed.toDouble() else -1.0
        if (mps < 0 && prev != null) {
            val dt = (loc.time - prev.time) / 1000.0
            if (dt > 0) mps = prev.distanceTo(loc) / dt
        }
        lastFix = loc
        lastFixAt = SystemClock.elapsedRealtime()
        if (mps < 0) return
        var kmh = (mps * 3.6).roundToInt()
        if (kmh < 3) kmh = 0 // evita que baile parado
        speedView?.text = kmh.toString()
        speedHint?.text = ""
    }

    // ---------- volumen ----------

    private val volReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateVolume()
    }

    private fun changeVolume(direction: Int) {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
        }
        updateVolume()
    }

    private fun updateVolume() {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            volBar?.max = max
            volBar?.progress = cur
            volText?.text = cur.toString()
        } catch (e: Exception) {
        }
    }

    // ---------- música ----------

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        val playing = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        setController(playing ?: list?.firstOrNull())
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMedia()
        override fun onSessionDestroyed() = setController(null)
    }

    private fun connectMedia() {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        val cn = ComponentName(this, MediaListener::class.java)
        try {
            val list = msm.getActiveSessions(cn)
            hasMediaAccess = true
            val playing = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            setController(playing ?: list.firstOrNull())
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn, handler)
        } catch (e: SecurityException) {
            hasMediaAccess = false
            updateMedia()
        }
    }

    private fun disconnectMedia() {
        try {
            getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
        }
        setController(null)
    }

    private fun setController(c: MediaController?) {
        controller?.unregisterCallback(mediaCallback)
        controller = c
        c?.registerCallback(mediaCallback, handler)
        updateMedia()
    }

    private fun updateMedia() {
        val md = controller?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        when {
            !title.isNullOrBlank() -> {
                titleView?.text = title
                artistView?.text = artist ?: ""
            }
            !hasMediaAccess -> {
                titleView?.text = "Música"
                artistView?.text = "Toca aquí para ver la canción en curso"
            }
            else -> {
                titleView?.text = "Sin reproducción"
                artistView?.text = ""
            }
        }
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        playBtn?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun mediaCmd(keyCode: Int) {
        val c = controller
        if (c != null) {
            val t = c.transportControls
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> t.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> t.skipToPrevious()
                else -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            }
            return
        }
        // Sin acceso a la sesión: enviamos la tecla multimedia, igual que los mandos del volante.
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun openMediaAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "Activa \"Inicio coche\" en la lista", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Esta radio no permite leer la canción; los botones siguen funcionando", Toast.LENGTH_LONG).show()
        }
    }
}
