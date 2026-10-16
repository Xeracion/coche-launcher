package es.fran.cochelauncher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity

/**
 * "Apagar pantalla": pantalla totalmente negra con el brillo al mínimo.
 * La música y Android Auto siguen funcionando. Tocar en cualquier sitio la vuelve a encender.
 */
class BlackoutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.attributes = window.attributes.apply { screenBrightness = 0.0f }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // Aviso muy tenue que desaparece a los 2 segundos.
        val hint = TextView(this).apply {
            text = "Toca para encender"
            setTextColor(Color.parseColor("#333333"))
            textSize = 16f
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.postDelayed({ hint.visibility = View.GONE }, 2000)
        root.setOnClickListener { finish() }
        setContentView(root)
        hideBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}
