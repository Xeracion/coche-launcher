package es.fran.cochelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.KeyEvent

/**
 * Canal de audio de la radio (plataforma HZBHD).
 *
 * El sonido solo sale por los altavoces cuando el gestor de fuentes de fábrica
 * (servicio "hzbhd_SourceService", interfaz ISourceService) tiene activa la fuente adecuada.
 * La app Música BT lo consigue al ponerse delante; nosotros se lo pedimos directamente
 * con requestAudioChannel, sin abrir nada.
 *
 * Códigos sacados del firmware: requestAudioChannel = 3, getCurrentSource = 6.
 * Fuentes: 18 = FM, 22 = música Bluetooth, 21 = teléfono Bluetooth.
 */
object BtSwitch {
    private const val TAG = "InicioCoche"
    private const val DESCRIPTOR = "com.hzbhd.proxy.sourcemanager.aidl.ISourceService"
    private const val TX_REQUEST = 3
    private const val TX_CURRENT = 6
    const val SRC_FM = 18
    const val SRC_BT_AUDIO = 22
    private const val DISP_MAIN = 0

    private val h = Handler(Looper.getMainLooper())
    @Volatile private var binder: IBinder? = null
    @Volatile private var busy = false
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }

    /** Se conecta al gestor de fuentes de fábrica. Se llama al arrancar. */
    fun connect(ctx: Context) {
        if (binder != null || bound) return
        val app = ctx.applicationContext
        try {
            val i = Intent("com.hzbhd.midware.service.sourcemanager").setPackage("com.hzbhd.service.sourcemanager")
            bound = app.bindService(i, conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            bound = false
        }
        if (!bound) {
            // Plan B: pedirlo al registro de servicios del sistema.
            try {
                val sm = Class.forName("android.os.ServiceManager")
                binder = sm.getMethod("getService", String::class.java).invoke(null, "hzbhd_SourceService") as IBinder?
            } catch (e: Throwable) {
            }
        }
    }

    /** Fuente de audio activa, o -1 si no se puede saber. */
    fun currentSource(): Int {
        val b = binder ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(DISP_MAIN)
            b.transact(TX_CURRENT, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Pide una fuente de audio al gestor de fábrica. Devuelve true si queda activa. */
    fun requestSource(source: Int): Boolean {
        val b = binder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(source)
            data.writeInt(DISP_MAIN)
            data.writeInt(0) // sin Bundle: no abre ninguna pantalla
            b.transact(TX_REQUEST, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioChannel falló", e)
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
        val now = currentSource()
        Log.i(TAG, "requestAudioChannel($source) -> fuente actual $now")
        return now == source || now == -1
    }

    /** ¿La música viene del móvil por Bluetooth (o no sabemos de dónde)? */
    fun isBtSource(ctx: Context, pkg: String?): Boolean {
        if (pkg == null) return true
        val bt = Apps.resolve(ctx, Slot.BTMUSIC)?.component?.packageName
        return pkg == bt || pkg.contains("bluetooth", true) || pkg.contains(".bt", true)
    }

    /** Pone el canal Bluetooth y, si se pide, da al play. */
    fun warmAndPlay(ctx: Context, play: Boolean) {
        val app = ctx.applicationContext
        connect(app)
        if (requestSource(SRC_BT_AUDIO)) {
            if (play) h.postDelayed({ sendPlay(app) }, 300)
            return
        }
        // Si el binder aún no estaba listo, reintentamos una vez en un momento.
        h.postDelayed({
            if (requestSource(SRC_BT_AUDIO)) {
                if (play) h.postDelayed({ sendPlay(app) }, 300)
            } else {
                legacyWarm(app, play)
            }
        }, 700)
    }

    /** Respaldo: abrir un instante Música BT, que conmuta el canal, y volver. */
    private fun legacyWarm(app: Context, play: Boolean) {
        if (busy) return
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
