package es.fran.cochelauncher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var dark = true
    private val p get() = if (dark) Palette.DARK else Palette.LIGHT

    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var playBtn: ImageView? = null

    private var controller: MediaController? = null
    private var hasMediaAccess = false

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 20_000)
        }
    }

    // ---------- ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE)
        root = FrameLayout(this)
        setContentView(root)
        dark = computeDark()
        build()
        askLocationOnce()
    }

    override fun onResume() {
        super.onResume()
        Apps.all(this, refresh = true)
        val d = computeDark()
        if (d != dark) dark = d
        build()
        connectMedia()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        disconnectMedia()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Es la pantalla de inicio: "atrás" no hace nada.
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
            setPadding(dp(20), dp(14), dp(20), dp(16))
        }
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        // Zona superior: reloj y reproductor a la izquierda, accesos grandes a la derecha.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        main.addView(top, LinearLayout.LayoutParams(-1, 0, 1f))

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        top.addView(left, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(16) })

        clockView = TextView(this).apply {
            setTextColor(c.text)
            textSize = 76f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
        }
        left.addView(clockView)
        dateView = TextView(this).apply {
            setTextColor(c.sub)
            textSize = 18f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        left.addView(dateView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        left.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        left.addView(buildMediaCard(c), LinearLayout.LayoutParams(-1, -2))

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        top.addView(grid, LinearLayout.LayoutParams(0, -1, 1.25f))
        val row1 = LinearLayout(this)
        val row2 = LinearLayout(this)
        grid.addView(row1, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(8) })
        grid.addView(row2, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        row1.addView(bigTile(c, Slot.PHONE, R.drawable.ic_phone), tileLp(true))
        row1.addView(bigTile(c, Slot.RADIO, R.drawable.ic_radio), tileLp(false))
        row2.addView(bigTile(c, Slot.BTMUSIC, R.drawable.ic_music), tileLp(true))
        row2.addView(bigTile(c, Slot.AUTO, R.drawable.ic_car), tileLp(false))

        // Barra inferior: accesos secundarios y botones de sistema.
        main.addView(buildDock(c), LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(16) })

        tick()
        updateMedia()
    }

    private fun tileLp(first: Boolean) = LinearLayout.LayoutParams(0, -1, 1f).apply {
        if (first) rightMargin = dp(8) else leftMargin = dp(8)
    }

    private fun bigTile(c: Palette, slot: Slot, icon: Int): View {
        val app = Apps.resolve(this, slot)
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = cardBg(c)
            isClickable = true
            setOnClickListener { Apps.launch(this@MainActivity, app) }
            setOnLongClickListener { pickApp(slot); true }
        }
        val iv = ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(c.accent, PorterDuff.Mode.SRC_IN)
        }
        tile.addView(iv, LinearLayout.LayoutParams(dp(54), dp(54)))
        tile.addView(TextView(this).apply {
            text = slot.title
            setTextColor(c.text)
            textSize = 21f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        if (app == null) {
            tile.addView(TextView(this).apply {
                text = "Mantén pulsado para elegir"
                setTextColor(c.sub)
                textSize = 12f
            })
        }
        return tile
    }

    private fun buildMediaCard(c: Palette): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(c)
            setPadding(dp(18), dp(14), dp(18), dp(12))
            isClickable = true
            setOnClickListener {
                if (!hasMediaAccess) openMediaAccessSettings()
                else Apps.launch(this@MainActivity, Apps.resolve(this@MainActivity, Slot.BTMUSIC))
            }
        }
        card.addView(TextView(this).apply {
            text = "REPRODUCIENDO"
            setTextColor(c.accent)
            textSize = 11f
            letterSpacing = 0.12f
        })
        titleView = TextView(this).apply {
            setTextColor(c.text)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        artistView = TextView(this).apply {
            setTextColor(c.sub)
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(artistView)

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        card.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        controls.addView(roundButton(c, R.drawable.ic_prev, c.card, c.text) { mediaCmd(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        playBtn = roundButton(c, R.drawable.ic_play, c.accent, android.graphics.Color.WHITE) { mediaCmd(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        controls.addView(playBtn, LinearLayout.LayoutParams(dp(60), dp(60)).apply { leftMargin = dp(22); rightMargin = dp(22) })
        controls.addView(roundButton(c, R.drawable.ic_next, c.card, c.text) { mediaCmd(KeyEvent.KEYCODE_MEDIA_NEXT) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        return card
    }

    private fun roundButton(c: Palette, icon: Int, bg: Int, fg: Int, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(fg, PorterDuff.Mode.SRC_IN)
            background = circleBg(c, bg)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun buildDock(c: Palette): View {
        val dock = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg(c, 24)
            setPadding(dp(10), 0, dp(10), 0)
        }
        for (slot in listOf(Slot.DOCK1, Slot.DOCK2, Slot.DOCK3, Slot.DOCK4)) {
            dock.addView(dockApp(c, slot), LinearLayout.LayoutParams(dp(118), -1))
        }
        dock.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val modeName = when (prefs.getString("theme", "auto")) {
            "dark" -> "Oscuro"
            "light" -> "Claro"
            else -> "Auto"
        }
        dock.addView(dockAction(c, R.drawable.ic_theme, modeName) { cycleTheme() }, LinearLayout.LayoutParams(dp(96), -1))
        dock.addView(dockAction(c, R.drawable.ic_power, "Pantalla") {
            startActivity(Intent(this, BlackoutActivity::class.java))
        }, LinearLayout.LayoutParams(dp(96), -1))
        dock.addView(dockAction(c, R.drawable.ic_apps, "Apps") {
            startActivity(Intent(this, AppsActivity::class.java).putExtra("dark", dark))
        }, LinearLayout.LayoutParams(dp(96), -1))
        return dock
    }

    private fun dockApp(c: Palette, slot: Slot): View {
        val app = Apps.resolve(this, slot)
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            background = cardBg(c, 16, c.card)
            isClickable = true
            setOnClickListener { Apps.launch(this@MainActivity, app) }
            setOnLongClickListener { pickApp(slot); true }
        }
        val iv = ImageView(this)
        val icon = app?.icon(this)?.mutate()
        if (icon != null) {
            // Iconos de fábrica en escala de grises para que no rompan la estética.
            icon.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            iv.setImageDrawable(icon)
        } else {
            iv.setImageResource(R.drawable.ic_apps)
            iv.setColorFilter(c.sub, PorterDuff.Mode.SRC_IN)
        }
        box.addView(iv, LinearLayout.LayoutParams(dp(32), dp(32)))
        box.addView(TextView(this).apply {
            text = app?.label ?: slot.title
            setTextColor(c.sub)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        return box
    }

    private fun dockAction(c: Palette, icon: Int, label: String, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            background = cardBg(c, 16, c.card)
            isClickable = true
            setOnClickListener { onClick() }
        }
        box.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(c.text, PorterDuff.Mode.SRC_IN)
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        box.addView(TextView(this).apply {
            text = label
            setTextColor(c.sub)
            textSize = 12f
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
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

    // ---------- reloj y modo día/noche ----------

    private fun tick() {
        val now = Date()
        clockView?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val d = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "ES")).format(now)
        dateView?.text = d.replaceFirstChar { it.uppercase() }
        val newDark = computeDark()
        if (newDark != dark) {
            dark = newDark
            build()
        }
    }

    private fun computeDark(): Boolean = when (prefs.getString("theme", "auto")) {
        "dark" -> true
        "light" -> false
        else -> !isDayNow()
    }

    private fun cycleTheme() {
        val next = when (prefs.getString("theme", "auto")) {
            "auto" -> "dark"
            "dark" -> "light"
            else -> "auto"
        }
        prefs.edit().putString("theme", next).apply()
        val msg = when (next) {
            "dark" -> "Modo oscuro fijo"
            "light" -> "Modo claro fijo"
            else -> "Automático: oscuro de la puesta al amanecer"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        dark = computeDark()
        build()
    }

    private fun isDayNow(): Boolean {
        val (lat, lon) = position()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val utcNow = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        return Sun.isDay(cal.get(Calendar.DAY_OF_YEAR), utcNow, lat, lon)
    }

    /** Posición del GPS de la radio. Si no hay, la última conocida, y si no, Ferrol. */
    private fun position(): Pair<Double, Double> {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val best = lm.getProviders(true)
                    .mapNotNull { prov -> try { lm.getLastKnownLocation(prov) } catch (e: SecurityException) { null } }
                    .maxByOrNull { it.time }
                if (best != null) {
                    prefs.edit()
                        .putFloat("lat", best.latitude.toFloat())
                        .putFloat("lon", best.longitude.toFloat())
                        .apply()
                    return best.latitude to best.longitude
                }
            } catch (e: Exception) {
                // seguimos con la posición guardada
            }
        }
        return prefs.getFloat("lat", 43.4832f).toDouble() to prefs.getFloat("lon", -8.2369f).toDouble()
    }

    private fun askLocationOnce() {
        if (prefs.getBoolean("asked_loc", false)) return
        prefs.edit().putBoolean("asked_loc", true).apply()
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1)
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
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
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
