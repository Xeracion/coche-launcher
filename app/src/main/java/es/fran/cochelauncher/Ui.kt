package es.fran.cochelauncher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Paleta. Oscuro: grafito profundo con azul de acento. Claro: gris perla.
 * Las tarjetas son "cristal": semitransparentes sobre la textura, con un filo de luz.
 */
data class Palette(
    val dark: Boolean,
    val bg: Int,
    val card: Int,
    val glass: Int,
    val edge: Int,
    val chip: Int,
    val press: Int,
    val text: Int,
    val sub: Int,
    val accent: Int,
) {
    companion object {
        val DARK = Palette(
            dark = true,
            bg = Color.parseColor("#09090B"),
            card = Color.parseColor("#1B1C1F"),
            glass = 0xB0181A1F.toInt(),
            edge = 0x1AFFFFFF,
            chip = 0x14FFFFFF,
            press = 0x33FFFFFF,
            text = Color.parseColor("#F4F4F5"),
            sub = Color.parseColor("#8E9097"),
            accent = Color.parseColor("#4C7BF4"),
        )
        val LIGHT = Palette(
            dark = false,
            bg = Color.parseColor("#E8EAEE"),
            card = Color.parseColor("#FFFFFF"),
            glass = 0xB8FFFFFF.toInt(),
            edge = 0xF2FFFFFF.toInt(),
            chip = 0x0D000000,
            press = 0x1F000000,
            text = Color.parseColor("#14161B"),
            sub = Color.parseColor("#62656C"),
            accent = Color.parseColor("#3463E0"),
        )
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Tarjeta de cristal redondeada, con filo de luz y efecto al pulsar. */
fun Context.cardBg(p: Palette, radiusDp: Int = 26, color: Int = p.glass): Drawable {
    val shape = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (color == p.glass) setStroke(dp(1).coerceAtLeast(1), p.edge)
    }
    val mask = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(Color.WHITE)
    }
    return RippleDrawable(ColorStateList.valueOf(p.press), shape, mask)
}

/** Fondo circular para los botones redondos. */
fun Context.circleBg(p: Palette, color: Int): Drawable {
    val circle = GradientDrawable()
    circle.shape = GradientDrawable.OVAL
    circle.setColor(color)
    val circleMask = GradientDrawable()
    circleMask.shape = GradientDrawable.OVAL
    circleMask.setColor(Color.WHITE)
    return RippleDrawable(ColorStateList.valueOf(p.press), circle, circleMask)
}

/** Rebote al pulsar: se hunde un poco y vuelve con un pequeño muelle. */
@SuppressLint("ClickableViewAccessibility")
fun View.springy(scale: Float = 0.88f): View {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(scale).scaleY(scale).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
        }
        false
    }
    return this
}

/** Entrada escalonada: aparece subiendo y con fundido. */
fun View.enter(delayMs: Long, fromDp: Int = 26) {
    alpha = 0f
    translationY = context.dp(fromDp).toFloat()
    animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(520)
        .setInterpolator(OvershootInterpolator(1.1f)).start()
}

/** Anillo que late detrás del botón de reproducir mientras suena la música. */
class PulseRing(ctx: Context, private val color: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(2).toFloat()
    }
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    fun setActive(on: Boolean) {
        if (on && !anim.isStarted) anim.start()
        if (!on && anim.isStarted) { anim.cancel(); phase = 0f; invalidate() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anim.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        if (!anim.isStarted) return
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) * 0.30f
        val max = minOf(width, height) * 0.5f
        for (k in 0..1) {
            val t = (phase + k * 0.5f) % 1f
            paint.color = color
            paint.alpha = ((1f - t) * 140).toInt()
            canvas.drawCircle(cx, cy, base + (max - base) * t, paint)
        }
    }
}
