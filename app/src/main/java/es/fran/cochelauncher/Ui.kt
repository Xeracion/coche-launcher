package es.fran.cochelauncher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/** Paleta de colores. Inspirada en la interfaz de Tesla: negro profundo, tarjetas grafito y azul de acento. */
data class Palette(
    val bg: Int,
    val card: Int,
    val press: Int,
    val text: Int,
    val sub: Int,
    val accent: Int,
) {
    companion object {
        val DARK = Palette(
            bg = Color.parseColor("#0A0A0B"),
            card = Color.parseColor("#1B1C1F"),
            press = Color.parseColor("#3A3D44"),
            text = Color.parseColor("#F4F4F5"),
            sub = Color.parseColor("#8E9097"),
            accent = Color.parseColor("#3E6AE1"),
        )
        val LIGHT = Palette(
            bg = Color.parseColor("#E9EAEC"),
            card = Color.parseColor("#FFFFFF"),
            press = Color.parseColor("#C9CCD2"),
            text = Color.parseColor("#171A20"),
            sub = Color.parseColor("#5C5E62"),
            accent = Color.parseColor("#3E6AE1"),
        )
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Fondo de tarjeta redondeada con efecto al pulsar. */
fun Context.cardBg(p: Palette, radiusDp: Int = 22, color: Int = p.card): Drawable {
    val shape = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }
    val mask = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(Color.WHITE)
    }
    return RippleDrawable(ColorStateList.valueOf(p.press), shape, mask)
}

/** Fondo circular para los botones redondos (reproductor, barra inferior). */
fun Context.circleBg(p: Palette, color: Int): Drawable {
    val circle = GradientDrawable()
    circle.shape = GradientDrawable.OVAL
    circle.setColor(color)
    val circleMask = GradientDrawable()
    circleMask.shape = GradientDrawable.OVAL
    circleMask.setColor(Color.WHITE)
    return RippleDrawable(ColorStateList.valueOf(p.press), circle, circleMask)
}
