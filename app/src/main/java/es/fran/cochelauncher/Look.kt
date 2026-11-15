package es.fran.cochelauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * Aspecto común: textura propia de fondo y colores teñidos con un azul mar.
 */
object Look {

    /** Azul mar del que salen el color de acento y el tono de las tarjetas. */
    const val SEA = 0xFF1D4F73.toInt()

    /** Paleta teñida con [color]. */
    fun tint(base: Palette, color: Int): Palette {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        val accent = ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceIn(0.45f, 0.80f), if (base.dark) 0.56f else 0.44f))
        val amount = if (base.dark) 0.16f else 0.10f
        val glassBase = if (base.dark) Color.rgb(0x15, 0x17, 0x1C) else Color.WHITE
        val glass = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(glassBase, color, amount), if (base.dark) 0xB4 else 0xC4)
        val card = ColorUtils.blendARGB(base.card, color, amount)
        return base.copy(accent = accent, glass = glass, card = card)
    }

    /** Fondo de una pantalla: la textura propia. */
    fun applyBackground(act: Activity, root: View, p: Palette) {
        act.window.setBackgroundDrawable(ColorDrawable(p.bg))
        root.background = Backdrop(p.dark)
    }
}
