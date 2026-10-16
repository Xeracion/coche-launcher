package es.fran.cochelauncher

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Cálculo de amanecer y anochecer (algoritmo del Almanac for Computers, precisión de un par de minutos).
 * Devuelve la hora en UTC (0..24) o null si ese día el sol no sale o no se pone.
 */
object Sun {
    private const val ZENITH = 90.833

    fun utcHour(dayOfYear: Int, lat: Double, lon: Double, rise: Boolean): Double? {
        val lngHour = lon / 15.0
        val t = dayOfYear + ((if (rise) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sinD(m) + 0.020 * sinD(2 * m) + 282.634, 360.0)
        var ra = norm(Math.toDegrees(atan(0.91764 * tanD(l))), 360.0)
        ra += floor(l / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0
        val sinDec = 0.39782 * sinD(l)
        val cosDec = cos(asin(sinDec))
        val cosH = (cosD(ZENITH) - sinDec * sinD(lat)) / (cosDec * cosD(lat))
        if (cosH > 1.0 || cosH < -1.0) return null
        val hDeg = if (rise) 360.0 - Math.toDegrees(acos(cosH)) else Math.toDegrees(acos(cosH))
        val h = hDeg / 15.0
        val localT = h + ra - 0.06571 * t - 6.622
        return norm(localT - lngHour, 24.0)
    }

    /** true si ahora es de día en esa posición. */
    fun isDay(dayOfYear: Int, utcNow: Double, lat: Double, lon: Double): Boolean {
        val rise = utcHour(dayOfYear, lat, lon, true)
        val set = utcHour(dayOfYear, lat, lon, false)
        if (rise == null || set == null) {
            // Caso polar: no aplica en Galicia, pero por si acaso.
            return utcNow in 8.0..18.0
        }
        return if (rise < set) utcNow >= rise && utcNow < set else utcNow >= rise || utcNow < set
    }

    private fun norm(v: Double, m: Double) = ((v % m) + m) % m
    private fun sinD(d: Double) = sin(Math.toRadians(d))
    private fun cosD(d: Double) = cos(Math.toRadians(d))
    private fun tanD(d: Double) = tan(Math.toRadians(d))
}
