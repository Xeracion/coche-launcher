package es.fran.cochelauncher

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Botón de asistente: pide al móvil, por Bluetooth, que abra su asistente de voz
 * (en el móvil de Fran, Claude). Es la misma orden que manda el botón de voz del volante
 * en los coches con manos libres (comando AT+BVRA del perfil de manos libres).
 *
 * La radio no necesita internet: el móvil escucha por el micrófono del coche
 * y responde por los altavoces, como en una llamada.
 *
 * Vías, por orden:
 *  1. App o pantalla elegida a mano (manteniendo pulsado el botón).
 *  2. Perfil de manos libres de Android (BluetoothHeadsetClient.startVoiceRecognition).
 *     Es una función oculta del sistema; puede que la radio no la deje usar.
 */
object Voice {
    private const val PREF_TARGET = "voice_target"
    /** BluetoothProfile.HEADSET_CLIENT (oculto en el SDK público). */
    private const val HEADSET_CLIENT = 16
    private val h = Handler(Looper.getMainLooper())

    @Volatile var lastReport = ""
        private set

    fun needsPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

    /** Pantalla elegida a mano, si la hay. */
    fun savedTarget(ctx: Context): ComponentName? =
        ctx.getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE).getString(PREF_TARGET, null)
            ?.let { ComponentName.unflattenFromString(it) }

    fun saveTarget(ctx: Context, cn: ComponentName?) {
        ctx.getSharedPreferences(Apps.PREFS, Context.MODE_PRIVATE).edit().apply {
            if (cn == null) remove(PREF_TARGET) else putString(PREF_TARGET, cn.flattenToString())
        }.apply()
    }

    /**
     * Pulsación del botón. Devuelve por [done] un texto corto para el aviso en pantalla,
     * o null si todo fue bien.
     */
    fun activate(ctx: Context, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        savedTarget(app)?.let { cn ->
            try {
                app.startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Diag.append(app, "asistente: abro " + cn.flattenToShortString())
                done(null)
            } catch (e: Exception) {
                done("No se pudo abrir la pantalla elegida")
            }
            return
        }
        Thread {
            val r = startOverHfp(app, StringBuilder())
            Diag.append(app, "asistente: manos libres -> " + r.name)
            val msg = when (r) {
                Result.OK -> null
                Result.NO_PERMISSION -> "Falta el permiso de Bluetooth"
                Result.NO_PHONE -> "No hay ningún móvil conectado por manos libres"
                else -> "La radio no deja activar el asistente. Mira Ajustes > Avanzado"
            }
            h.post { done(msg) }
        }.start()
    }

    enum class Result { OK, NO_PERMISSION, NO_ADAPTER, NO_PROFILE, NO_PHONE, BLOCKED, REFUSED }

    /** Intenta activar el asistente del móvil por el perfil de manos libres. Solo desde un hilo secundario. */
    private fun startOverHfp(app: Context, sb: StringBuilder): Result {
        if (needsPermission(app)) {
            sb.append("Permiso de Bluetooth: falta\n")
            return Result.NO_PERMISSION
        }
        sb.append("Permiso de Bluetooth: concedido\n")
        val adapter: BluetoothAdapter? = try {
            (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
        } catch (e: Exception) { null }
        if (adapter == null) {
            sb.append("Bluetooth de Android: no disponible\n")
            return Result.NO_ADAPTER
        }
        var proxy: BluetoothProfile? = null
        val latch = CountDownLatch(1)
        val ok = try {
            adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile?) { proxy = p; latch.countDown() }
                override fun onServiceDisconnected(profile: Int) {}
            }, HEADSET_CLIENT)
        } catch (e: Exception) {
            sb.append("Perfil de manos libres: error ").append(e.javaClass.simpleName).append('\n')
            false
        }
        if (ok) latch.await(3, TimeUnit.SECONDS)
        val p = proxy
        if (p == null) {
            sb.append("Perfil de manos libres de Android: no responde (la radio usa su propio Bluetooth)\n")
            return Result.NO_PROFILE
        }
        try {
            val devices: List<BluetoothDevice> = try { p.connectedDevices } catch (e: Exception) { emptyList() }
            sb.append("Perfil de manos libres de Android: disponible\n")
            if (devices.isEmpty()) {
                sb.append("Móviles conectados a ese perfil: ninguno\n")
                return Result.NO_PHONE
            }
            val dev = devices.first()
            sb.append("Móvil conectado: ").append(try { dev.name } catch (e: Exception) { "?" }).append('\n')
            // Android esconde esta función a las apps normales; pedimos permiso para verla.
            if (Build.VERSION.SDK_INT >= 28) {
                val freed = try {
                    org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")
                } catch (e: Throwable) { false }
                sb.append("Acceso a funciones ocultas: ").append(if (freed) "conseguido" else "denegado").append('\n')
            }
            val m = try {
                p.javaClass.getMethod("startVoiceRecognition", BluetoothDevice::class.java)
            } catch (e: Throwable) {
                sb.append("Orden de asistente: bloqueada por Android (").append(e.javaClass.simpleName).append(")\n")
                return Result.BLOCKED
            }
            val res = try {
                m.invoke(p, dev) as? Boolean ?: false
            } catch (e: Throwable) {
                val cause = (e.cause ?: e).javaClass.simpleName
                sb.append("Orden de asistente: rechazada (").append(cause).append(")\n")
                return Result.BLOCKED
            }
            sb.append("Orden de asistente: ").append(if (res) "enviada al móvil" else "el móvil o la radio la rechazó").append('\n')
            return if (res) Result.OK else Result.REFUSED
        } finally {
            try { adapter.closeProfileProxy(HEADSET_CLIENT, p) } catch (e: Exception) { }
        }
    }

    /** Pantallas de fábrica que parecen de asistente de voz, para elegir a mano. */
    fun candidates(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val words = listOf("voice", "siri", "assist", "speech", "recogn", "vr", "voz", "asistente")
        val out = mutableListOf<AppEntry>()
        for (pkg in vendorPackages(ctx)) {
            val info = try { pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES) } catch (e: Exception) { continue }
            for (a in info.activities ?: emptyArray()) {
                if (!a.exported) continue
                val short = a.name.substringAfterLast('.').lowercase()
                if (words.any { short.contains(it) }) {
                    val label = try { a.loadLabel(pm).toString() } catch (e: Exception) { short }
                    out += AppEntry("$label (${a.name.substringAfterLast('.')})", ComponentName(pkg, a.name))
                }
            }
        }
        return out.distinctBy { it.component }
    }

    /** Apps de fábrica relacionadas con teléfono y Bluetooth. */
    private fun vendorPackages(ctx: Context): Set<String> {
        val set = linkedSetOf<String>()
        for (s in listOf(Slot.PHONE, Slot.BTMUSIC)) Apps.resolve(ctx, s)?.component?.packageName?.let { set += it }
        try {
            for (ai in ctx.packageManager.getInstalledApplications(0)) {
                val n = ai.packageName.lowercase()
                if (n.startsWith("com.hzbhd") || n.contains("bluetooth") || n.contains(".bt") || n.contains("voice")) set += ai.packageName
            }
        } catch (e: Exception) { }
        return set
    }

    /** Informe completo para la pantalla de ajustes. Solo desde un hilo secundario. */
    fun diagnose(ctx: Context): String {
        val app = ctx.applicationContext
        val sb = StringBuilder()
        savedTarget(app)?.let { sb.append("Pantalla elegida a mano: ").append(it.flattenToShortString()).append("\n\n") }
        val before = BtSwitch.run { connect(app); currentSource() }
        val r = startOverHfp(app, sb)
        if (r == Result.OK) {
            try { Thread.sleep(1500) } catch (e: InterruptedException) { }
            sb.append("Canal de audio de la radio: antes nº ").append(before)
                .append(", ahora nº ").append(BtSwitch.currentSource()).append('\n')
            sb.append("\nFunciona: el móvil debería abrir el asistente ahora.")
        } else {
            sb.append("\nNo funciona por esta vía.")
        }

        // Pistas para la siguiente versión: componentes de fábrica con nombre de voz.
        val pm = app.packageManager
        val words = listOf("voice", "siri", "assist", "speech", "recogn", "voz")
        val hints = mutableListOf<String>()
        for (pkg in vendorPackages(app)) {
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
            val info = try { pm.getPackageInfo(pkg, flags) } catch (e: Exception) { continue }
            val all = (info.activities ?: emptyArray()).map { "pantalla" to it.name } +
                (info.services ?: emptyArray()).map { "servicio" to it.name } +
                (info.receivers ?: emptyArray()).map { "receptor" to it.name }
            for ((kind, name) in all) {
                if (words.any { name.substringAfterLast('.').lowercase().contains(it) }) hints += "$kind: $pkg/${name.removePrefix(pkg)}"
            }
        }
        sb.append("\n\nPiezas de fábrica con nombre de voz: ")
        if (hints.isEmpty()) sb.append("ninguna") else sb.append('\n').append(hints.take(25).joinToString("\n"))
        lastReport = sb.toString()
        Diag.append(app, "asistente: prueba -> " + r.name + ", pistas " + hints.size)
        return lastReport
    }
}
