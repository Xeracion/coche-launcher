package es.fran.cochelauncher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import kotlin.math.roundToInt

/**
 * Servicio de conducción. Funciona aunque la pantalla de inicio no esté visible
 * (por ejemplo con Android Auto delante) y se encarga de:
 *  1. Subir el volumen con la velocidad y bajarlo al frenar.
 *  2. Ajustar el brillo de la pantalla de día y de noche.
 *  3. Al encender el coche: reanudar la música y abrir Android Auto al conectar el móvil por cable.
 */
class DriveService : Service() {

    companion object {
        const val ACTION_REFRESH = "es.fran.cochelauncher.REFRESH"
        private const val CHANNEL = "conduccion"

        /** La pantalla de inicio se apunta aquí mientras está visible. */
        var onPhoneConnected: (() -> Unit)? = null
        /** Móvil conectado por cable sin haber abierto aún Android Auto. */
        var pendingAuto = false
        var autoDoneThisWake = false

        fun start(ctx: Context, refresh: Boolean = false) {
            val i = Intent(ctx, DriveService::class.java)
            if (refresh) i.action = ACTION_REFRESH
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // Si Android no deja arrancarlo ahora, lo intentará la próxima vez que se vea el inicio.
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var audio: AudioManager
    private var lastFix: Location? = null
    private var listening = false
    private var receiversOn = false

    // ---------- ciclo de vida ----------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        goForeground()
        registerReceivers()
        Sound.init(this)
        handler.post(brightnessLoop)
        scheduleAutoPlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        startLocation()
        if (intent?.action == ACTION_REFRESH) {
            lastBrightTarget = -1
            applyBrightness()
            if (prefs.getInt("spdvol", 1) == 0) restoreBaseVolume()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopLocation()
        if (receiversOn) {
            try { unregisterReceiver(receiver) } catch (e: Exception) { }
        }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Conducción", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            })
        }
        val n: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Inicio coche")
            .setContentText("Volumen, brillo y arranque automáticos")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29 && hasLocation()) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            try { startForeground(1, n) } catch (e2: Exception) { }
        }
    }

    // ---------- 1. Volumen según la velocidad ----------

    private var baseVolume = -1
    private var appliedVolume = -1
    private var boost = 0

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val locListener = LocationListener { loc -> onFix(loc) }

    private fun startLocation() {
        if (listening || !hasLocation()) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
                listening = true
            }
        } catch (e: Exception) {
        }
    }

    private fun stopLocation() {
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locListener) } catch (e: Exception) { }
        listening = false
    }

    private fun onFix(loc: Location) {
        val prev = lastFix
        var mps = if (loc.hasSpeed()) loc.speed.toDouble() else -1.0
        if (mps < 0 && prev != null) {
            val dt = (loc.time - prev.time) / 1000.0
            if (dt > 0) mps = prev.distanceTo(loc) / dt
        }
        lastFix = loc
        if (mps < 0) return
        onSpeed((mps * 3.6).roundToInt())
    }

    /** Umbrales en km/h. Cada umbral superado sube un escalón de volumen. */
    private fun thresholds(level: Int): IntArray = when (level) {
        1 -> intArrayOf(60, 100)            // suave
        2 -> intArrayOf(45, 75, 100, 115)   // fuerte
        else -> intArrayOf()
    }

    private fun onSpeed(kmh: Int) {
        val level = prefs.getInt("spdvol", 1)
        if (level == 0) return
        val th = thresholds(level)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Si el conductor ha tocado el volumen, su elección pasa a ser la base.
        if (baseVolume < 0 || (appliedVolume >= 0 && cur != appliedVolume)) {
            baseVolume = (cur - boost * unit(max)).coerceAtLeast(0)
            appliedVolume = cur
        }

        // Histéresis: sube al pasar el umbral, baja 8 km/h por debajo. Así no oscila.
        var b = boost.coerceAtMost(th.size)
        while (b < th.size && kmh >= th[b]) b++
        while (b > 0 && kmh < th[b - 1] - 8) b--
        if (b == boost) return
        boost = b
        val target = (baseVolume + boost * unit(max)).coerceIn(0, max)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            appliedVolume = target
        } catch (e: Exception) {
        }
    }

    /** Un escalón: alrededor de un 7 % del volumen máximo de la radio. */
    private fun unit(max: Int) = (max / 15f).roundToInt().coerceAtLeast(1)

    private fun restoreBaseVolume() {
        if (boost > 0 && baseVolume >= 0) {
            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, baseVolume, 0) } catch (e: Exception) { }
        }
        boost = 0
        baseVolume = -1
        appliedVolume = -1
    }

    // ---------- 2. Brillo de día y de noche ----------

    private var lastBrightTarget = -1

    private val brightnessLoop = object : Runnable {
        override fun run() {
            applyBrightness()
            handler.postDelayed(this, 30_000)
        }
    }

    /** Solo actúa al cambiar de día a noche (o al tocar los ajustes), así respeta los cambios manuales. */
    private fun applyBrightness() {
        if (!prefs.getBoolean("bright_auto", false)) return
        if (!Brightness.canWrite(this)) return
        val night = Theme.isDark(this)
        val target = if (night) prefs.getInt("bright_night", 70) else prefs.getInt("bright_day", 230)
        if (target == lastBrightTarget) return
        lastBrightTarget = target
        Brightness.fadeTo(this, target, handler)
    }

    // ---------- 3. Arranque inteligente ----------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    autoDoneThisWake = false
                    lastBrightTarget = -1
                    applyBrightness()
                    scheduleAutoPlay()
                    // Si el cable ya estaba puesto al encender el coche.
                    handler.postDelayed({ if (phoneOnUsb()) phoneConnected() }, 2_000)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    autoDoneThisWake = false
                    pendingAuto = false
                    restoreBaseVolume()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (dev != null && isPhone(dev)) phoneConnected()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // Al quitar el cable, la próxima conexión vuelve a abrir Android Auto.
                    if (!phoneOnUsb()) {
                        pendingAuto = false
                        autoDoneThisWake = false
                    }
                }
            }
        }
    }

    private fun registerReceivers() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(receiver, f)
            receiversOn = true
        } catch (e: Exception) {
        }
    }

    /** Unos segundos después de encender, si no suena nada, da al play. */
    private fun scheduleAutoPlay() {
        if (!prefs.getBoolean("auto_play", false)) return
        handler.postDelayed({
            if (!audio.isMusicActive) {
                try {
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                } catch (e: Exception) {
                }
            }
        }, 6_000)
    }

    /**
     * ¿Es un móvil lo que se ha enchufado? Descarta pendrives, discos, teclados,
     * concentradores y tarjetas de sonido; el resto (MTP, modo accesorio de Android Auto...) cuenta como móvil.
     */
    private fun isPhone(d: UsbDevice): Boolean {
        if (d.vendorId == 0x18D1 && d.productId in 0x2D00..0x2D05) return true // modo Android Auto
        if (d.deviceClass == UsbConstants.USB_CLASS_HUB) return false
        if (d.interfaceCount == 0) return false
        for (i in 0 until d.interfaceCount) {
            when (d.getInterface(i).interfaceClass) {
                UsbConstants.USB_CLASS_MASS_STORAGE,
                UsbConstants.USB_CLASS_HID,
                UsbConstants.USB_CLASS_HUB,
                UsbConstants.USB_CLASS_AUDIO -> return false
            }
        }
        return true
    }

    private fun phoneOnUsb(): Boolean = try {
        (getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.any { isPhone(it) }
    } catch (e: Exception) {
        false
    }

    private fun phoneConnected() {
        if (!prefs.getBoolean("auto_aa", false) || autoDoneThisWake) return
        // Damos unos segundos para que el móvil termine de negociar la conexión.
        handler.postDelayed({
            if (autoDoneThisWake) return@postDelayed
            val cb = onPhoneConnected
            if (cb != null) {
                cb()
            } else {
                // La pantalla de inicio no está delante: lo intentamos directamente,
                // y si Android no lo permite, lo hará el inicio al volver a verse.
                pendingAuto = true
                if (Settings.canDrawOverlays(this)) {
                    autoDoneThisWake = true
                    pendingAuto = false
                    Apps.launch(this, Apps.resolve(this, Slot.AUTO))
                }
            }
        }, 3_000)
    }
}

/** Brillo del sistema, con transición suave. Necesita el permiso de "modificar ajustes del sistema". */
object Brightness {
    fun canWrite(ctx: Context) = Settings.System.canWrite(ctx)

    fun current(ctx: Context): Int = try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Exception) {
        128
    }

    fun set(ctx: Context, v: Int) {
        try {
            val cr = ctx.contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(5, 255))
        } catch (e: Exception) {
        }
    }

    fun fadeTo(ctx: Context, target: Int, handler: Handler) {
        val from = current(ctx)
        val steps = 15
        for (i in 1..steps) {
            handler.postDelayed({ set(ctx, from + (target - from) * i / steps) }, i * 100L)
        }
    }
}
