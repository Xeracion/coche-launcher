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
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
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
    private val p get() = Theme.paletteFor(this, dark)

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
    private var weatherBox: LinearLayout? = null
    private var weatherIcon: ImageView? = null
    private var weatherTemp: TextView? = null
    private var weatherNote: TextView? = null
    private var speedBox: LinearLayout? = null
    private var clockBox: LinearLayout? = null
    private var speedClockView: TextView? = null
    private var mediaCard: View? = null
    private var moving = false
    private var slowSince = 0L
    private var ticks = 0
    private val REQ_BT = 77

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
        DriveService.start(this)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        Apps.all(this, refresh = true)
        dark = Theme.isDark(this)
        build()
        connectMedia()
        startSpeed()
        DriveService.onPhoneConnected = { launchAuto() }
        if (DriveService.pendingAuto && !DriveService.autoDoneThisWake) {
            handler.postDelayed({ launchAuto() }, 800)
        }
        refreshWeather()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        DriveService.onPhoneConnected = null
        handler.removeCallbacks(ticker)
        disconnectMedia()
        stopSpeed()
        entered = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------- gestos ----------

    private var downOnControl = false
    private var downOnMedia = false
    private var flung = false

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!downOnControl) runGesture("g_double")
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!downOnControl) runGesture("g_long")
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.rawX - start.rawX
                val dy = e2.rawY - start.rawY
                val ax = kotlin.math.abs(dx)
                val ay = kotlin.math.abs(dy)
                // Deslizar en horizontal sobre la música: canción siguiente o anterior.
                if (downOnMedia && ax > ay * 1.5f && ax > dp(80)) {
                    flung = true
                    mediaCmd(if (dx < 0) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    mediaCard?.let { card ->
                        card.animate().translationX(if (dx < 0) -dp(24).toFloat() else dp(24).toFloat()).setDuration(110)
                            .withEndAction { card.animate().translationX(0f).setDuration(260).start() }.start()
                    }
                    return true
                }
                if (ay > ax * 1.5f && ay > dp(90)) {
                    flung = true
                    runGesture(if (dy < 0) "g_up" else "g_down")
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            flung = false
            downOnControl = hitsControl(root, ev.rawX, ev.rawY)
            downOnMedia = mediaCard?.let { hits(it, ev.rawX, ev.rawY) } ?: false
        }
        gestures.onTouchEvent(ev)
        if (flung && ev.actionMasked == MotionEvent.ACTION_UP) {
            // El gesto ya hizo su trabajo: el botón que había debajo no debe pulsarse.
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hits(v: View, x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return x >= loc[0] && y >= loc[1] && x <= loc[0] + v.width && y <= loc[1] + v.height
    }

    /** ¿El dedo cayó sobre un botón (y no en una zona vacía)? */
    private fun hitsControl(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != View.VISIBLE || !hits(v, x, y)) return false
        if (v !== root && (v.isClickable || v.isLongClickable)) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hitsControl(v.getChildAt(i), x, y)) return true
        return false
    }

    @Suppress("DEPRECATION")
    private fun openApps() {
        startActivity(Intent(this, AppsActivity::class.java))
        overridePendingTransition(R.anim.slide_up, R.anim.stay)
    }

    private fun runGesture(key: String) {
        when (Gestures.action(this, Gestures.byKey(key))) {
            "apps" -> openApps()
            "blackout" -> startActivity(Intent(this, BlackoutActivity::class.java))
            "assistant" -> assistant()
            "auto" -> Apps.launch(this, Apps.resolve(this, Slot.AUTO))
            "phone" -> Apps.launch(this, Apps.resolve(this, Slot.PHONE))
            "theme" -> cycleTheme()
            "settings" -> startActivity(Intent(this, SoundActivity::class.java))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Es la pantalla de inicio: "atrás" no hace nada.
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            assistant()
            return
        }
        startSpeed()
        DriveService.start(this)
        tick()
    }

    // ---------- interfaz ----------

    private fun build() {
        dark = Theme.isDark(this)
        val c = p
        root.removeAllViews()
        Look.applyBackground(this, root, c)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val animate = !entered
        entered = true

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(20), dp(30), dp(12))
        }
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        // Centro: reloj grande (o velocidad en marcha) a la izquierda, música a la derecha.
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        main.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))

        val left = FrameLayout(this)
        mid.addView(left, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(22) })

        // Reloj: el protagonista, como en los launchers de móvil.
        val clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
        }
        this.clockBox = clockBox
        left.addView(clockBox, FrameLayout.LayoutParams(-1, -1))
        clockView = TextView(this).apply {
            setTextColor(c.text)
            textSize = 104f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            letterSpacing = -0.03f
            setShadowLayer(dp(12).toFloat(), 0f, dp(2).toFloat(), if (c.dark) 0x66000000 else 0x33FFFFFF)
        }
        clockBox.addView(clockView)
        dateView = TextView(this).apply {
            setTextColor(c.text)
            alpha = 0.85f
            textSize = 22f
            setPadding(dp(4), 0, 0, 0)
        }
        clockBox.addView(dateView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        // El tiempo, en una píldora bajo la fecha. Solo aparece si hay internet.
        weatherBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = cardBg(c, 22)
            setPadding(dp(16), dp(8), dp(18), dp(8))
        }
        clockBox.addView(weatherBox, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        weatherIcon = ImageView(this).apply { setColorFilter(c.accent, PorterDuff.Mode.SRC_IN) }
        weatherBox!!.addView(weatherIcon, LinearLayout.LayoutParams(dp(26), dp(26)))
        weatherTemp = TextView(this).apply {
            setTextColor(c.text)
            textSize = 20f
        }
        weatherBox!!.addView(weatherTemp, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        weatherNote = TextView(this).apply {
            setTextColor(c.sub)
            textSize = 16f
        }
        weatherBox!!.addView(weatherNote, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) })
        Weather.last?.let { showWeather(it) }

        // Velocidad: sustituye al reloj mientras el coche se mueve.
        val speedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        this.speedBox = speedBox
        left.addView(speedBox, FrameLayout.LayoutParams(-1, -1))
        speedClockView = TextView(this).apply {
            setTextColor(c.sub)
            textSize = 22f
        }
        speedBox.addView(speedClockView)
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
            setTextColor(c.accent)
            textSize = 20f
            letterSpacing = 0.08f
        })
        speedHint = TextView(this).apply {
            setTextColor(c.sub)
            textSize = 13f
        }
        speedBox.addView(speedHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        speedBox.visibility = if (moving) View.VISIBLE else View.GONE
        clockBox.visibility = if (moving) View.GONE else View.VISIBLE

        val media = buildMediaCard(c)
        this.mediaCard = media
        mid.addView(media, LinearLayout.LayoutParams(0, -1, 1.25f))

        // Asa: indica que deslizando hacia arriba se abren todas las apps.
        main.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(c.text)
            }
            alpha = 0.35f
        }, LinearLayout.LayoutParams(dp(44), dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        })

        // Dock flotante, solo iconos.
        val dock = buildDock(c, animate)
        main.addView(dock, LinearLayout.LayoutParams(-1, dp(84)).apply { topMargin = dp(6) })

        if (animate) {
            (if (moving) speedBox else clockBox).enter(0, 14)
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
        // Estilo dock de móvil: iconos redondos flotando, sin barra de fondo.
        val dock = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        val items = mutableListOf<View>()
        val mainApps = listOf(
            Triple(Slot.PHONE, R.drawable.ic_phone, 0xFF2EA868.toInt()),
            Triple(Slot.RADIO, R.drawable.ic_radio, 0xFFEF8A2B.toInt()),
            Triple(Slot.BTMUSIC, R.drawable.ic_music, 0xFF8E5CE0.toInt()),
            Triple(Slot.AUTO, R.drawable.ic_car, 0xFF3D7FEA.toInt()),
        )
        for ((slot, icon, color) in mainApps) {
            val app = Apps.resolve(this, slot)
            items += dockItem(c, icon, slot.title, color,
                onClick = { Apps.launch(this, app) },
                onLong = { pickApp(slot) })
        }
        // Asistente del móvil (Claude) por el manos libres.
        items += dockItem(c, R.drawable.ic_assistant, "Asistente", 0xFFD9653B.toInt(),
            onClick = { assistant() },
            onLong = { pickAssistant() })
        val modeName = when (Theme.mode(this)) {
            "dark" -> "Modo oscuro fijo"
            "light" -> "Modo claro fijo"
            else -> "Modo automático"
        }
        val tools = listOf(
            dockItem(c, R.drawable.ic_eq, "Ajustes", null, onClick = { startActivity(Intent(this, SoundActivity::class.java)) }),
            dockItem(c, R.drawable.ic_theme, modeName, null, onClick = { cycleTheme() }),
            dockItem(c, R.drawable.ic_power, "Apagar pantalla", null, onClick = { startActivity(Intent(this, BlackoutActivity::class.java)) }),
            dockItem(c, R.drawable.ic_apps, "Todas las apps", null, onClick = { openApps() }),
        )
        for (v in items) dock.addView(v, LinearLayout.LayoutParams(0, -1, 1f))
        dock.addView(View(this), LinearLayout.LayoutParams(dp(28), 1))
        for (v in tools) dock.addView(v, LinearLayout.LayoutParams(0, -1, 1f))
        if (animate) {
            (items + tools).forEachIndexed { i, v -> v.enter(140L + i * 45L) }
        }
        return dock
    }

    /**
     * Icono redondo, como los de un móvil Android. Con color: círculo de color y glifo blanco.
     * Sin color (herramientas): círculo translúcido y glifo del color del texto.
     */
    private fun dockItem(c: Palette, icon: Int, description: String, color: Int?,
                         onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val box = FrameLayout(this)
        val size = if (color != null) dp(66) else dp(56)
        val circle = FrameLayout(this).apply {
            contentDescription = description
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color ?: c.chip)
            }
            if (color != null) {
                elevation = dp(5).toFloat()
                outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            isClickable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
            springy(0.86f)
        }
        box.addView(circle, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        circle.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(if (color != null) Color.WHITE else c.text, PorterDuff.Mode.SRC_IN)
        }, FrameLayout.LayoutParams(if (color != null) dp(32) else dp(26), if (color != null) dp(32) else dp(26), Gravity.CENTER))
        return box
    }

    // ---------- asistente ----------

    private fun assistant() {
        if (Voice.savedTarget(this) == null && Voice.needsPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            return
        }
        Voice.activate(this) { msg ->
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Mantener pulsado: elegir a mano una pantalla de fábrica de asistente, o volver a lo automático. */
    private fun pickAssistant() {
        val found = Voice.candidates(this)
        val apps = found + Apps.all(this, refresh = true)
        val labels = arrayOf("Automático (asistente del móvil)") +
            found.map { "Fábrica: " + it.label }.toTypedArray() +
            Apps.all(this).map { it.label }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Botón del asistente")
            .setItems(labels) { _, which ->
                Voice.saveTarget(this, if (which == 0) null else apps[which - 1].component)
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
        speedClockView?.text = clockView?.text
        val d = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "ES")).format(now)
        dateView?.text = d.replaceFirstChar { it.uppercase() }

        // Sin GPS durante unos segundos: tratamos el coche como parado.
        if (moving && SystemClock.elapsedRealtime() - lastFixAt > 6_000) setMoving(false)

        ticks++
        if (ticks % 150 == 0) refreshWeather()
        if (ticks % 15 == 0) {
            val newDark = Theme.isDark(this)
            if (newDark != dark) {
                dark = newDark
                build()
            }
        }
    }

    // ---------- tiempo ----------

    private fun refreshWeather() {
        Weather.refresh(this) { now -> if (now != null) showWeather(now) else weatherBox?.visibility = View.GONE }
    }

    private fun showWeather(w: Weather.Now) {
        // Si el dato tiene más de 2 horas (sin conexión desde entonces), no lo enseñamos.
        if (System.currentTimeMillis() - w.fetchedAt > 2 * 3600_000) {
            weatherBox?.visibility = View.GONE
            return
        }
        weatherIcon?.setImageResource(w.icon)
        weatherTemp?.text = "${w.tempC}°"
        weatherNote?.text = w.rainNote ?: ""
        weatherNote?.visibility = if (w.rainNote != null) View.VISIBLE else View.GONE
        weatherBox?.let { box ->
            if (box.visibility != View.VISIBLE) {
                box.visibility = View.VISIBLE
                box.alpha = 0f
                box.animate().alpha(1f).setDuration(600).start()
            }
        }
    }

    // ---------- Android Auto al conectar el móvil ----------

    private fun launchAuto() {
        if (DriveService.autoDoneThisWake) return
        DriveService.autoDoneThisWake = true
        DriveService.pendingAuto = false
        Apps.launch(this, Apps.resolve(this, Slot.AUTO))
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
        DriveService.start(this, refresh = true)
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

    /** En marcha, la velocidad sustituye al reloj con un fundido cruzado. */
    private fun setMoving(on: Boolean) {
        if (on == moving) return
        moving = on
        slowSince = 0L
        val show = (if (on) speedBox else clockBox) ?: return
        val hide = (if (on) clockBox else speedBox) ?: return
        hide.animate().cancel()
        show.animate().cancel()
        hide.animate().alpha(0f).translationY(-dp(14).toFloat()).setDuration(260).withEndAction {
            hide.visibility = View.GONE
            hide.alpha = 1f
            hide.translationY = 0f
            if (!moving) shownSpeed = -1
        }.start()
        show.visibility = View.VISIBLE
        show.alpha = 0f
        show.translationY = dp(14).toFloat()
        show.animate().alpha(1f).translationY(0f).setStartDelay(180).setDuration(420)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
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
            // Sin portada no hay cuadro: el título ocupa el hueco y se ve más grande.
            val texts = titleView?.parent as? View
            if (art != null) {
                iv.visibility = View.VISIBLE
                iv.setImageBitmap(art)
                (texts?.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(20)
                titleView?.textSize = 27f
            } else {
                iv.visibility = View.GONE
                iv.setImageDrawable(null)
                (texts?.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = 0
                titleView?.textSize = 32f
            }
            texts?.requestLayout()
        }
    }

    private fun mediaCmd(keyCode: Int) {
        val c = controller
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        // Al dar al play con música del móvil, conmutamos antes el canal de audio a Bluetooth.
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !playing && BtSwitch.isBtSource(this, c?.packageName)) {
            BtSwitch.warmAndPlay(this, true)
            return
        }
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
