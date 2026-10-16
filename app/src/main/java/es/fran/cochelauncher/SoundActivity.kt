package es.fran.cochelauncher

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Ecualizador propio, sustituto del DSP de fábrica. */
class SoundActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var root: LinearLayout
    private val valueLabels = mutableListOf<TextView>()
    private val bars = mutableListOf<SeekBar>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sound.init(this)
        p = Theme.palette(this)
        window.statusBarColor = p.bg
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(20), dp(14), dp(20), dp(16))
        }
        setContentView(root)
        build()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun build() {
        root.removeAllViews()
        valueLabels.clear()
        bars.clear()
        root.addView(screenHeader(p, "Sonido") { finish() })

        if (!Sound.available) {
            root.addView(TextView(this).apply {
                text = "Esta radio no permite el ecualizador de Android. Habrá que buscar el ajuste del fabricante."
                setTextColor(p.sub)
                textSize = 18f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }

        // Preajustes.
        val presets = LinearLayout(this)
        root.addView(presets, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12); bottomMargin = dp(8) })
        val current = Sound.presetName(this)
        for (pr in Sound.presets) {
            val sel = pr.name == current
            presets.addView(TextView(this).apply {
                text = pr.name
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(if (sel) android.graphics.Color.WHITE else p.text)
                background = cardBg(p, 28, if (sel) p.accent else p.card)
                isClickable = true
                setOnClickListener {
                    Sound.applyPreset(this@SoundActivity, pr)
                    build()
                }
            }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(10) })
        }

        // Bandas.
        val (lo, hi) = Sound.rangeDb()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(p)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        root.addView(card, LinearLayout.LayoutParams(-1, 0, 1f))
        for (b in 0 until Sound.bands()) {
            val hz = Sound.centerHz(b)
            val name = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
            card.addView(sliderRow(name, hi - lo, Sound.bandDb(this, b) - lo, { v -> "${if (v + lo > 0) "+" else ""}${v + lo} dB" }) { v ->
                Sound.setBandDb(this, b, v + lo)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        card.addView(sliderRow("Refuerzo de graves", 1000, Sound.bassLevel(this), { v -> "${v / 10} %" }) { v ->
            Sound.setBass(this, v)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun sliderRow(label: String, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(p.text)
            textSize = 16f
        }, LinearLayout.LayoutParams(dp(170), -2))
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
                override fun onStopTrackingTouch(s: SeekBar?) {
                    getSharedPreferences(Apps.PREFS, MODE_PRIVATE).edit().putString("preset", "Personal").apply()
                }
            })
        }
        row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(valueView, LinearLayout.LayoutParams(dp(80), -2))
        return row
    }
}

/** Cabecera de pantallas secundarias: flecha de volver y título. */
fun Activity.screenHeader(p: Palette, title: String, onBack: () -> Unit): View {
    val h = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    h.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_back)
        setColorFilter(p.text, PorterDuff.Mode.SRC_IN)
        background = circleBg(p, p.card)
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
