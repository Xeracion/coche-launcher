package es.fran.cochelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.widget.Toast
import java.text.Normalizer

data class AppEntry(val label: String, val component: ComponentName) {
    fun icon(ctx: Context): Drawable? = try {
        ctx.packageManager.getActivityIcon(component)
    } catch (e: Exception) {
        null
    }
}

/**
 * Huecos configurables de la pantalla. Cada uno tiene palabras clave para encontrar
 * automáticamente la app de fábrica de la radio por su nombre. Si la detección falla,
 * se puede elegir la app manteniendo pulsado el botón.
 */
enum class Slot(val title: String, val keywords: List<String>) {
    PHONE("Teléfono", listOf("telefono bt", "bt phone", "telefono", "phone", "bluetooth phone")),
    RADIO("Radio", listOf("radio", "fm radio", "fm")),
    BTMUSIC("Música BT", listOf("musica bt", "bt music", "bluetooth music", "a2dp", "musica bluetooth")),
    AUTO("Android Auto", listOf("androidlink", "android link", "android auto", "zlink", "carlink", "autolink")),
}

object Apps {
    private var cache: List<AppEntry>? = null

    fun all(ctx: Context, refresh: Boolean = false): List<AppEntry> {
        if (!refresh) cache?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = ctx.packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map {
                AppEntry(
                    it.loadLabel(ctx.packageManager).toString(),
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                )
            }
            .distinctBy { it.component }
            .sortedBy { norm(it.label) }
        cache = list
        return list
    }

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .trim()

    /** App asignada a un hueco: la elegida a mano, o la detectada por nombre. */
    fun resolve(ctx: Context, slot: Slot): AppEntry? {
        val apps = all(ctx)
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("slot_" + slot.name, null)
        if (saved != null) {
            val cn = ComponentName.unflattenFromString(saved)
            apps.firstOrNull { it.component == cn }?.let { return it }
        }
        for (k in slot.keywords) {
            apps.firstOrNull { norm(it.label) == k }?.let { return it }
        }
        for (k in slot.keywords) {
            if (k.length < 3) continue
            apps.firstOrNull { norm(it.label).contains(k) }?.let { return it }
        }
        return null
    }

    fun assign(ctx: Context, slot: Slot, app: AppEntry?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (app == null) remove("slot_" + slot.name) else putString("slot_" + slot.name, app.component.flattenToString())
        }.apply()
    }

    fun launch(ctx: Context, app: AppEntry?) {
        if (app == null) {
            Toast.makeText(ctx, "Mantén pulsado para elegir la app", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            DriveService.userNavigatedAway = true
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "No se pudo abrir " + app.label, Toast.LENGTH_SHORT).show()
        }
    }

    const val PREFS = "cfg"
}
