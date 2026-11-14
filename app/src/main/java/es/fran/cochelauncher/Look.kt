package es.fran.cochelauncher

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.ColorUtils

/**
 * Aspecto inspirado en los launchers de móvil "ambientales": de fondo, la imagen de
 * fondo de pantalla de la radio; los colores de botones y tarjetas salen de esa imagen.
 * Con el fondo propio (textura) se usan los colores fijos de siempre.
 */
object Look {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE)

    /** "wall" = fondo de pantalla de la radio; "texture" = textura propia. */
    fun bgMode(ctx: Context): String = prefs(ctx).getString("bg_mode", "wall") ?: "wall"
    fun setBgMode(ctx: Context, m: String) { prefs(ctx).edit().putString("bg_mode", m).apply() }

    /** ¿Los colores salen del fondo de pantalla? */
    fun wallColors(ctx: Context): Boolean = prefs(ctx).getBoolean("wall_colors", true)
    fun setWallColors(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("wall_colors", v).apply() }

    @Volatile private var seed: Int? = null
    @Volatile private var seedRead = false
    private var listening = false

    /** Color principal del fondo de pantalla, o null si no se puede saber. */
    fun seedColor(ctx: Context): Int? {
        if (seedRead) return seed
        val app = ctx.applicationContext
        val wm = WallpaperManager.getInstance(app)
        seed = try {
            wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
        } catch (e: Throwable) { null }
        seedRead = true
        if (!listening) {
            listening = true
            try {
                wm.addOnColorsChangedListener({ _, _ -> forget() }, Handler(Looper.getMainLooper()))
            } catch (e: Throwable) { }
        }
        return seed
    }

    /** Olvida el color guardado: se vuelve a leer en el siguiente dibujado. */
    fun forget() { seedRead = false; seed = null }

    /** Paleta teñida con el color del fondo. */
    fun tint(base: Palette, color: Int): Palette {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        // Casi gris: el fondo no tiene un color claro, dejamos el acento de siempre.
        val greyish = hsl[1] < 0.12f
        val accent = if (greyish) base.accent else {
            val a = floatArrayOf(hsl[0], hsl[1].coerceIn(0.45f, 0.80f), if (base.dark) 0.56f else 0.44f)
            ColorUtils.HSLToColor(a)
        }
        val tintAmount = if (greyish) 0.04f else if (base.dark) 0.16f else 0.10f
        val glassBase = if (base.dark) Color.rgb(0x15, 0x17, 0x1C) else Color.WHITE
        val glassRgb = ColorUtils.blendARGB(glassBase, color, tintAmount)
        val glass = ColorUtils.setAlphaComponent(glassRgb, if (base.dark) 0xB4 else 0xC4)
        val card = ColorUtils.blendARGB(base.card, color, tintAmount)
        return base.copy(accent = accent, glass = glass, card = card)
    }

    const val REQ_WALL = 91

    /** Abre la galería para elegir una imagen de fondo. La respuesta llega a onActivityResult. */
    fun pickWallpaper(act: Activity) {
        try {
            act.startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_WALL)
        } catch (e: Exception) {
            try {
                act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Elegir fondo"))
            } catch (e2: Exception) {
                Toast.makeText(act, "La radio no tiene ninguna app para elegir imágenes", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Pone como fondo la imagen elegida. Avisa por [done] en el hilo principal. */
    fun setWallpaper(act: Activity, uri: Uri?, done: () -> Unit) {
        if (uri == null) return
        Toast.makeText(act, "Poniendo el fondo…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try {
                act.contentResolver.openInputStream(uri)?.use {
                    WallpaperManager.getInstance(act).setStream(it, null, true, WallpaperManager.FLAG_SYSTEM)
                }
                true
            } catch (e: Exception) { false }
            act.runOnUiThread {
                forget()
                if (!ok) Toast.makeText(act, "No se pudo poner esa imagen", Toast.LENGTH_LONG).show()
                done()
            }
        }.start()
    }

    /** Pone el fondo de una pantalla: imagen de fondo con velo, o la textura propia. */
    fun applyBackground(act: Activity, root: View, p: Palette) {
        if (bgMode(act) == "wall") {
            act.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            act.window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            root.background = Scrim(p.dark)
        } else {
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            act.window.setBackgroundDrawable(ColorDrawable(p.bg))
            root.background = Backdrop(p.dark)
        }
    }
}

/** Velo sobre la imagen de fondo para que el texto se lea siempre. */
class Scrim(private val dark: Boolean) : Drawable() {
    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val top = if (dark) 0x59000000 else 0x40FFFFFF
        val mid = if (dark) 0x80000000.toInt() else 0x66FFFFFF
        val bottom = if (dark) 0xC0000000.toInt() else 0xA6FFFFFF.toInt()
        paint.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(top, mid, bottom), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
