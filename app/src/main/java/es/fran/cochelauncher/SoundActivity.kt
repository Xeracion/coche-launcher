package es.fran.cochelauncher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Ajustes: sonido, conducción, pantalla y arranque, con pestañas a la izquierda. */
class SoundActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var root: LinearLayout
    private lateinit var prefs: android.content.SharedPreferences
    private var tab = 0
    private val tabs = listOf("Sonido", "Conducción", "Pantalla", "Arranque", "Avanzado")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sound.init(this)
        prefs = getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE)
        tab = intent.getIntExtra("tab", 0)
        p = Theme.palette(this)
        window.statusBarColor = Color.TRANSPARENT
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Backdrop(p.dark)
            setPadding(dp(20), dp(14), dp(20), dp(16))
        }
        setContentView(root)
        build()
    }

    override fun onResume() {
        super.onResume()
        // Al volver de conceder un permiso, refrescamos.
        build()
        DriveService.start(this, refresh = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        build()
    }

    private fun changed() = DriveService.start(this, refresh = true)

    // ---------- estructura ----------

    private fun build() {
        p = Theme.palette(this)
        root.background = Backdrop(p.dark)
        root.removeAllViews()
        root.addView(screenHeader(p, "Ajustes") { finish() })

        val body = LinearLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })

        // Pestañas.
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(side, LinearLayout.LayoutParams(dp(200), -1).apply { rightMargin = dp(16) })
        tabs.forEachIndexed { i, name ->
            val sel = i == tab
            side.addView(TextView(this).apply {
                text = name
                textSize = 18f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(12), 0)
                setTextColor(if (sel) Color.WHITE else p.text)
                background = cardBg(p, 18, if (sel) p.accent else Color.TRANSPARENT)
                isClickable = true
                setOnClickListener { tab = i; build() }
            }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(6) })
        }

        // Contenido.
        val scroll = ScrollView(this).apply {
            background = cardBg(p, 26)
            isVerticalScrollBarEnabled = false
        }
        body.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        scroll.addView(content, -1, -2)
        when (tab) {
            0 -> buildSound(content)
            1 -> buildDriving(content)
            2 -> buildScreen(content)
            3 -> buildStartup(content)
            else -> buildAdvanced(content)
        }
    }

    // ---------- Sonido ----------

    private fun buildSound(c: LinearLayout) {
        val on = Sound.isOn(this)
        c.addView(toggleRow("Ecualizador", "Si al activarlo deja de sonar la música Bluetooth, apágalo: esta radio no lo admite.", on) { v ->
            Sound.setOn(this, v)
            build()
        })
        if (!on) return
        c.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
        if (!Sound.available) {
            c.addView(note("Esta radio no permite el ecualizador de Android. Habrá que buscar el ajuste del fabricante."))
            return
        }
        c.addView(title("Ecualizador"))
        val names = Sound.presets.map { it.name }
        val current = names.indexOf(Sound.presetName(this))
        c.addView(segment(names, current) { i ->
            Sound.applyPreset(this, Sound.presets[i])
            build()
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10); bottomMargin = dp(14) })

        val (lo, hi) = Sound.rangeDb()
        for (b in 0 until Sound.bands()) {
            val hz = Sound.centerHz(b)
            val name = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
            c.addView(sliderRow(name, hi - lo, Sound.bandDb(this, b) - lo, { v -> "${if (v + lo > 0) "+" else ""}${v + lo} dB" }, {
                prefs.edit().putString("preset", "Personal").apply()
            }) { v ->
                Sound.setBandDb(this, b, v + lo)
            })
        }
        c.addView(sliderRow("Refuerzo de graves", 1000, Sound.bassLevel(this), { v -> "${v / 10} %" }, {
            prefs.edit().putString("preset", "Personal").apply()
        }) { v -> Sound.setBass(this, v) })
    }

    // ---------- Conducción ----------

    private fun buildDriving(c: LinearLayout) {
        c.addView(title("Volumen según la velocidad"))
        c.addView(note("Sube el volumen poco a poco en carretera para compensar el ruido, y lo devuelve a tu nivel al frenar. Si tocas el volumen mientras conduces, tu ajuste pasa a ser el nuevo punto de partida."))
        val level = prefs.getInt("spdvol", 1)
        c.addView(segment(listOf("Apagado", "Suave", "Fuerte"), level) { i ->
            prefs.edit().putInt("spdvol", i).apply()
            changed()
            build()
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        c.addView(note(when (level) {
            1 -> "Suave: un escalón a partir de 60 km/h y otro a partir de 100 km/h."
            2 -> "Fuerte: escalones a 45, 75, 100 y 115 km/h."
            else -> "El volumen no cambia con la velocidad."
        }).apply { setPadding(0, dp(10), 0, 0) })
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
            c.addView(actionButton("Permitir ubicación") {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
            })
        }
    }

    // ---------- Pantalla ----------

    private fun buildScreen(c: LinearLayout) {
        c.addView(title("Tema"))
        val modes = listOf("auto", "dark", "light")
        c.addView(segment(listOf("Automático", "Oscuro", "Claro"), modes.indexOf(Theme.mode(this)).coerceAtLeast(0)) { i ->
            Theme.setMode(this, modes[i])
            changed()
            build()
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10); bottomMargin = dp(22) })

        val canWrite = Brightness.canWrite(this)
        val on = prefs.getBoolean("bright_auto", false) && canWrite
        c.addView(toggleRow("Brillo automático", "Más brillo de día y menos de noche, siguiendo el amanecer y el anochecer.", on) { v ->
            if (v && !Brightness.canWrite(this)) {
                askWriteSettings()
            } else {
                prefs.edit().putBoolean("bright_auto", v).apply()
                changed()
            }
            build()
        })
        if (!canWrite) {
            c.addView(note("Necesita el permiso \"Modificar ajustes del sistema\". Al activarlo se abrirá la pantalla para concederlo."))
            return
        }
        if (on) {
            c.addView(sliderRow("Brillo de día", 250, prefs.getInt("bright_day", 230) - 5, { v -> "${(v + 5) * 100 / 255} %" }, {}) { v ->
                prefs.edit().putInt("bright_day", v + 5).apply()
                if (!Theme.isDark(this)) Brightness.set(this, v + 5)
            })
            c.addView(sliderRow("Brillo de noche", 250, prefs.getInt("bright_night", 70) - 5, { v -> "${(v + 5) * 100 / 255} %" }, {}) { v ->
                prefs.edit().putInt("bright_night", v + 5).apply()
                if (Theme.isDark(this)) Brightness.set(this, v + 5)
            })
            c.addView(note("Mientras mueves la barra que corresponde a ahora mismo, verás el cambio en directo. Si la radio regula el brillo con las luces del coche, puede que mande ella."))
        }
    }

    private fun askWriteSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Activa el permiso y vuelve atrás", Toast.LENGTH_LONG).show()
            prefs.edit().putBoolean("bright_auto", true).apply()
        } catch (e: Exception) {
            Toast.makeText(this, "Esta radio no deja cambiar el brillo desde otras apps", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Arranque ----------

    private fun buildStartup(c: LinearLayout) {
        c.addView(toggleRow("Música al encender", "Conecta el sonido del móvil por Bluetooth y sigue con la última canción.",
            prefs.getBoolean("auto_play", true)) { v ->
            prefs.edit().putBoolean("auto_play", v).apply()
            changed()
        })
        c.addView(View(this), LinearLayout.LayoutParams(1, dp(18)))

        c.addView(toggleRow("Pausar la música en llamadas", "Al entrar una llamada pausa la música del móvil, y al colgar sigue donde estaba.",
            prefs.getBoolean("pause_call", true)) { v ->
            prefs.edit().putBoolean("pause_call", v).apply()
            changed()
        })
        c.addView(View(this), LinearLayout.LayoutParams(1, dp(18)))

        c.addView(toggleRow("Mantener mi pantalla al arrancar", "Si la radio planta su reproductor al encender, esta pantalla vuelve al frente durante los primeros segundos.",
            prefs.getBoolean("guard_home", true)) { v ->
            prefs.edit().putBoolean("guard_home", v).apply()
            changed()
        })
        c.addView(View(this), LinearLayout.LayoutParams(1, dp(18)))

        c.addView(toggleRow("Abrir Android Auto al conectar el cable", "Cuando enchufas el móvil por USB. El Bluetooth sigue solo para llamadas y música.",
            prefs.getBoolean("auto_aa", false)) { v ->
            prefs.edit().putBoolean("auto_aa", v).apply()
            changed()
            build()
        })
        if (prefs.getBoolean("auto_aa", false) && !Settings.canDrawOverlays(this)) {
            c.addView(note("Funciona siempre que la pantalla de inicio esté delante. Para que también funcione con otra app abierta, concede el permiso de mostrarse sobre otras apps."))
            c.addView(actionButton("Conceder permiso") {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    Toast.makeText(this, "Esta radio no tiene ese ajuste", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    // ---------- Avanzado ----------

    private fun buildAdvanced(c: LinearLayout) {
        c.addView(title("Actualizar la app"))
        c.addView(TextView(this).apply {
            text = "Versión instalada: " + try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
            setTextColor(p.sub)
            textSize = 15f
            setPadding(0, dp(6), 0, 0)
        })
        val st = updateState
        when {
            st.startsWith("buscando") -> c.addView(note("Buscando…"))
            st.startsWith("bajando") -> {
                c.addView(note("Descargando… " + st.removePrefix("bajando ") + " %"))
                if (st == "bajando 100") {
                    c.addView(note("Si no aparece el instalador, usa el botón de abajo."))
                    c.addView(actionButton("Instalar ahora") { installNow() })
                }
            }
            st.startsWith("fallo ") -> {
                c.addView(note("No se pudo instalar: " + st.removePrefix("fallo ") + "."))
                if (!Updater.canInstall(this)) {
                    c.addView(note("Falta darle permiso para instalar apps."))
                    c.addView(actionButton("Dar permiso") { Updater.openInstallPermission(this) })
                }
                c.addView(actionButton("Intentar instalar") { installNow() })
                c.addView(note("También queda una copia en la carpeta Descargas, por si prefieres abrirla con ES File Explorer."))
            }
            st.startsWith("error") -> {
                c.addView(note("No se pudo. Comprueba que la radio tiene internet."))
                c.addView(actionButton("Reintentar") { checkUpdate() })
            }
            st.startsWith("aldia") -> {
                c.addView(note("Ya tienes la última versión."))
                c.addView(actionButton("Buscar de nuevo") { checkUpdate() })
            }
            st.startsWith("hay ") -> {
                c.addView(note("Hay una versión nueva: " + st.removePrefix("hay ")))
                c.addView(actionButton("Descargar e instalar") { startDownload() })
            }
            else -> c.addView(actionButton("Buscar actualización") { checkUpdate() })
        }
        c.addView(View(this), LinearLayout.LayoutParams(1, dp(28)))

        c.addView(title("Canal de audio Bluetooth"))
        c.addView(note("Comprueba si la radio deja cambiar el canal de audio desde esta app, sin abrir la pantalla de fábrica."))
        if (btReport.isNotEmpty()) {
            c.addView(TextView(this).apply {
                text = btReport
                setTextColor(p.text)
                textSize = 15f
                setLineSpacing(0f, 1.25f)
                background = cardBg(p, 16, p.chip)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        c.addView(actionButton(if (btReport.isEmpty()) "Probar canal" else "Probar otra vez") { testBt() })
        c.addView(note("La prueba completa pasa un segundo a la radio FM y vuelve, para comprobar el cambio de verdad. Sonará un momento."))
        c.addView(actionButton("Prueba completa") { testBt(round = true) })
        c.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        c.addView(toggleRow("Abrir Música BT si falla", "Respaldo: si la orden directa no funciona, abre un instante la app de fábrica. Apagado, nunca se abre nada.",
            prefs.getBoolean("bt_fallback", false)) { v ->
            prefs.edit().putBoolean("bt_fallback", v).apply()
        })
        c.addView(View(this), LinearLayout.LayoutParams(1, dp(28)))

        c.addView(title("Compartir apps de fábrica por wifi"))
        c.addView(note("Prepara un archivo con las apps de radio, teléfono y música Bluetooth del fabricante y lo ofrece en la wifi. Desde un ordenador conectado a la misma wifi, abre en el navegador la dirección que aparecerá aquí."))
        val addr = WifiShare.address()
        if (WifiShare.running && addr != null) {
            c.addView(TextView(this).apply {
                text = addr
                setTextColor(p.accent)
                textSize = 30f
                setPadding(0, dp(16), 0, dp(4))
            })
            c.addView(note("Deja esta pantalla abierta mientras se descarga."))
            c.addView(actionButton("Dejar de compartir") { WifiShare.stop(); build() })
        } else {
            c.addView(actionButton("Compartir por wifi") { exportApps(share = true) })
            if (WifiShare.address() == null) c.addView(note("La radio no está conectada a ninguna wifi. Conéctala al punto de acceso del móvil."))
        }

        c.addView(View(this), LinearLayout.LayoutParams(1, dp(28)))
        c.addView(title("Registro del arranque"))
        c.addView(note("Apunta lo que hace la app al encender el coche. Tras arrancar, entra aquí y hazle una foto a esto."))
        c.addView(TextView(this).apply {
            text = Diag.read(this@SoundActivity)
            setTextColor(p.text)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(0f, 1.15f)
            background = cardBg(p, 16, p.chip)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val row = LinearLayout(this)
        row.addView(actionButton("Actualizar") { build() })
        row.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        row.addView(actionButton("Borrar") { Diag.clear(this); build() })
        c.addView(row)
    }

    private var btReport = ""

    private fun testBt(round: Boolean = false) {
        btReport = "Probando…"
        build()
        Thread {
            val r = try {
                if (round) BtSwitch.diagnoseRoundTrip(this) else BtSwitch.diagnose(this)
            } catch (e: Exception) { "Error: " + e.message }
            runOnUiThread { btReport = r; build() }
        }.start()
    }

    // ---------- actualización ----------

    private var updateState = ""
    private var pending: Updater.Release? = null

    private fun checkUpdate() {
        updateState = "buscando"
        build()
        Updater.check { r ->
            val mine = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
            updateState = when {
                r == null -> "error"
                mine != null && ("v" + mine.substringAfterLast('.')) == r.version -> "aldia"
                else -> "hay " + r.version
            }
            pending = r
            build()
        }
    }

    private fun startDownload() {
        val r = pending ?: return
        updateState = "bajando 0"
        build()
        Updater.download(this, r, onError = { msg ->
            updateState = "fallo " + msg
            build()
        }) { pct ->
            updateState = if (pct < 0) "error" else "bajando " + pct
            build()
        }
    }

    private fun installNow() {
        val f = Updater.downloaded(this)
        if (f == null) {
            updateState = "fallo no hay ninguna descarga"
        } else {
            val err = Updater.install(this, f)
            updateState = if (err == null) "bajando 100" else "fallo " + err
        }
        build()
    }

    override fun onDestroy() {
        super.onDestroy()
        WifiShare.stop()
    }

    private fun exportApps(share: Boolean = false) {
        Toast.makeText(this, "Exportando… puede tardar un poco", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                val pm = packageManager
                val targets = linkedSetOf<String>()
                for (s in listOf(Slot.RADIO, Slot.PHONE, Slot.BTMUSIC)) {
                    Apps.resolve(this, s)?.component?.packageName?.let { targets += it }
                }
                // Todo lo que comparta prefijo de fabricante con esas apps (servicios del canbus, MCU...).
                val prefixes = targets.map { it.split('.').take(2).joinToString(".") }
                    .filter { it != "com.android" && it != "com.google" && it != "android" }.toSet()
                val all = pm.getInstalledApplications(0)
                for (ai in all) if (prefixes.any { ai.packageName.startsWith("$it.") || ai.packageName == it }) targets += ai.packageName

                val out = java.io.File(cacheDir, "apps_fabrica.zip")
                var n = 0
                java.util.zip.ZipOutputStream(java.io.FileOutputStream(out)).use { zip ->
                    for (pkg in targets) {
                        try {
                            val ai = pm.getApplicationInfo(pkg, 0)
                            val files = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
                            files.forEachIndexed { i, f ->
                                zip.putNextEntry(java.util.zip.ZipEntry(if (i == 0) "$pkg.apk" else "${pkg}_split$i.apk"))
                                java.io.File(f).inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                            n++
                        } catch (e: Exception) {
                        }
                    }
                    zip.putNextEntry(java.util.zip.ZipEntry("paquetes.txt"))
                    val sb = StringBuilder()
                    sb.append("Exportadas: ").append(targets.joinToString(", ")).append("\n\n")
                    for (ai in all.sortedBy { it.packageName }) {
                        val sys = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        sb.append(ai.packageName).append(" | ").append(pm.getApplicationLabel(ai))
                            .append(if (sys) " | sistema" else "").append(" | ").append(ai.sourceDir).append('\n')
                    }
                    zip.write(sb.toString().toByteArray())
                    zip.closeEntry()
                }
                if (share) {
                    WifiShare.start(out)
                    "Listo: $n apps preparadas"
                } else "Preparadas $n apps"
            } catch (e: Exception) {
                "No se pudo exportar: " + e.message
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                build()
            }
        }.start()
    }

    private fun hasPerm(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    // ---------- piezas ----------

    private fun title(t: String) = TextView(this).apply {
        text = t
        setTextColor(p.text)
        textSize = 21f
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        setTextColor(p.sub)
        textSize = 15f
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(6), 0, 0)
    }

    private fun actionButton(label: String, onClick: () -> Unit): View {
        val wrap = LinearLayout(this).apply { setPadding(0, dp(14), 0, 0) }
        wrap.addView(TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(24), 0, dp(24), 0)
            background = cardBg(p, 24, p.accent)
            isClickable = true
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        return wrap
    }

    /** Selector de varias opciones en una cápsula. */
    private fun segment(options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            background = cardBg(p, 26, p.chip)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        options.forEachIndexed { i, o ->
            val sel = i == selected
            row.addView(TextView(this).apply {
                text = o
                textSize = 16f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(if (sel) Color.WHITE else p.text)
                background = cardBg(p, 22, if (sel) p.accent else Color.TRANSPARENT)
                isClickable = true
                setOnClickListener { if (!sel) onSelect(i) }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return row
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun toggleRow(t: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        texts.addView(title(t).apply { textSize = 19f })
        texts.addView(note(sub))
        val sw = Switch(this).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, if (p.dark) 0xFFB8BAC0.toInt() else Color.WHITE))
            trackTintList = ColorStateList(states, intArrayOf(p.accent, p.sub))
            scaleX = 1.3f
            scaleY = 1.3f
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(20); rightMargin = dp(8) })
        return row
    }

    private fun sliderRow(label: String, max: Int, value: Int, fmt: (Int) -> String, onStop: () -> Unit, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(p.text)
            textSize = 16f
        }, LinearLayout.LayoutParams(dp(160), -2))
        val valueView = TextView(this).apply {
            text = fmt(value)
            setTextColor(p.sub)
            textSize = 15f
            gravity = Gravity.END
        }
        val bar = SeekBar(this).apply {
            this.max = max
            progress = value
            progressTintList = ColorStateList.valueOf(p.accent)
            thumbTintList = ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = ColorStateList.valueOf(p.sub)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    valueView.text = fmt(v)
                    onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) = onStop()
            })
        }
        row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(valueView, LinearLayout.LayoutParams(dp(76), -2))
        return row
    }
}

/** Cabecera de pantallas secundarias: flecha de volver y título. */
fun Activity.screenHeader(p: Palette, title: String, onBack: () -> Unit): View {
    val h = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    h.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_back)
        setColorFilter(p.text, PorterDuff.Mode.SRC_IN)
        background = circleBg(p, p.chip)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        isClickable = true
        setOnClickListener { onBack() }
    }, LinearLayout.LayoutParams(dp(56), dp(56)))
    h.addView(TextView(this).apply {
        text = title
        setTextColor(p.text)
        textSize = 26f
        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
    }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(18) })
    return h
}
