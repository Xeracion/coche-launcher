package es.fran.cochelauncher

import android.Manifest
import android.app.Activity
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private var artView: ImageView? = null
    private var pulse: PulseRing? = null
    private var shownSpeed = -1
    private var speedAnim: ValueAnimator? = null
    private var entered = false

    private var controller: MediaController? = null
    private var hasMediaAccess = false

    private var lastFix: Location? = null
    private var lastFixAt = 0L
    private var speedBox: LinearLayout? = null
    private var moving = false
    private var slowSince = 0L
    private var speedLayoutAnim: ValueAnimator? = null
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
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        disconnectMedia()
        stopSpeed()
        entered = false
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
        root.background = Backdrop(c.dark)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val animate = !entered
        entered = true

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(14), dp(26), dp(16))
        }
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        // Cabecera: hora a la izquierda, fecha a la derecha.
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        main.addView(header, LinearLayout.LayoutParams(-1, dp(44)))
        clockView = TextView(this).apply {
            setTextColor(c.text)
            textSize = 30f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.02f
        }
        header.addView(clockView)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        dateView = TextView(this).apply {
            setTextColor(c.sub)
            textSize = 17f
            letterSpacing = 0.02f
        }
        header.addView(dateView)

        // Centro: velocidad a la izquierda, música a la derecha.
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        main.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })

        // La velocidad solo se ve en marcha. Parado, la música ocupa todo el ancho.
        val speedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
        }
        this.speedBox = speedBox
        mid.addView(speedBox, LinearLayout.LayoutParams(0, -1, if (moving) 1f else 0f).apply {
            rightMargin = if (moving) dp(18) else 0
        })
        if (!moving) speedBox.visibility = View.GONE
        speedView = TextView(this).apply {
            text = if (shownSpeed >= 0) shownSpeed.toString() else "--"
            setTextColor(c.text)
            textSize = 144f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER
            letterSpacing = -0.02f
        }
        speedBox.addView(speedView)
        speedBox.addView(TextView(this).apply {
            text = "km/h"
            setTextColor(c.sub)
            textSize = 20f
            letterSpacing = 0.08f
        })
        speedHint = TextView(this).apply {
            setTextColor(c.sub)
            textSize = 13f
        }
        speedBox.addView(speedHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        val media = buildMediaCard(c)
        mid.addView(media, LinearLayout.LayoutParams(0, -1, 1.5f))

        // Barra inferior, solo iconos.
        val dock = buildDock(c, animate)
        main.addView(dock, LinearLayout.LayoutParams(-1, dp(84)).apply { topMargin = dp(16) })

        if (animate) {
            if (moving) speedBox.enter(0, 14)
            media.enter(80, 20)
        }
        tick()
        updateMedia()
    }

    private fun buildMediaCard(c: Palette): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(c, 30)
            setPadding(dp(24), dp(22), dp(24), dp(20))
        }

        // Carátula y datos de la canción.
        val info = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                if (!hasMediaAccess) openMediaAccessSettings()
                else Apps.launch(this@MainActivity, Apps.resolve(this@MainActivity, Slot.BTMUSIC))
            }
        }
        card.addView(info, LinearLayout.LayoutParams(-1, -2))
        artView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(c.chip)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
                }
            }
        }
        info.addView(artView, LinearLayout.LayoutParams(dp(112), dp(112)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(20) })
        texts.addView(TextView(this).apply {
            text = "Reproduciendo"
            setTextColor(c.accent)
            textSize = 13f
            letterSpacing = 0.06f
        })
        titleView = TextView(this).apply {
            setTextColor(c.text)
            textSize = 27f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        artistView = TextView(this).apply {
            setTextColor(c.sub)
            textSize = 17f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(artistView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        card.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Controles: volumen menos, anterior, reproducir, siguiente, volumen más.
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        card.addView(controls, LinearLayout.LayoutParams(-1, dp(104)))
        controls.addView(roundButton(c, R.drawable.ic_vol_down, c.chip, c.sub) { changeVolume(AudioManager.ADJUST_LOWER) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(roundButton(c, R.drawable.ic_prev, c.chip, c.text) { mediaCmd(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, LinearLayout.LayoutParams(dp(64), dp(64)))

        val playWrap = FrameLayout(this)
        pulse = PulseRing(this, c.accent)
        playWrap.addView(pulse, FrameLayout.LayoutParams(-1, -1))
        playBtn = roundButton(c, R.drawable.ic_play, c.accent, Color.WHITE) { mediaCmd(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        playWrap.addView(playBtn, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
        controls.addView(playWrap, LinearLayout.LayoutParams(dp(104), dp(104)).apply { leftMargin = dp(16); rightMargin = dp(16) })

        controls.addView(roundButton(c, R.drawable.ic_next, c.chip, c.text) { mediaCmd(KeyEvent.KEYCODE_MEDIA_NEXT) }, LinearLayout.LayoutParams(dp(64), dp(64)))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(roundButton(c, R.drawable.ic_vol_up, c.chip, c.sub) { changeVolume(AudioManager.ADJUST_RAISE) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        return card
    }

    private fun roundButton(c: Palette, icon: Int, bg: Int, fg: Int, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(fg, PorterDuff.Mode.SRC_IN)
            background = circleBg(c, bg)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            setOnClickListener { onClick() }
            springy()
        }

    private fun buildDock(c: Palette, animate: Boolean): View {
        val dock = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg(c, 32)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val items = mutableListOf<View>()
        val mainApps = listOf(
            Slot.PHONE to R.drawable.ic_phone,
            Slot.RADIO to R.drawable.ic_radio,
            Slot.BTMUSIC to R.drawable.ic_music,
            Slot.AUTO to R.drawable.ic_car,
        )
        for ((slot, icon) in mainApps) {
            val app = Apps.resolve(this, slot)
            items += dockItem(c, icon, slot.title, c.accent, accent = true,
                onClick = { Apps.launch(this, app) },
                onLong = { pickApp(slot) })
        }
        val modeName = when (Theme.mode(this)) {
            "dark" -> "Modo oscuro fijo"
            "light" -> "Modo claro fijo"
            else -> "Modo automático"
        }
        val tools = listOf(
            dockItem(c, R.drawable.ic_eq, "Sonido", c.text, onClick = { startActivity(Intent(this, SoundActivity::class.java)) }),
            dockItem(c, R.drawable.ic_theme, modeName, c.text, onClick = { cycleTheme() }),
            dockItem(c, R.drawable.ic_power, "Apagar pantalla", c.text, onClick = { startActivity(Intent(this, BlackoutActivity::class.java)) }),
            dockItem(c, R.drawable.ic_apps, "Todas las apps", c.text, onClick = { startActivity(Intent(this, AppsActivity::class.java)) }),
        )
        for (v in items) dock.addView(v, LinearLayout.LayoutParams(0, -1, 1f))
        dock.addView(View(this).apply { setBackgroundColor(c.edge) }, LinearLayout.LayoutParams(dp(1), dp(36)).apply { leftMargin = dp(10); rightMargin = dp(10) })
        for (v in tools) dock.addView(v, LinearLayout.LayoutParams(0, -1, 1f))
        if (animate) {
            (items + tools).forEachIndexed { i, v -> v.enter(140L + i * 45L) }
        }
        return dock
    }

    private fun dockItem(c: Palette, icon: Int, description: String, iconColor: Int, accent: Boolean = false,
                         onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        // Estilo baldosa: cada icono dentro de una tesela redondeada.
        // Principales en azul con glifo blanco; herramientas en tesela neutra.
        val box = FrameLayout(this)
        val tile = FrameLayout(this).apply {
            contentDescription = description
            background = cardBg(c, 18, if (accent) c.accent else c.tile)
            isClickable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
            springy(0.84f)
        }
        box.addView(tile, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        tile.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(if (accent) Color.WHITE else iconColor, PorterDuff.Mode.SRC_IN)
        }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
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

        // Sin GPS durante unos segundos: tratamos el coche como parado.
        if (moving && SystemClock.elapsedRealtime() - lastFixAt > 6_000) setMoving(false)

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
        speedHint?.text = ""
        val now = SystemClock.elapsedRealtime()

        // Histéresis: aparece al pasar de 6 km/h; se oculta tras 8 s por debajo de 3 km/h.
        // Así no parpadea en un semáforo o en un atasco a paso de peatón.
        if (kmh >= 6) {
            slowSince = 0L
            if (!moving) setMoving(true)
        } else if (kmh < 3 && moving) {
            if (slowSince == 0L) slowSince = now
            if (now - slowSince > 8_000) setMoving(false)
        }
        animateSpeed(kmh)
    }

    /** Muestra u oculta el bloque de velocidad con una transición suave del ancho. */
    private fun setMoving(on: Boolean) {
        if (on == moving) return
        moving = on
        slowSince = 0L
        val box = speedBox ?: return
        val lp = box.layoutParams as LinearLayout.LayoutParams
        speedLayoutAnim?.cancel()
        if (on) {
            box.visibility = View.VISIBLE
            box.alpha = 0f
            box.translationX = -dp(30).toFloat()
        }
        val from = lp.weight
        val to = if (on) 1f else 0f
        speedLayoutAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                lp.weight = from + (to - from) * t
                lp.rightMargin = (dp(18) * lp.weight).toInt()
                box.layoutParams = lp
                box.alpha = if (on) t else 1f - t * 1.6f
                box.translationX = if (on) -dp(30) * (1f - t) else -dp(30) * t
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!moving) {
                        box.visibility = View.GONE
                        shownSpeed = -1
                    }
                }
            })
            start()
        }
    }

    /** La cifra cuenta hasta el valor nuevo en lugar de saltar. */
    private fun animateSpeed(target: Int) {
        val from = if (shownSpeed < 0) target else shownSpeed
        speedAnim?.cancel()
        speedAnim = ValueAnimator.ofInt(from, target).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shownSpeed = it.animatedValue as Int
                speedView?.text = shownSpeed.toString()
            }
            start()
        }
    }

    // ---------- volumen ----------

    private fun changeVolume(direction: Int) {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
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
        pulse?.setActive(playing)

        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        artView?.let { iv ->
            if (art != null) {
                iv.clearColorFilter()
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setPadding(0, 0, 0, 0)
                iv.setImageBitmap(art)
            } else {
                iv.setImageResource(R.drawable.ic_music)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.setColorFilter(p.accent, PorterDuff.Mode.SRC_IN)
                iv.setPadding(dp(32), dp(32), dp(32), dp(32))
            }
        }
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
