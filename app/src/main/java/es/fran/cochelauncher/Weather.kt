package es.fran.cochelauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * El tiempo, de Open-Meteo (gratuito, sin registro). Solo funciona con internet;
 * si no hay conexión, simplemente no se muestra nada.
 */
object Weather {

    data class Now(
        val tempC: Int,
        val icon: Int,
        /** Aviso de lluvia próxima, o null. */
        val rainNote: String?,
        val fetchedAt: Long,
    )

    @Volatile var last: Now? = null
        private set
    @Volatile private var loading = false
    private val main = Handler(Looper.getMainLooper())

    /** Pide el tiempo si el dato tiene más de 20 minutos. Avisa por onReady en el hilo principal. */
    fun refresh(ctx: Context, onReady: (Now?) -> Unit) {
        val cached = last
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < 20 * 60_000) {
            onReady(cached)
            return
        }
        if (loading) return
        loading = true
        val (lat, lon) = Theme.position(ctx)
        Thread {
            val result = try { fetch(lat, lon) } catch (e: Exception) { null }
            if (result != null) last = result
            loading = false
            main.post { onReady(result ?: last) }
        }.start()
    }

    private fun fetch(lat: Double, lon: Double): Now? {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                "&current=temperature_2m,weather_code,is_day" +
                "&minutely_15=precipitation&forecast_minutely_15=8&timezone=auto",
            lat, lon,
        )
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val cur = json.getJSONObject("current")
            val temp = cur.getDouble("temperature_2m").roundToInt()
            val code = cur.getInt("weather_code")
            val isDay = cur.optInt("is_day", 1) == 1

            // Lluvia en las próximas dos horas, en tramos de 15 minutos.
            var note: String? = null
            val raining = code in 51..67 || code in 80..82 || code in 95..99
            val m = json.optJSONObject("minutely_15")?.optJSONArray("precipitation")
            if (m != null && !raining) {
                for (i in 0 until m.length()) {
                    if (m.optDouble(i, 0.0) >= 0.2) {
                        val min = (i + 1) * 15
                        note = if (min <= 60) "Lluvia en $min min" else "Lluvia en ${min / 60} h"
                        break
                    }
                }
            }
            return Now(temp, iconFor(code, isDay), note, System.currentTimeMillis())
        } finally {
            conn.disconnect()
        }
    }

    /** Códigos WMO a icono. */
    private fun iconFor(code: Int, day: Boolean): Int = when (code) {
        0, 1 -> if (day) R.drawable.ic_w_sun else R.drawable.ic_w_moon
        2 -> if (day) R.drawable.ic_w_partly else R.drawable.ic_w_cloud
        3, 45, 48 -> R.drawable.ic_w_cloud
        in 51..67, in 80..82 -> R.drawable.ic_w_rain
        in 71..77, 85, 86 -> R.drawable.ic_w_snow
        in 95..99 -> R.drawable.ic_w_storm
        else -> R.drawable.ic_w_cloud
    }
}
