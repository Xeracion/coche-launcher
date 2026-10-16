package es.fran.cochelauncher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fondo con textura: degradado base, resplandores tipo aurora, curvas de nivel
 * muy finas y grano de película. Se pinta una sola vez en un bitmap para no gastar CPU.
 */
class Backdrop(private val dark: Boolean) : Drawable() {

    private var cache: Bitmap? = null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        cache?.recycle()
        cache = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val bmp = cache ?: render(b.width(), b.height()).also { cache = it }
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
    }

    private fun render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Degradado base.
        val top = if (dark) Color.parseColor("#101116") else Color.parseColor("#F1F2F5")
        val bottom = if (dark) Color.parseColor("#060607") else Color.parseColor("#DFE2E8")
        paint.shader = LinearGradient(0f, 0f, wf * 0.3f, hf, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, paint)

        // 2. Resplandores tipo aurora.
        val glows = if (dark) listOf(
            Glow(0.22f, 0.45f, 0.55f, 0x384C7BF4),   // azul detrás de la velocidad
            Glow(0.92f, 0.05f, 0.45f, 0x1C7A5CFF),   // violeta arriba a la derecha
            Glow(0.70f, 1.05f, 0.50f, 0x1426C6B0),   // turquesa abajo
        ) else listOf(
            Glow(0.20f, 0.40f, 0.60f, 0x40A9B8FF),   // lavanda
            Glow(0.95f, 0.10f, 0.50f, 0x38FFC9A8),   // melocotón
            Glow(0.65f, 1.10f, 0.55f, 0x30A8E6DA),   // menta
        )
        for (g in glows) {
            val r = g.radius * maxOf(wf, hf)
            paint.shader = RadialGradient(g.x * wf, g.y * hf, r, g.color, g.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, wf, hf, paint)
        }

        // 3. Curvas de nivel: líneas onduladas apenas visibles.
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = if (dark) 0x0EFFFFFF else 0x12000000
        val lines = 26
        val path = Path()
        for (i in 0 until lines) {
            val baseY = hf * (i + 0.5f) / lines
            path.reset()
            var x = -10f
            var first = true
            while (x <= wf + 10f) {
                val u = x / wf
                val y = baseY +
                    hf * 0.045f * sin(2 * PI * (u * 1.3 + i * 0.07)).toFloat() +
                    hf * 0.028f * sin(2 * PI * (u * 2.7 - i * 0.11 + 0.3)).toFloat() +
                    hf * 0.012f * sin(2 * PI * (u * 5.1 + i * 0.19)).toFloat()
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                x += 8f
            }
            c.drawPath(path, paint)
        }

        // 4. Viñeta suave en los bordes (solo oscuro).
        paint.style = Paint.Style.FILL
        if (dark) {
            paint.shader = RadialGradient(wf / 2, hf / 2, maxOf(wf, hf) * 0.75f,
                intArrayOf(0x00000000, 0x00000000, 0x66000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, wf, hf, paint)
        }

        // 5. Grano fino de película.
        val n = 128
        val noise = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val rnd = Random(42)
        val px = IntArray(n * n) {
            val a = rnd.nextInt(if (dark) 14 else 18)
            val v = if (rnd.nextBoolean()) 255 else 0
            Color.argb(a, v, v, v)
        }
        noise.setPixels(px, 0, n, 0, 0, n, n)
        paint.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawRect(0f, 0f, wf, hf, paint)
        noise.recycle()
        return bmp
    }

    private data class Glow(val x: Float, val y: Float, val radius: Float, val color: Int)

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
