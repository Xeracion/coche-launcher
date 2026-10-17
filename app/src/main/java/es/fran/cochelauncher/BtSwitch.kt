package es.fran.cochelauncher

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * En estas radios el sonido Bluetooth solo sale por los altavoces cuando la app Música BT
 * de fábrica conmuta el canal de audio. Abrimos esa app un instante, damos al play
 * y volvemos a la pantalla de inicio.
 */
object BtSwitch {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    /** ¿La música viene del móvil por Bluetooth (o no sabemos de dónde)? */
    fun isBtSource(ctx: Context, pkg: String?): Boolean {
        if (pkg == null) return true
        val bt = Apps.resolve(ctx, Slot.BTMUSIC)?.component?.packageName
        return pkg == bt || pkg.contains("bluetooth", true) || pkg.contains(".bt", true)
    }

    fun warmAndPlay(ctx: Context, play: Boolean) {
        if (busy) return
        val app = ctx.applicationContext
        val bt = Apps.resolve(app, Slot.BTMUSIC)
        if (bt == null) {
            if (play) sendPlay(app)
            return
        }
        busy = true
        try {
            app.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(bt.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (e: Exception) {
            busy = false
            if (play) sendPlay(app)
            return
        }
        if (play) h.postDelayed({ sendPlay(app) }, 1200)
        h.postDelayed({
            try {
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            } catch (e: Exception) {
            }
            busy = false
        }, 1900)
    }

    fun sendPlay(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        } catch (e: Exception) {
        }
    }
}
