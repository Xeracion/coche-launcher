package es.fran.cochelauncher

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

/**
 * Ecualizador global (sesión de audio 0). Afecta a todo lo que pasa por el mezclador de Android:
 * música Bluetooth, Android Auto, Spotify, YouTube... La radio FM puede ir por otro circuito.
 * Los objetos se mantienen vivos mientras viva el proceso del launcher, y se reaplican al arrancar.
 */
object Sound {
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    var available = false
        private set

    data class Preset(val name: String, val gains: List<Int>, val bass: Int)

    /** Ganancias en decibelios, de graves a agudos (se adaptan al número de bandas real). */
    val presets = listOf(
        Preset("Plano", listOf(0, 0, 0, 0, 0), 0),
        Preset("Graves", listOf(6, 4, 0, 1, 2), 500),
        Preset("Voz", listOf(-2, 0, 4, 3, 0), 0),
        Preset("Viaje", listOf(4, 2, 0, 2, 4), 300),
        Preset("Agudos", listOf(0, 0, 1, 4, 6), 0),
    )

    /** El ecualizador va apagado por defecto: en algunas radios silencia el audio Bluetooth. */
    fun isOn(ctx: Context) = prefs(ctx).getBoolean("eq_on", false)

    fun init(ctx: Context) {
        if (!isOn(ctx)) {
            release()
            return
        }
        if (eq != null) return
        try {
            eq = Equalizer(1000, 0).apply { enabled = true }
            available = true
        } catch (e: Throwable) {
            eq = null
            available = false
        }
        try {
            bass = BassBoost(1000, 0).apply { enabled = true }
        } catch (e: Throwable) {
            bass = null
        }
        restore(ctx)
    }

    fun setOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("eq_on", on).apply()
        init(ctx)
    }

    private fun release() {
        try { eq?.enabled = false; eq?.release() } catch (e: Throwable) { }
        try { bass?.enabled = false; bass?.release() } catch (e: Throwable) { }
        eq = null
        bass = null
    }

    fun bands(): Int = try { eq?.numberOfBands?.toInt() ?: 0 } catch (e: Throwable) { 0 }

    /** Rango de cada banda en dB. */
    fun rangeDb(): Pair<Int, Int> = try {
        val r = eq!!.bandLevelRange
        (r[0] / 100) to (r[1] / 100)
    } catch (e: Throwable) {
        -12 to 12
    }

    fun centerHz(band: Int): Int = try { (eq!!.getCenterFreq(band.toShort()) / 1000) } catch (e: Throwable) { 0 }

    fun bandDb(ctx: Context, band: Int): Int =
        prefs(ctx).getInt("eq_$band", 0)

    fun setBandDb(ctx: Context, band: Int, db: Int) {
        prefs(ctx).edit().putInt("eq_$band", db).apply()
        try { eq?.setBandLevel(band.toShort(), (db * 100).toShort()) } catch (e: Throwable) { }
    }

    fun bassLevel(ctx: Context): Int = prefs(ctx).getInt("bass", 0)

    fun setBass(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("bass", v).apply()
        try { bass?.setStrength(v.coerceIn(0, 1000).toShort()) } catch (e: Throwable) { }
    }

    fun applyPreset(ctx: Context, p: Preset) {
        val n = bands()
        val (lo, hi) = rangeDb()
        for (b in 0 until n) {
            // Reparte las 5 ganancias del preset sobre las bandas reales.
            val idx = if (n <= 1) 0 else (b * (p.gains.size - 1) + (n - 1) / 2) / (n - 1)
            setBandDb(ctx, b, p.gains[idx.coerceIn(0, p.gains.size - 1)].coerceIn(lo, hi))
        }
        setBass(ctx, p.bass)
        prefs(ctx).edit().putString("preset", p.name).apply()
    }

    fun presetName(ctx: Context): String = prefs(ctx).getString("preset", "Plano") ?: "Plano"

    private fun restore(ctx: Context) {
        for (b in 0 until bands()) setBandDb(ctx, b, bandDb(ctx, b))
        setBass(ctx, bassLevel(ctx))
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE)
}
