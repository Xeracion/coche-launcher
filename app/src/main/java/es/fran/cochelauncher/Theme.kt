package es.fran.cochelauncher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.view.View
import java.util.Calendar
import java.util.TimeZone

/** Modo día/noche compartido por todas las pantallas. */
object Theme {

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE).getString("theme", "auto") ?: "auto"

    fun setMode(ctx: Context, m: String) {
        ctx.getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE).edit().putString("theme", m).apply()
    }

    fun isDark(ctx: Context): Boolean = when (mode(ctx)) {
        "dark" -> true
        "light" -> false
        else -> !isDayNow(ctx)
    }

    fun palette(ctx: Context): Palette = if (isDark(ctx)) Palette.DARK else Palette.LIGHT

    private fun isDayNow(ctx: Context): Boolean {
        val (lat, lon) = position(ctx)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val utcNow = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        return Sun.isDay(cal.get(Calendar.DAY_OF_YEAR), utcNow, lat, lon)
    }

    /** Posición del GPS de la radio. Si no hay, la última guardada, y si no, Ferrol. */
    fun position(ctx: Context): Pair<Double, Double> {
        val prefs = ctx.getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE)
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
}

/** Oculta la barra de la radio mientras se ve una pantalla nuestra. */
@Suppress("DEPRECATION")
fun Activity.hideSystemBars() {
    window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        or View.SYSTEM_UI_FLAG_FULLSCREEN
        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
}
